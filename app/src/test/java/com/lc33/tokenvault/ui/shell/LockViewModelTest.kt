package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.PinPolicy
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BootState
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.screens.lock.OnboardingStep
import com.lc33.tokenvault.screens.lock.PinError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 锁闸的 ViewModel（§14.3 测试 6 的桥接那一半）。
 *
 * 用**真的** [VaultSession] + 真的 [FileBootStore]：这一层最容易错的地方恰好是
 * "阶段与 boot 里写了什么对不对得上"，用假会话就把要测的东西替换掉了。
 *
 * `Dispatchers.setMain` 用 [StandardTestDispatcher]，而 ViewModel 里的 PBKDF2 走
 * `withContext(Dispatchers.Default)` ——那是真的线程池、真的几百毫秒。所以断言前要
 * `awaitIdle()`（先把主调度器排空、再等真实的后台工作落地），不能只 `runCurrent()`。
 * 引导参数被 [slowClock] 压到下限，所以整套仍是秒级。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: FileBootStore
    private lateinit var session: VaultSession
    private lateinit var autoLocker: AutoLocker
    private lateinit var scope: TestScope
    private val dispatcher = StandardTestDispatcher()

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = TestScope(dispatcher)
        val file = File(temp.newFolder("files"), FileBootStore.FILE_NAME)
        store = FileBootStore(file) { "test-device" }
        session = VaultSession(
            bootStore = store,
            nowEpochMs = { now },
        )
        autoLocker = AutoLocker(session, scope) { 0L }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = LockViewModel(
        session = session,
        bootStore = store,
        autoLocker = autoLocker,
    )

    private fun record() = (store.read() as? BootState.Ok)?.record

    /**
     * 把主调度器排空，并等真实后台工作（PBKDF2 在 `Dispatchers.Default` 上）落地。
     *
     * 不能只 `runCurrent()`：ViewModel 里每一步都是 `launch { withContext(Default) { … } }`，
     * 后半段发生在另一个真实线程上，虚拟时间推不动它。所以这里轮询而不是推时间。
     */
    private fun awaitIdle(timeoutMs: Long = 20_000, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.runCurrent()
            if (until()) return
            Thread.sleep(POLL_MS)
        }
        dispatcher.scheduler.runCurrent()
        assertTrue("等了 ${timeoutMs}ms 条件还没成立", until())
    }

    private fun LockViewModel.type(pin: String) {
        pin.forEach { onPinDigit(it) }
        dispatcher.scheduler.runCurrent()
    }

    /** 走完整条引导直到解锁。 */
    private fun LockViewModel.onboardFully(pin: String = GOOD_PIN) {
        awaitIdle { phase.value == LockPhase.Onboarding }
        onOnboardingNext() // Welcome -> SetPin
        type(pin)
        assertEquals(OnboardingStep.ConfirmPin, uiState.value.onboarding.step)
        type(pin)
        awaitIdle { phase.value == LockPhase.Unlocked }
    }

    private companion object {
        const val GOOD_PIN = "194057"
        const val OTHER_PIN = "735281"
        const val POLL_MS = 5L

        /** 连错的上限。`UnlockBackoff.FREE_ATTEMPTS` 是 4，所以第 5 次才真的开始罚。 */
        const val MAX_ATTEMPTS = 8
    }

    // ------------------------------------------------------------------ 引导

    @Test
    fun `全新安装从引导第一步开始`() {
        val vm = newViewModel()
        awaitIdle { vm.phase.value == LockPhase.Onboarding }
        assertEquals(OnboardingStep.Welcome, vm.uiState.value.onboarding.step)
    }

    @Test
    fun `两次 PIN 不一致停在确认那一步并报不一致`() {
        val vm = newViewModel()
        awaitIdle { vm.phase.value == LockPhase.Onboarding }
        vm.onOnboardingNext()
        vm.type(GOOD_PIN)
        vm.type(OTHER_PIN)

        assertEquals(OnboardingStep.ConfirmPin, vm.uiState.value.onboarding.step)
        assertEquals(PinError.Mismatch, vm.uiState.value.onboarding.error)
        // 报错时要把输入清掉，否则用户得自己按六次退格
        assertEquals(0, vm.uiState.value.onboarding.pinLength)
        assertNull("不一致时不该已经写 boot", record()?.pinKdf)
    }

    @Test
    fun `弱 PIN 被拦在第一步`() {
        val vm = newViewModel()
        awaitIdle { vm.phase.value == LockPhase.Onboarding }
        vm.onOnboardingNext()
        vm.type("111111")

        assertEquals(OnboardingStep.SetPin, vm.uiState.value.onboarding.step)
        assertEquals(PinError.TooSimple, vm.uiState.value.onboarding.error)
    }

    @Test
    fun `引导完成即解锁并落盘 onboarded`() {
        val vm = newViewModel()
        vm.onboardFully()

        assertTrue(record()!!.onboarded)
        assertEquals(LockPhase.Unlocked, vm.phase.value)
    }

    // ------------------------------------------------------------------ 解锁与退避

    /** 走完整条引导再锁上，得到一个"冷启动落到锁屏"的起点。 */
    private fun lockedVaultViewModel(): LockViewModel {
        val setup = newViewModel()
        setup.onboardFully()
        session.lock()
        val vm = newViewModel()
        awaitIdle { vm.phase.value is LockPhase.Locked }
        return vm
    }

    @Test
    fun `PIN 对了就解锁，失败计数清零`() {
        val vm = lockedVaultViewModel()
        vm.type(GOOD_PIN)
        awaitIdle { vm.phase.value == LockPhase.Unlocked }
        assertEquals(0, record()!!.pinFailCount)
        assertNull(vm.uiState.value.unlock.error)
    }

    @Test
    fun `PIN 错了报错并累加退避，倒计时进 UI`() {
        val vm = lockedVaultViewModel()
        vm.type(OTHER_PIN)
        awaitIdle { vm.uiState.value.unlock.error != null }

        assertEquals(PinError.Wrong, vm.uiState.value.unlock.error)
        assertEquals(1, record()!!.pinFailCount)
        val locked = vm.phase.value as LockPhase.Locked
        assertEquals(1, locked.backoff.failedAttempts)
    }

    @Test
    fun `退避中那一次不报密钥错误`() {
        val vm = lockedVaultViewModel()
        // 连错到退避真的生效为止（前几次 UnlockBackoff 是不罚的宽限期）
        var guard = 0
        while (guard++ < MAX_ATTEMPTS) {
            vm.type(OTHER_PIN)
            awaitIdle { !vm.uiState.value.unlock.busy }
            if ((vm.phase.value as LockPhase.Locked).backoff.isActive(now)) break
        }
        assertTrue("前置条件：退避已经生效", (vm.phase.value as LockPhase.Locked).backoff.isActive(now))

        vm.type(OTHER_PIN)
        awaitIdle { !vm.uiState.value.unlock.busy }
        // 这一次根本没去试，说"PIN 不对"是假话；倒计时由界面从 backoff 自己画
        assertNull("退避中不该报密钥错误", vm.uiState.value.unlock.error)
    }

    // ------------------------------------------------------------------ 锁定

    @Test
    fun `锁定后界面上的明文状态被清空并换回锁屏`() {
        val setup = newViewModel()
        setup.onboardFully()

        val vm = newViewModel()
        awaitIdle { vm.phase.value == LockPhase.Unlocked }

        vm.onLockNow()
        awaitIdle { vm.phase.value is LockPhase.Locked }

        // §7.4：锁定时清空所有已展开的明文 UI 状态
        assertEquals(0, vm.uiState.value.unlock.pinLength)
        assertNull(vm.uiState.value.unlock.error)
        assertEquals(PinPolicy.DEFAULT_SLOTS, vm.uiState.value.unlock.pinSlots)
        assertFalse(session.isUnlocked)
    }
}

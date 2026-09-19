package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.PinPolicy
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BiometricEnableOutcome
import com.lc33.tokenvault.platform.BiometricPromptText
import com.lc33.tokenvault.platform.BiometricUnlockOutcome
import com.lc33.tokenvault.platform.BiometricVault
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
import org.junit.Assert.assertArrayEquals
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
 * 引导用的 KDF 参数由 [com.lc33.tokenvault.crypto.Pbkdf2Kdf.benchmark] 现场标定，
 * 这台机器上一次只有零点几秒，所以整套仍是秒级。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: FileBootStore
    private lateinit var bootFile: File
    private lateinit var session: VaultSession
    private lateinit var autoLocker: AutoLocker
    private lateinit var scope: TestScope
    private val dispatcher = StandardTestDispatcher()

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = TestScope(dispatcher)
        bootFile = File(temp.newFolder("files"), FileBootStore.FILE_NAME)
        store = FileBootStore(bootFile) { "test-device" }
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

    private fun newViewModel(vault: BiometricVault = unavailableBiometric) = LockViewModel(
        session = session,
        bootStore = store,
        autoLocker = autoLocker,
        vault = vault,
    )

    /** 验证框的三段文案由 composable 层解析，测试里给什么都行——只要**不是中文**（红线 19）。 */
    private val prompt = BiometricPromptText(title = "Unlock", subtitle = "Verify", cancel = "Use PIN")

    /** 这套用例只测 PIN 那条路，生物识别一律"这台设备用不了"。 */
    private val unavailableBiometric = object : BiometricVault {
        override fun isAvailable(): Boolean = false
        override suspend fun enable(prompt: BiometricPromptText): BiometricEnableOutcome =
            BiometricEnableOutcome.Unavailable

        override suspend fun unlock(blob: ByteArray?, prompt: BiometricPromptText): BiometricUnlockOutcome =
            BiometricUnlockOutcome.Invalidated

        override fun disable() = Unit
    }

    /**
     * 回固定结果的生物识别替身，并记下 `disable()` 被调了几次。
     *
     * 为什么非要记次数：这一层最要紧的三条规则里，两条是**否定式**的——
     * "被系统锁住时什么都不许改"（改了就把等一会儿就能用的凭据关掉）与
     * "清空重来必须连平台凭据一起删"（不删就留下一份活着的安全材料）。
     * 只看 boot 里写了什么判不出前者，只看界面判不出后者。
     */
    private class FakeBiometric(
        private val outcome: BiometricUnlockOutcome,
    ) : BiometricVault {
        var disableCalls = 0

        override fun isAvailable(): Boolean = true

        override suspend fun enable(prompt: BiometricPromptText): BiometricEnableOutcome =
            BiometricEnableOutcome.Unavailable

        override suspend fun unlock(blob: ByteArray?, prompt: BiometricPromptText): BiometricUnlockOutcome =
            outcome

        override fun disable() {
            disableCalls++
        }
    }

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
    private fun lockedVaultViewModel(vault: BiometricVault = unavailableBiometric): LockViewModel {
        val setup = newViewModel()
        setup.onboardFully()
        session.lock()
        val vm = newViewModel(vault)
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

    // ------------------------------------------------------------------ 生物识别的三档善后

    @Test
    fun `生物识别被系统锁住时什么都不许改`() {
        val vault = FakeBiometric(BiometricUnlockOutcome.LockedOut)
        val vm = lockedVaultViewModel(vault)
        store.update { it.copy(biometricEnabled = true, dekWrappedByBiometric = byteArrayOf(1, 2, 3)) }

        vm.unlockWithBiometric(prompt)
        awaitIdle { vm.uiState.value.unlock.error != null }

        // 锁住是"过一会儿再来"，不是"这条路坏了"：删凭据、关开关都会把一件没坏的事改成要重开一次
        assertEquals(PinError.BiometryLockedOut, vm.uiState.value.unlock.error)
        assertEquals("被系统锁住时不许动平台凭据", 0, vault.disableCalls)
        val after = record()!!
        assertTrue(after.biometricEnabled)
        assertArrayEquals(byteArrayOf(1, 2, 3), after.dekWrappedByBiometric)
        assertTrue(vm.phase.value is LockPhase.Locked)
    }

    @Test
    fun `生物识别这一步没成时报改用PIN而不是PIN错误`() {
        val vault = FakeBiometric(BiometricUnlockOutcome.Error("keychain add failed: -34018"))
        val vm = lockedVaultViewModel(vault)

        vm.unlockWithBiometric(prompt)
        awaitIdle { vm.uiState.value.unlock.error != null }

        // 这一次根本没试过 PIN，说"PIN 不对"是假话；但也不能报"暂时锁住"——那是另一种等法
        assertEquals(PinError.BiometryFailed, vm.uiState.value.unlock.error)
        assertEquals(0, vault.disableCalls)
    }

    @Test
    fun `凭据失效而boot已读不通时落到恢复页而不是崩在锁屏上`() {
        val vault = FakeBiometric(BiometricUnlockOutcome.Invalidated)
        val vm = lockedVaultViewModel(vault)
        store.update { it.copy(biometricEnabled = true, dekWrappedByBiometric = byteArrayOf(7)) }
        // 善后那一步要写 boot，而这一刻文件已经解析不出来了（撕裂写入 / 被人改过）
        bootFile.writeText("{ not json at all")

        vm.unlockWithBiometric(prompt)
        awaitIdle { vm.phase.value is LockPhase.BootCorrupt }

        // 关键在"异常没从锁屏冒出去"：走到了恢复页，而那份损坏文件没被增量写覆盖掉
        assertEquals(PinError.BootWriteFailed, vm.uiState.value.unlock.error)
        assertEquals(1, vault.disableCalls)
        assertTrue(bootFile.readText().startsWith("{ not json"))
    }

    @Test
    fun `清空重来连平台凭据一起抹掉`() {
        val vault = FakeBiometric(BiometricUnlockOutcome.Invalidated)
        newViewModel().onboardFully()
        store.update { it.copy(biometricEnabled = true) }
        val vm = newViewModel(vault)
        awaitIdle { vm.phase.value == LockPhase.Unlocked }

        vm.onWipeAndStartOver()

        // 下一次引导会生成一把全新的 DEK，留着旧那份就是留着一份还活着、却没人读得到的安全材料
        assertEquals(1, vault.disableCalls)
        assertNull("boot 应当已经清掉", store.read() as? BootState.Ok)
        assertFalse("清空重来后内存里不该还留着 DEK", session.isUnlocked)
        assertEquals(LockPhase.Onboarding, vm.phase.value)
    }

    // ------------------------------------------------------------------ 引导写盘失败

    @Test
    fun `引导写不进盘时退回设PIN那一步而不是卡在进度页`() {
        val vm = newViewModel()
        awaitIdle { vm.phase.value == LockPhase.Onboarding }
        vm.onOnboardingNext()
        // rename 的失败分支在 CI 上造不出真实条件，用 FileBootStore 的测试钩子钉住它
        store.renameOverride = { _, _ -> false }

        vm.type(GOOD_PIN)
        assertEquals(OnboardingStep.ConfirmPin, vm.uiState.value.onboarding.step)
        vm.type(GOOD_PIN)
        awaitIdle { vm.uiState.value.onboarding.error != null }

        // Calibrating 那一屏没有键盘也没有取消按钮，停在那里就等于杀掉进程重来
        assertEquals(PinError.BootWriteFailed, vm.uiState.value.onboarding.error)
        assertEquals(OnboardingStep.SetPin, vm.uiState.value.onboarding.step)
        assertFalse(vm.uiState.value.onboarding.busy)
        assertEquals(LockPhase.Onboarding, vm.phase.value)
        assertFalse("写盘失败时不该把 DEK 采纳进会话", session.isUnlocked)
    }
}

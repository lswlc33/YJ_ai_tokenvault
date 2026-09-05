package com.lc33.tokenvault.ui.shell

import androidx.fragment.app.FragmentActivity
import com.lc33.tokenvault.crypto.RecoveryKey
import com.lc33.tokenvault.domain.BiometricAvailability
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.PinPolicy
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BiometricOutcome
import com.lc33.tokenvault.platform.BiometricUnlocker
import com.lc33.tokenvault.platform.BootState
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.SecureClipboard
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 锁闸的 ViewModel（§14.3 测试 6 的桥接那一半）。
 *
 * 两条取舍：
 *
 * 1. **用真的 [VaultSession] + 真的 [FileBootStore]**，只把两个纯平台协作者换成假的
 *    （剪贴板要 `Context`，生物识别要 Android Keystore）。这一层最容易错的地方恰好是
 *    "阶段与 boot 里写了什么对不对得上"，用假会话就把要测的东西替换掉了。
 * 2. **`Dispatchers.setMain` 用 [StandardTestDispatcher]**，而 ViewModel 里的 Argon2 走
 *    `withContext(Dispatchers.Default)` ——那是真的线程池、真的几百毫秒。所以断言前要
 *    `awaitIdle()`（先把主调度器排空、再等真实的后台工作落地），不能只 `runCurrent()`。
 *    引导参数被 [slowClock] 压到下限，所以整套仍是秒级。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: FileBootStore
    private lateinit var session: VaultSession
    private lateinit var autoLocker: AutoLocker
    private lateinit var clipboard: RecordingClipboard
    private lateinit var scope: TestScope
    private val dispatcher = StandardTestDispatcher()

    /** 剪贴板：只记下被要求复制了什么，不碰系统。 */
    private class RecordingClipboard : SecureClipboard {
        val copied = mutableListOf<String>()
        var cleared = 0
        override fun copy(label: String, value: CharArray, autoClearSeconds: Int) {
            copied += String(value)
        }

        override fun clearNow() {
            cleared++
        }
    }

    /** 生物识别：这一套用例都不走那条路，调到就是测试自己写错了。 */
    private object UnusedBiometric : BiometricUnlocker {
        override suspend fun enable(
            activity: FragmentActivity,
            title: String,
            subtitle: String,
            negativeText: String,
        ): BiometricOutcome = error("这套用例不该走生物识别")

        override fun disable() = error("这套用例不该走生物识别")

        override suspend fun unlock(
            activity: FragmentActivity,
            title: String,
            subtitle: String,
            negativeText: String,
        ): BiometricOutcome = error("这套用例不该走生物识别")
    }

    /** 让 benchmark 落在"太慢"那一档，于是引导挑到下限参数，Argon2 跑得快。 */
    private fun slowClock(): () -> Long {
        var t = 0L
        return { t += 900_000_000L; t }
    }

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
            biometricAvailability = { BiometricAvailability.AVAILABLE },
        )
        autoLocker = AutoLocker(session, scope) { 0L }
        clipboard = RecordingClipboard()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = LockViewModel(
        session = session,
        bootStore = store,
        biometric = UnusedBiometric,
        clipboard = clipboard,
        autoLocker = autoLocker,
    )

    private fun record() = (store.read() as? BootState.Ok)?.record

    /**
     * 把主调度器排空，并等真实后台工作（Argon2 在 `Dispatchers.Default` 上）落地。
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

    /** 走到"抄下恢复密钥"那一步为止，返回展示串。 */
    private fun LockViewModel.onboardToRecoveryKey(pin: String = GOOD_PIN): String {
        awaitIdle { phase.value == LockPhase.Onboarding }
        onOnboardingNext() // Welcome -> SetPin
        type(pin)
        assertEquals(OnboardingStep.ConfirmPin, uiState.value.onboarding.step)
        type(pin)
        // 这一步跑 benchmark + Argon2 两次（PIN 与恢复密钥各一次），是整套里最慢的一处
        awaitIdle { uiState.value.onboarding.step == OnboardingStep.Biometric }
        onOnboardingNext() // Biometric -> RecoveryKey
        val display = uiState.value.onboarding.recoveryKeyDisplay
        assertNotNull("走到这一步必须有展示串", display)
        return display!!
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
    fun `没勾我已保存就不算走完引导`() {
        val vm = newViewModel()
        vm.onboardToRecoveryKey()

        vm.onOnboardingNext()
        dispatcher.scheduler.runCurrent()
        assertEquals(OnboardingStep.RecoveryKey, vm.uiState.value.onboarding.step)
        assertEquals(LockPhase.Onboarding, vm.phase.value)
        // 关键那一条：这一刻被杀掉，下次启动必须回到引导而不是进一个没有恢复密钥的库
        assertFalse("没确认之前 onboarded 不许落盘", record()!!.onboarded)
    }

    @Test
    fun `勾了我已保存才落盘，落盘之后明文与展示串都不留`() {
        val vm = newViewModel()
        vm.onboardToRecoveryKey()

        vm.onRecoveryKeySavedChange(true)
        vm.onOnboardingNext()
        awaitIdle { vm.phase.value == LockPhase.Unlocked }

        assertTrue(record()!!.onboarded)
        assertNull(
            "展示串是擦不掉的 String，落盘之后必须丢引用",
            vm.uiState.value.onboarding.recoveryKeyDisplay,
        )
        // 明文已经擦了，所以这时再点"复制"什么都不该发生
        vm.onCopyRecoveryKey("label")
        assertTrue(clipboard.copied.isEmpty())
    }

    @Test
    fun `复制走的是明文那一份，不是展示串`() {
        val vm = newViewModel()
        val display = vm.onboardToRecoveryKey()

        vm.onCopyRecoveryKey("label")
        assertEquals(1, clipboard.copied.size)
        // 展示串带分组分隔符，剪贴板里那份是规范化过的原文；两者规范化之后必须相等
        assertEquals(
            String(RecoveryKey.normalize(display.toCharArray())),
            String(RecoveryKey.normalize(clipboard.copied.single().toCharArray())),
        )
    }

    @Test
    fun `引导最后一步被打断，重建之后回到那一步并换一把新钥匙`() {
        val first = newViewModel()
        val firstKey = first.onboardToRecoveryKey()
        // Activity 被销毁（返回键 / 进程回收 / 不保留活动）：框架把上一个 VM 清掉，
        // 于是那串明文随它一起没了，而 DEK 还在会话（@Singleton）里。
        val second = newViewModel()
        awaitIdle { second.uiState.value.onboarding.recoveryKeyDisplay != null }

        assertEquals(LockPhase.Onboarding, second.phase.value)
        assertEquals(OnboardingStep.RecoveryKey, second.uiState.value.onboarding.step)
        // 旧的那串明文随上一个 VM 一起没了，所以只能轮换出新的一把——
        // 显示旧的做不到（boot 里只有它的包裹），跳过这一步会让用户手上一把恢复密钥都没有
        assertNotEquals(firstKey, second.uiState.value.onboarding.recoveryKeyDisplay)
        assertFalse("轮换之后仍然没走完引导", record()!!.onboarded)
    }

    // ------------------------------------------------------------------ 解锁与退避

    /** 走完整条引导再锁上，得到一个"冷启动落到锁屏"的起点。 */
    private fun lockedVaultViewModel(): LockViewModel {
        val setup = newViewModel()
        setup.onboardToRecoveryKey()
        setup.onRecoveryKeySavedChange(true)
        setup.onOnboardingNext()
        awaitIdle { setup.phase.value == LockPhase.Unlocked }
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

    @Test
    fun `恢复密钥格式不对单独报错，而且照样受罚`() {
        val vm = lockedVaultViewModel()
        vm.onEnterRecoveryMode()
        vm.onRecoveryUnlock("not-a-recovery-key".toCharArray())
        awaitIdle { vm.uiState.value.unlock.error != null }

        assertEquals(PinError.RecoveryKeyMalformed, vm.uiState.value.unlock.error)
        // 不罚的话它就成了一条不受退避约束的探测通道
        assertEquals(1, record()!!.pinFailCount)
    }

    // ------------------------------------------------------------------ 锁定

    @Test
    fun `锁定后界面上的明文状态被清空并换回锁屏`() {
        val setup = newViewModel()
        setup.onboardToRecoveryKey()
        setup.onRecoveryKeySavedChange(true)
        setup.onOnboardingNext()
        awaitIdle { setup.phase.value == LockPhase.Unlocked }

        val vm = newViewModel()
        awaitIdle { vm.phase.value == LockPhase.Unlocked }
        vm.onEnterRecoveryMode()

        vm.onLockNow()
        awaitIdle { vm.phase.value is LockPhase.Locked }

        // §7.4：锁定时清空所有已展开的明文 UI 状态。恢复模式也要退出——
        // 留在那一页的表现是"锁上之后直接看到一个恢复密钥输入框"
        assertFalse(vm.uiState.value.unlock.recoveryMode)
        assertEquals(0, vm.uiState.value.unlock.pinLength)
        assertNull(vm.uiState.value.unlock.error)
        assertNull(vm.uiState.value.onboarding.recoveryKeyDisplay)
        assertEquals(PinPolicy.DEFAULT_SLOTS, vm.uiState.value.unlock.pinSlots)
        assertFalse(session.isUnlocked)
    }
}

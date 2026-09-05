package com.lc33.tokenvault.platform

import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.VaultLockedException
import com.lc33.tokenvault.domain.BiometricAvailability
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.UnlockBackoff
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 会话（§14.3 测试 6 的会话那一半）。
 *
 * 用**真的** [FileBootStore] 而不是假的：这一层最要紧的行为都是"boot 里到底写了什么"，
 * 用假 store 就把要测的东西替换掉了。Argon2 参数取下限，否则十几次派生会让套件慢好几秒。
 */
class VaultSessionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: FileBootStore
    private var now = 1_700_000_000_000L
    private lateinit var session: VaultSession

    private val pin = charArrayOf('1', '2', '3', '4', '5', '6')

    /** 让 benchmark 落在"太慢"那一档，于是引导时挑到下限参数，测试跑得快。 */
    private fun slowClock(): () -> Long {
        var t = 0L
        return { t += 900_000_000L; t }
    }

    @Before
    fun setUp() {
        val file = File(temp.newFolder("files"), FileBootStore.FILE_NAME)
        store = FileBootStore(file) { "test-device" }
        session = VaultSession(
            bootStore = store,
            nowEpochMs = { now },
            biometricAvailability = { BiometricAvailability.AVAILABLE },
        )
    }

    /**
     * 走**完整**一条引导：`onboard` 之后还要 `completeOnboarding`。
     *
     * 两步分开是产品行为的一部分（用户得先抄下恢复密钥），所以这个辅助函数代表"引导真的走完了"，
     * 下面那几个用例才是专门测中间那个窗口的。
     */
    private fun onboard(): CharArray {
        val key = session.onboard(pin.copyOf(), slowClock()).recoveryKey
        session.completeOnboarding()
        return key
    }

    // ------------------------------------------------------------------ 阶段

    @Test
    fun `全新安装是引导阶段`() {
        assertEquals(LockPhase.Onboarding, session.refresh())
    }

    @Test
    fun `boot 损坏落 BootCorrupt 而不是引导`() {
        val file = File(temp.root, "files/${FileBootStore.FILE_NAME}")
        file.writeText("")
        val phase = session.refresh()
        assertTrue("$phase", phase is LockPhase.BootCorrupt)
    }

    @Test
    fun `引导完成即解锁`() {
        onboard()
        assertEquals(LockPhase.Unlocked, session.currentPhase())
        assertTrue(session.isUnlocked)
    }

    // ---- 引导的最后一步：恢复密钥还没被确认的那个窗口

    @Test
    fun `恢复密钥还没确认时留在引导阶段，但 DEK 已经可用`() {
        session.onboard(pin.copyOf(), slowClock())
        // 报 Unlocked 的后果很具体：恢复密钥那一页会被跳过去，而那串明文只活在 ViewModel 里
        assertEquals(LockPhase.Onboarding, session.currentPhase())
        // 同时 DEK 必须已经在内存里——"重新轮换一把恢复密钥"这条补救路要用它
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `确认之后 onboarded 才落盘`() {
        session.onboard(pin.copyOf(), slowClock())
        assertFalse((store.read() as BootState.Ok).record.onboarded)
        session.completeOnboarding()
        assertTrue((store.read() as BootState.Ok).record.onboarded)
        assertEquals(LockPhase.Unlocked, session.currentPhase())
    }

    @Test
    fun `引导没走完就杀进程，重启回到引导而不是锁屏`() {
        session.onboard(pin.copyOf(), slowClock())
        // 换一个全新实例 = 杀进程。boot 里已经有 PIN 包裹了，但引导没走完，
        // 所以这里必须是引导而不是"输 PIN 解锁"——用户手上还没有恢复密钥。
        val restarted = VaultSession(bootStore = store, nowEpochMs = { now })
        assertEquals(LockPhase.Onboarding, restarted.refresh())
    }

    @Test
    fun `引导没走完时能轮换出新的恢复密钥，旧的立刻失效`() {
        val first = session.onboard(pin.copyOf(), slowClock()).recoveryKey
        // Activity 被销毁后那串明文没了，但会话还解锁着，所以能换一把新的接着展示
        val second = session.regenerateRecoveryKey()
        assertFalse(first.contentEquals(second))
        session.completeOnboarding()

        session.lock()
        assertEquals(UnlockResult.Success, session.unlockWithRecoveryKey(second.copyOf()))
        session.lock()
        assertTrue(session.unlockWithRecoveryKey(first.copyOf()) is UnlockResult.WrongCredential)
    }

    @Test
    fun `completeOnboarding 在锁定态抛而不是静默写 onboarded`() {
        assertThrows(VaultLockedException::class.java) { session.completeOnboarding() }
        assertEquals(BootState.Missing, store.read())
    }

    @Test
    fun `锁定之后回到 Locked 并带上退避与生物识别信息`() {
        onboard()
        session.lock()
        val phase = session.currentPhase()
        assertTrue("$phase", phase is LockPhase.Locked)
        phase as LockPhase.Locked
        assertTrue("引导时生成了恢复密钥包裹", phase.hasRecoveryKey)
        assertEquals(0, phase.backoff.failedAttempts)
        // 红线 5：引导阶段没开生物识别，所以这里不该报"系统可用"
        assertEquals(BiometricAvailability.NOT_ENABLED_BY_USER, phase.biometric)
    }

    // ------------------------------------------------------------------ 解锁

    @Test
    fun `PIN 正确就解锁`() {
        onboard()
        session.lock()
        assertEquals(UnlockResult.Success, session.unlockWithPin(pin.copyOf()))
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `PIN 错误累加失败计数且不解锁`() {
        onboard()
        session.lock()
        val result = session.unlockWithPin(charArrayOf('9', '9', '9', '9', '9', '9'))
        assertTrue("$result", result is UnlockResult.WrongCredential)
        assertEquals(1, (result as UnlockResult.WrongCredential).backoff.failedAttempts)
        assertFalse(session.isUnlocked)
    }

    @Test
    fun `失败计数落进 boot、杀进程也不清零`() {
        onboard()
        session.lock()
        repeat(3) { session.unlockWithPin(charArrayOf('0', '0', '0', '0', '0', '0')) }

        // 换一个全新的 session 实例 = 模拟杀进程重启
        val restarted = VaultSession(bootStore = store, nowEpochMs = { now })
        val phase = restarted.refresh()
        assertEquals(3, (phase as LockPhase.Locked).backoff.failedAttempts)
    }

    @Test
    fun `第五次错才开始退避`() {
        onboard()
        session.lock()
        val wrong = charArrayOf('0', '0', '0', '0', '0', '0')
        repeat(UnlockBackoff.FREE_ATTEMPTS) {
            val r = session.unlockWithPin(wrong.copyOf()) as UnlockResult.WrongCredential
            assertFalse("前四次不该罚", r.backoff.isActive(now))
        }
        val fifth = session.unlockWithPin(wrong.copyOf()) as UnlockResult.WrongCredential
        assertEquals(30, fifth.backoff.remainingSeconds(now))
    }

    @Test
    fun `退避期间不去尝试、也不累加计数`() {
        onboard()
        session.lock()
        val wrong = charArrayOf('0', '0', '0', '0', '0', '0')
        repeat(UnlockBackoff.FREE_ATTEMPTS + 1) { session.unlockWithPin(wrong.copyOf()) }

        val blocked = session.unlockWithPin(pin.copyOf())
        assertTrue("$blocked", blocked is UnlockResult.InBackoff)
        assertEquals(
            "退避期间的尝试不该再罚一次",
            UnlockBackoff.FREE_ATTEMPTS + 1,
            (blocked as UnlockResult.InBackoff).backoff.failedAttempts,
        )
    }

    @Test
    fun `退避到期后正确的 PIN 能解锁并清零计数`() {
        onboard()
        session.lock()
        val wrong = charArrayOf('0', '0', '0', '0', '0', '0')
        repeat(UnlockBackoff.FREE_ATTEMPTS + 1) { session.unlockWithPin(wrong.copyOf()) }

        now += 31_000
        assertEquals(UnlockResult.Success, session.unlockWithPin(pin.copyOf()))
        session.lock()
        assertEquals(0, (session.currentPhase() as LockPhase.Locked).backoff.failedAttempts)
    }

    @Test
    fun `恢复密钥能解锁`() {
        val recovery = onboard()
        session.lock()
        assertEquals(UnlockResult.Success, session.unlockWithRecoveryKey(recovery.copyOf()))
    }

    @Test
    fun `恢复密钥带空格与短横线也能解锁`() {
        val recovery = onboard()
        session.lock()
        // 用户照着每 4 位一组抄下来，回填时几乎一定带分隔符
        val typed = com.lc33.tokenvault.crypto.RecoveryKey.formatForDisplay(recovery).toCharArray()
        assertEquals(UnlockResult.Success, session.unlockWithRecoveryKey(typed))
    }

    @Test
    fun `格式不对的恢复密钥照样累加计数`() {
        onboard()
        session.lock()
        val result = session.unlockWithRecoveryKey("not-a-key".toCharArray())
        // 不累加的话它就成了一条不受退避约束的探测通道
        assertEquals(1, (result as UnlockResult.WrongCredential).backoff.failedAttempts)
    }

    @Test
    fun `没有 boot 记录时解锁给出 Unavailable 而不是罚用户`() {
        val result = session.unlockWithPin(pin.copyOf())
        assertTrue("$result", result is UnlockResult.Unavailable)
    }

    // ------------------------------------------------------------------ 借用与锁定

    @Test
    fun `锁定态借用子密钥抛 VaultLockedException`() {
        onboard()
        session.lock()
        // 返回 null 会被写成 `?: ""`，然后一张好密钥看起来像空密钥（红线 8 的同一条道理）
        assertThrows(VaultLockedException::class.java) { session.withFieldKey { it.size } }
        assertThrows(VaultLockedException::class.java) { session.withFingerprintKey { it.size } }
    }

    @Test
    fun `两个子密钥不同且解锁前后一致`() {
        onboard()
        val field = session.withFieldKey { it.copyOf() }
        val fingerprint = session.withFingerprintKey { it.copyOf() }
        assertEquals(32, field.size)
        assertNotEquals(field.toList(), fingerprint.toList())

        session.lock()
        session.unlockWithPin(pin.copyOf())
        assertArrayEquals("同一个 DEK 派生必须稳定", field, session.withFieldKey { it.copyOf() })
    }

    @Test
    fun `锁定会把子密钥清零`() {
        onboard()
        // 拿到的是会话持有的那一份引用，锁定后它应当被清零
        val borrowed = session.withFieldKey { it }
        assertTrue(borrowed.any { it != 0.toByte() })
        session.lock()
        assertTrue("红线 6：锁定时置零所有子密钥", borrowed.all { it == 0.toByte() })
    }

    // ------------------------------------------------------------------ 改 PIN 与恢复密钥

    /** 红线 2：改 PIN 只产生一次 boot 写入，业务数据零改动。 */
    @Test
    fun `改 PIN 之后旧字段密文仍可解、且 DEK 没变`() {
        onboard()
        val box = SecretBox()
        val aad = FieldAad.of("api_keys", 1L, "secretEnc")
        val cipher = session.withFieldKey { box.seal("sk-value".encodeToByteArray(), it, aad) }

        session.changePin(charArrayOf('6', '5', '4', '3', '2', '1'))
        session.lock()
        assertEquals(UnlockResult.Success, session.unlockWithPin(charArrayOf('6', '5', '4', '3', '2', '1')))

        val plain = session.withFieldKey { box.open(cipher, it, aad, "test") }
        assertEquals("sk-value", plain.decodeToString())
    }

    @Test
    fun `改 PIN 之后旧 PIN 解不开`() {
        onboard()
        session.changePin(charArrayOf('6', '5', '4', '3', '2', '1'))
        session.lock()
        assertTrue(session.unlockWithPin(pin.copyOf()) is UnlockResult.WrongCredential)
    }

    @Test
    fun `改 PIN 会换盐`() {
        onboard()
        val saltBefore = (store.read() as BootState.Ok).record.pinKdf!!.salt.copyOf()
        session.changePin(charArrayOf('6', '5', '4', '3', '2', '1'))
        val saltAfter = (store.read() as BootState.Ok).record.pinKdf!!.salt
        assertFalse("不换盐的话攻击者手里那份旧密文仍对应同一个盐", saltBefore.contentEquals(saltAfter))
    }

    @Test
    fun `重新生成恢复密钥让旧的立刻失效`() {
        val old = onboard()
        val new = session.regenerateRecoveryKey()
        assertFalse(old.concatToString() == new.concatToString())

        session.lock()
        assertTrue(session.unlockWithRecoveryKey(old.copyOf()) is UnlockResult.WrongCredential)
        // 上一步罚了一次，但还在免费额度内，所以新密钥应当仍能解
        assertEquals(UnlockResult.Success, session.unlockWithRecoveryKey(new.copyOf()))
    }

    @Test
    fun `锁定态不允许改 PIN`() {
        onboard()
        session.lock()
        assertThrows(VaultLockedException::class.java) { session.changePin(pin.copyOf()) }
    }

    @Test
    fun `生物识别那条路交进来的 DEK 长度不对时被拒`() {
        onboard()
        session.lock()
        val result = session.unlockWithDek(ByteArray(16))
        assertTrue("$result", result is UnlockResult.Unavailable)
        assertFalse(session.isUnlocked)
    }
}

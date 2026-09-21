package com.lc33.tokenvault.platform

import com.lc33.tokenvault.crypto.DekEnvelope
import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.KdfParams
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.VaultLockedException
import com.lc33.tokenvault.domain.LockPhase
import com.lc33.tokenvault.domain.UnlockBackoff
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * 用假 store 就把要测的东西替换掉了。PBKDF2 参数取下限，否则十几次派生会让套件慢好几秒。
 */
class VaultSessionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: FileBootStore
    private lateinit var bootFile: File
    private var now = 1_700_000_000_000L

    /**
     * 注入的单调时钟。刻意不用默认的 `::monotonicNanoTime`：退避的剩余时间取"墙钟与单调钟里
     * 走得慢的那个"（防拨表，§7.2），拿真实单调钟的话任何"只动 [now]"的用例都等不到退避结束，
     * 整套测试就成了这台机器跑了多久的函数。
     */
    private var mono = 0L

    private lateinit var session: VaultSession

    private val pin = charArrayOf('1', '2', '3', '4', '5', '6')

    /** 让 benchmark 落在"太慢"那一档，于是引导时挑到下限参数，测试跑得快。 */
    private fun slowClock(): () -> Long {
        var t = 0L
        return { t += 900_000_000L; t }
    }

    @Before
    fun setUp() {
        bootFile = File(temp.newFolder("files"), FileBootStore.FILE_NAME)
        store = FileBootStore(bootFile) { "test-device" }
        session = VaultSession(
            bootStore = store,
            nowEpochMs = { now },
            monotonicNano = { mono },
        )
    }

    /** 走**完整**一条引导：`onboard` 之后还要 `completeOnboarding`。 */
    private fun onboard() {
        session.onboard(pin.copyOf(), slowClock())
        session.completeOnboarding()
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
        // 所以这里必须是引导而不是"输 PIN 解锁"。
        val restarted = VaultSession(bootStore = store, nowEpochMs = { now })
        assertEquals(LockPhase.Onboarding, restarted.refresh())
    }

    @Test
    fun `completeOnboarding 在锁定态抛而不是静默写 onboarded`() {
        assertThrows(VaultLockedException::class.java) { session.completeOnboarding() }
        assertEquals(BootState.Missing, store.read())
    }

    @Test
    fun `锁定之后回到 Locked 并带上退避信息`() {
        onboard()
        session.lock()
        val phase = session.currentPhase()
        assertTrue("$phase", phase is LockPhase.Locked)
        phase as LockPhase.Locked
        assertEquals(0, phase.backoff.failedAttempts)
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

        // 退避到期 = **两个时钟都**越过 deadline。只把墙钟拨过去的那条路有专门一例盯着
        // （`把系统时间往前调不能提前结束退避`），这里要的是"真的等满了"。
        now += 31_000
        mono += 31_000_000_000L
        assertEquals(UnlockResult.Success, session.unlockWithPin(pin.copyOf()))
        session.lock()
        assertEquals(0, (session.currentPhase() as LockPhase.Locked).backoff.failedAttempts)
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

    // ------------------------------------------------------------------ 改 PIN

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
    fun `锁定态不允许改 PIN`() {
        onboard()
        session.lock()
        assertThrows(VaultLockedException::class.java) { session.changePin(pin.copyOf()) }
    }

    // ------------------------------------------------------------------ 生物识别那条路（§7.3）

    @Test
    fun `用生物识别取回的 DEK 能解锁`() {
        onboard()
        // 平台层就是这么拿到 DEK 的：锁内短暂借用、拷一份出来交给 Keystore / Keychain。
        val dek = session.withDek { it.copyOf() }
        session.lock()
        assertFalse(session.isUnlocked)

        assertEquals(UnlockResult.Success, session.unlockWithDek(dek))
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `长度不对的 DEK 不解锁且被擦掉`() {
        onboard()
        session.lock()
        val bad = ByteArray(16) { 7 }
        assertTrue(session.unlockWithDek(bad) is UnlockResult.Unavailable)
        assertFalse(session.isUnlocked)
        assertArrayEquals(ByteArray(16), bad)
    }

    @Test
    fun `锁定态取 DEK 抛 VaultLockedException`() {
        onboard()
        session.lock()
        assertThrows(VaultLockedException::class.java) { session.withDek { it } }
    }

    @Test
    fun `平台给回另一把 DEK 时不算解锁成功`() {
        // 平台（Keystore / Keychain）交回的字节没有任何认证 tag 可验，所以"长度对"不等于
        // "是这个库的那把"。不校验的表现是"解得开但全是乱码"，而不是"解不开"。
        onboard()
        session.lock()
        val other = ByteArray(DekEnvelope.DEK_BYTES) { 3 }
        assertTrue(session.unlockWithDek(other) is UnlockResult.Unavailable)
        assertFalse(session.isUnlocked)
        assertArrayEquals("失败那一份归这里擦", ByteArray(DekEnvelope.DEK_BYTES), other)
    }

    @Test
    fun `旧记录缺校验密文时平台那条路照旧放行`() {
        // 拒绝等于把升级用户已经在用的生物识别入口直接打死；缺的那一份在下一次 PIN 解锁时补
        // （见 `PIN 解锁会补上旧记录缺的校验密文`），改 PIN 与重新引导那两条路也会顺手补。
        onboard()
        val dek = session.withDek { it.copyOf() }
        store.update { it.copy(dekCheck = null) }
        session.lock()
        assertEquals(UnlockResult.Success, session.unlockWithDek(dek))
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `引导写下校验密文，改 PIN 会补上旧记录缺的那一份`() {
        onboard()
        assertNotNull((store.read() as BootState.Ok).record.dekCheck)

        val dek = session.withDek { it.copyOf() }
        store.update { it.copy(dekCheck = null) }
        session.changePin(charArrayOf('6', '5', '4', '3', '2', '1'))
        assertNotNull(
            "改 PIN 是升级后最常发生的一次 boot 重写，用它补齐比另开一个迁移入口少一处能写错的地方",
            (store.read() as BootState.Ok).record.dekCheck,
        )
        // DEK 没换，所以补上的那一份仍然认得原来那把
        session.lock()
        assertEquals(UnlockResult.Success, session.unlockWithDek(dek))
    }

    @Test
    fun `PIN 解锁会补上旧记录缺的校验密文`() {
        // 只在引导 / 改 PIN 时补是不够的：升级设备上大多数人既没重装也没改 PIN，身份校验
        // 就在他们身上一直空转。而 PIN 解锁是**唯一一次**手里的 DEK 已被 PIN 槽 AEAD tag
        // 认证过的时机——生物识别交回的字节正是待验对象，不能拿它盖章。
        onboard()
        val dek = session.withDek { it.copyOf() }
        store.update { it.copy(dekCheck = null) }
        session.lock()

        assertEquals(UnlockResult.Success, session.unlockWithPin(pin.copyOf()))
        assertNotNull(
            "补写挂在解锁后那趟已有的 boot 重写里，不额外多一次写",
            (store.read() as BootState.Ok).record.dekCheck,
        )

        // 补完之后，冒牌 DEK 这条路就走不通了——这才是补它的意义
        session.lock()
        assertTrue(session.unlockWithDek(ByteArray(DekEnvelope.DEK_BYTES) { 3 }) is UnlockResult.Unavailable)
        assertFalse(session.isUnlocked)
        // 而原来那把照旧能用，补写没有把用户锁在外面
        session.lock()
        assertEquals(UnlockResult.Success, session.unlockWithDek(dek))
    }

    // ------------------------------------------------------------------ 结构性失败要与凭据失败分开（红线 8）

    @Test
    fun `KDF 参数超出封顶时判为不可用而不是崩在锁屏`() {
        onboard()
        session.lock()
        val good = (store.read() as BootState.Ok).record
        store.write(
            good.copy(pinKdf = KdfParams(iterations = KdfParams.MAX_ITERATIONS + 1, salt = ByteArray(16) { 1 })),
        )

        val result = session.unlockWithPin(pin.copyOf())
        assertTrue("$result", result is UnlockResult.Unavailable)
        val phase = session.currentPhase()
        assertTrue("要停在恢复页而不是白屏崩溃：$phase", phase is LockPhase.BootCorrupt)
        assertEquals("参数不认与 PIN 猜错是两件事，一次都不该罚", 0, pinFailCount())
    }

    @Test
    fun `封套版本不认识时给出不可用并把阶段钉住`() {
        onboard()
        session.lock()
        val good = (store.read() as BootState.Ok).record
        val future = good.dekWrappedByPin!!.copyOf().also { it[0] = 9 }
        store.write(good.copy(dekWrappedByPin = future))

        val result = session.unlockWithPin(pin.copyOf())
        assertTrue("$result", result is UnlockResult.Unavailable)
        val phase = session.currentPhase()
        assertTrue("$phase", phase is LockPhase.BootCorrupt)
        assertEquals(0, pinFailCount())
    }

    @Test
    fun `换回一份读得通的 boot 之后 refresh 会离开恢复页`() {
        // 钉住这一判定是为了不让界面回到"请输入 PIN"上打转；但用户真的换了 boot（从备份恢复、
        // 或清空重来）之后必须能出去，否则恢复页成了死胡同。refresh 是唯一那个解除入口。
        onboard()
        session.lock()
        val good = (store.read() as BootState.Ok).record
        store.write(good.copy(dekWrappedByPin = good.dekWrappedByPin!!.copyOf().also { it[0] = 9 }))
        assertTrue(session.unlockWithPin(pin.copyOf()) is UnlockResult.Unavailable)

        store.write(good)
        assertFalse(session.refresh() is LockPhase.BootCorrupt)
        assertEquals(UnlockResult.Success, session.unlockWithPin(pin.copyOf()))
    }

    @Test
    fun `PIN 猜错不把会话钉在恢复页`() {
        onboard()
        session.lock()
        assertTrue(session.unlockWithPin(charArrayOf('9', '9', '9', '9', '9', '9')) is UnlockResult.WrongCredential)
        val phase = session.currentPhase()
        assertTrue("猜错就该继续让他猜：$phase", phase is LockPhase.Locked)
    }

    @Test
    fun `记不下失败计数时给出不可用而不是把异常抛到锁屏页外`() {
        // LockViewModel 那一边只有 try/finally（擦 PIN），没有 catch：解锁路径上任何异常
        // 都是白屏崩溃。写不进去（磁盘满、rename 失败）时文件本身没坏，所以也不许钉恢复页。
        onboard()
        session.lock()
        store.renameOverride = { _, _ -> false }
        val result = session.unlockWithPin(charArrayOf('9', '9', '9', '9', '9', '9'))
        assertTrue("$result", result is UnlockResult.Unavailable)
        assertFalse(session.isUnlocked)
        assertTrue(session.currentPhase() is LockPhase.Locked)
    }

    // ------------------------------------------------------------------ 退避的防拨表（§7.2）

    @Test
    fun `把系统时间往前调不能提前结束退避`() {
        onboard()
        session.lock()
        var nano = 0L
        val s = VaultSession(bootStore = store, nowEpochMs = { now }, monotonicNano = { nano })
        val wrong = charArrayOf('0', '0', '0', '0', '0', '0')
        repeat(UnlockBackoff.FREE_ATTEMPTS + 1) { s.unlockWithPin(wrong.copyOf()) }

        // 墙钟调快一分钟（越过 deadline），单调时钟却只走了一秒
        now += 60_000
        nano += 1_000_000_000L
        val blocked = s.unlockWithPin(pin.copyOf())
        assertTrue("拨表买不到一次解锁：$blocked", blocked is UnlockResult.InBackoff)

        // 真等满了才放行
        nano += 31_000_000_000L
        assertEquals(UnlockResult.Success, s.unlockWithPin(pin.copyOf()))
    }

    // ------------------------------------------------------------------ 引导清理（§7.5）

    @Test
    fun `重新引导会清掉上一轮的生物识别状态`() {
        onboard()
        store.update { it.copy(biometricEnabled = true, dekWrappedByBiometric = ByteArray(40) { 5 }) }

        // 换一个实例 = 用户清空重来之后重新引导
        VaultSession(bootStore = store, nowEpochMs = { now }).onboard(pin.copyOf(), slowClock())
        val record = (store.read() as BootState.Ok).record
        assertFalse("DEK 已经换了一把，旧包裹通向的是解不开的数据", record.biometricEnabled)
        assertNull(record.dekWrappedByBiometric)
    }

    @Test
    fun `没绑剪贴板时锁定照常进行`() {
        onboard()
        session.lock()
        assertTrue(session.currentPhase() is LockPhase.Locked)
    }

    /**
     * boot 里落盘的失败计数。
     *
     * **刻意不走 [store.read]**：有几例要故意把封套首字节改坏（那正是要测的输入），而存储层
     * 对那种文件一律判 `Corrupt`（读到不认的封套就在这一层拦掉，不等解锁去抛）——那时
     * `read() as BootState.Ok` 会直接 ClassCastException，把"有没有罚用户"这个结论淹掉。
     * "这一笔有没有累加计数"与"这份文件读不读得通"是两件事，所以这里按原样解一次文本。
     */
    private fun pinFailCount(): Int = kotlinx.serialization.json.Json.Default
        .decodeFromString(BootRecord.serializer(), bootFile.readText()).pinFailCount
}

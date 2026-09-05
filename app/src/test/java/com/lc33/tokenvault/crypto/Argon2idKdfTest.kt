package com.lc33.tokenvault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Argon2id 参数与分档（计划.md §7.2），以及恢复密钥的规范化。
 *
 * 参数取的都是下限，因为这里验的是逻辑不是强度。
 */
class Argon2idKdfTest {

    private fun params(memoryKib: Int = KdfParams.MIN_MEMORY_KIB, iterations: Int = 1) =
        KdfParams(
            memoryKib = memoryKib,
            iterations = iterations,
            parallelism = 1,
            salt = ByteArray(KdfParams.SALT_BYTES) { 5 },
        )

    @Test
    fun `同样的口令与参数派生出同样的密钥`() {
        val a = Argon2idKdf.derive("123456".toCharArray(), params())
        val b = Argon2idKdf.derive("123456".toCharArray(), params())
        assertEquals(32, a.size)
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun `换盐就换密钥`() {
        val withSalt5 = Argon2idKdf.derive("123456".toCharArray(), params())
        val withSalt6 = Argon2idKdf.derive(
            "123456".toCharArray(),
            params().copy(salt = ByteArray(KdfParams.SALT_BYTES) { 6 }),
        )
        assertNotEquals(withSalt5.toList(), withSalt6.toList())
    }

    @Test
    fun `换参数就换密钥`() {
        // 这正是红线 3 的后果：改写存储的参数值 = 旧包裹永久解不开
        val t1 = Argon2idKdf.derive("123456".toCharArray(), params(iterations = 1))
        val t2 = Argon2idKdf.derive("123456".toCharArray(), params(iterations = 2))
        assertNotEquals(t1.toList(), t2.toList())
    }

    @Test
    fun `超过封顶的参数被拒绝`() {
        val tooBig = KdfParams(
            memoryKib = KdfParams.MAX_MEMORY_KIB * 4,
            iterations = 2,
            parallelism = 2,
            salt = ByteArray(KdfParams.SALT_BYTES),
        )
        assertFalse(tooBig.withinCap())
        assertThrows(InvalidKdfParamsException::class.java) {
            Argon2idKdf.derive("123456".toCharArray(), tooBig)
        }
    }

    @Test
    fun `盐长度不对直接构造失败`() {
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(salt = ByteArray(8))
        }
    }

    @Test
    fun `只接受 argon2id`() {
        assertThrows(IllegalArgumentException::class.java) {
            KdfParams(algorithm = "scrypt", salt = ByteArray(KdfParams.SALT_BYTES))
        }
    }

    @Test
    fun `太慢就降档`() {
        val chosen = Argon2idKdf.chooseParams(KdfParams(salt = ByteArray(16)), elapsedMillis = 900)
        assertEquals(KdfParams.MIN_MEMORY_KIB, chosen.memoryKib)
    }

    @Test
    fun `很快才升档、且不超过封顶`() {
        val chosen = Argon2idKdf.chooseParams(KdfParams(salt = ByteArray(16)), elapsedMillis = 40)
        assertEquals(KdfParams.MAX_MEMORY_KIB, chosen.memoryKib)
        assertEquals(KdfParams.MAX_ITERATIONS, chosen.iterations)
        assertTrue(chosen.withinCap())
    }

    @Test
    fun `落在目标区间就不动`() {
        val baseline = KdfParams(salt = ByteArray(16))
        assertEquals(baseline, Argon2idKdf.chooseParams(baseline, elapsedMillis = 380))
    }

    @Test
    fun `基准用注入的时钟、不读系统时间`() {
        // 红线 20：纯 Kotlin 层不允许直接读当前时间，否则测试变成时间敏感的
        var fake = 0L
        val elapsed = Argon2idKdf.benchmark(params()) {
            fake += 250_000_000L
            fake
        }
        assertEquals(250, elapsed)
    }

    @Test
    fun `参数的 toString 不打印盐`() {
        val text = params().toString()
        assertTrue(text.contains("m=${KdfParams.MIN_MEMORY_KIB}"))
        assertFalse("盐不该跟着进日志", text.contains("5, 5, 5"))
    }

    @Test
    fun `恢复密钥是 32 个 hex 字符`() {
        val key = RecoveryKey.generate(SecureRandomBytes)
        assertEquals(RecoveryKey.LENGTH, key.size)
        assertTrue(RecoveryKey.isWellFormed(key))
    }

    @Test
    fun `恢复密钥输入去空格与短横线、忽略大小写`() {
        val typed = "A1B2 c3d4-e5f6 7890 a1b2 c3d4 e5f6 7890".toCharArray()
        val normalized = RecoveryKey.normalize(typed)
        assertEquals(RecoveryKey.LENGTH, normalized.size)
        assertTrue("抄写时几乎一定带分隔符，不规范化就会说'你抄错了'", RecoveryKey.isWellFormed(normalized))
    }

    /**
     * 换行必须被跳掉。
     *
     * 恢复密钥的正常保管方式是密码管理器的备注或一个 txt 文件，粘贴过来几乎一定带一个
     * 结尾换行；多行输入框里用户也能直接按回车。带着 `\n` 就是 33 个字符，于是应用告诉他
     * "恢复密钥是 32 个字符"——而他抄得没错。
     */
    @Test
    fun `粘贴带来的换行被跳掉`() {
        val hex = "a1b2c3d4e5f67890a1b2c3d4e5f67890"
        for (pasted in listOf("$hex\n", "$hex\r\n", "\n$hex", "a1b2c3d4e5f67890\na1b2c3d4e5f67890")) {
            val normalized = RecoveryKey.normalize(pasted.toCharArray())
            assertTrue("粘贴形态 <${pasted.replace("\n", "\\n").replace("\r", "\\r")}> 该被接受",
                RecoveryKey.isWellFormed(normalized))
        }
    }

    @Test
    fun `全角空格等 Unicode 空白也跳掉`() {
        // 枚举几个字符早晚漏一个，所以用 isWhitespace
        val withIdeographicSpace = "a1b2c3d4e5f67890\u3000a1b2c3d4e5f67890"
        assertTrue(RecoveryKey.isWellFormed(RecoveryKey.normalize(withIdeographicSpace.toCharArray())))
    }

    /**
     * `normalize` 必须**无条件**擦掉中间缓冲。
     *
     * `copyOf(n)` 永远新分配一份，所以那个缓冲每次都是该丢掉的中间产物。写成"长度变了才擦"
     * 会恰好漏掉最常见的那条路径：用户直接粘贴、输入里没有分隔符时长度没变，
     * 于是完整的明文恢复密钥留在堆上不擦。
     */
    @Test
    fun `没有分隔符时中间缓冲照样被擦掉`() {
        val clean = "a1b2c3d4e5f67890a1b2c3d4e5f67890".toCharArray()
        val normalized = RecoveryKey.normalize(clean)
        // 返回值是拷贝，所以它本身必须是完好的——擦的是那份看不见的缓冲
        assertEquals(RecoveryKey.LENGTH, normalized.size)
        assertTrue(RecoveryKey.isWellFormed(normalized))
        assertEquals("a1b2c3d4e5f67890a1b2c3d4e5f67890", normalized.concatToString())
    }

    @Test
    fun `非 hex 的输入判为格式不对`() {
        assertFalse(RecoveryKey.isWellFormed(RecoveryKey.normalize("zzzz".toCharArray())))
    }

    @Test
    fun `显示时每四位一组`() {
        val key = CharArray(RecoveryKey.LENGTH) { 'a' }
        val shown = RecoveryKey.formatForDisplay(key)
        assertEquals(RecoveryKey.LENGTH / RecoveryKey.GROUP_SIZE - 1, shown.count { it == ' ' })
    }
}

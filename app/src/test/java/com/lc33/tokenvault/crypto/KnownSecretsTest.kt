package com.lc33.tokenvault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试：会话级已知明文清单（红线 32 第一道的来源）。
 *
 * 只验证三件事，它们对应这个类的三条契约：
 * 1. [KnownSecrets.add] 存的是副本，调用方擦自己那份不影响清单。
 * 2. [KnownSecrets.snapshot] 每次现读，反映"登记至今"的完整集合，且去重。
 * 3. [KnownSecrets.clear] 清零——锁定后脱敏器不该再记着上一把密钥的明文。
 */
class KnownSecretsTest {

    @Test
    fun `add 存副本、调用方擦自己那份不影响清单`() {
        val secrets = KnownSecrets()
        val plain = "ExampleSecretValue".toCharArray()
        secrets.add(plain)
        plain.zeroize()

        assertEquals(listOf("ExampleSecretValue"), secrets.snapshot())
    }

    @Test
    fun `snapshot 现读、反映多次 add 的并集`() {
        val secrets = KnownSecrets()
        secrets.add("firstSecretValue1".toCharArray())
        secrets.add("secondSecretValue2".toCharArray())

        val snap = secrets.snapshot()
        assertTrue(snap.contains("firstSecretValue1"))
        assertTrue(snap.contains("secondSecretValue2"))
        assertEquals(2, snap.size)
    }

    @Test
    fun `同一明文重复 add 只留一份`() {
        val secrets = KnownSecrets()
        secrets.add("SameSecretValueX".toCharArray())
        secrets.add("SameSecretValueX".toCharArray())

        assertEquals(1, secrets.snapshot().size)
    }

    @Test
    fun `空串不入清单`() {
        val secrets = KnownSecrets()
        secrets.add(charArrayOf())
        assertEquals(0, secrets.snapshot().size)
    }

    @Test
    fun `clear 后 snapshot 为空`() {
        val secrets = KnownSecrets()
        secrets.add("SomeSecretValue3".toCharArray())
        secrets.clear()

        assertEquals(emptyList<String>(), secrets.snapshot())
    }

    @Test
    fun `clear 后可重新 add（新一轮会话）`() {
        val secrets = KnownSecrets()
        secrets.add("OldSessionSecret".toCharArray())
        secrets.clear()
        secrets.add("NewSessionSecret".toCharArray())

        assertEquals(listOf("NewSessionSecret"), secrets.snapshot())
    }
}

package com.lc33.tokenvault.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遮蔽串（§6.2）。
 *
 * 这几条都是"写错了不会报错"的：遮蔽逻辑错了照样显示一串东西，只有对着长度算才看得出来
 * 它到底遮住了什么。
 */
class SecretMaskTest {

    @Test
    fun `够长的密钥保留头 7 尾 4`() {
        val secret = "abcdefghijklmnopqrstuvwxyz".toCharArray()
        assertEquals("abcdefg…wxyz", SecretMask.of(secret))
    }

    @Test
    fun `刚好到阈值就按头尾遮，差一个字符就退到短值规则`() {
        // 15 = 7 + 4 + 4，恰好藏住 4 个
        val exact = CharArray(SecretMask.PREFIX + SecretMask.SUFFIX + SecretMask.MIN_HIDDEN) { 'x' }
        assertEquals("xxxxxxx…xxxx", SecretMask.of(exact))

        val oneLess = CharArray(exact.size - 1) { 'x' }
        assertEquals("xx…xx", SecretMask.of(oneLess))
    }

    @Test
    fun `太短的值一个字符都不给`() {
        // 7 个字符按"头 7 尾 4"遮完就是原样显示，那等于没遮
        assertEquals(SecretMask.ELLIPSIS, SecretMask.of("short12".toCharArray()))
        assertEquals(SecretMask.ELLIPSIS, SecretMask.of(CharArray(0)))
    }

    @Test
    fun `遮蔽串里藏住的字符一个都不出现`() {
        val secret = "0123456789abcdefghij".toCharArray()
        val masked = SecretMask.of(secret)
        val hidden = String(secret, SecretMask.PREFIX, secret.size - SecretMask.PREFIX - SecretMask.SUFFIX)
        assertTrue("遮蔽串不该包含被藏起来那一段", !masked.contains(hidden))
    }
}

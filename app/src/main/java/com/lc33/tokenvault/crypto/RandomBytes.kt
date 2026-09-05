package com.lc33.tokenvault.crypto

/**
 * 随机源。
 *
 * 做成接口是为了**让测试能给定 IV**——不然"同一份明文两次加密必须得到不同密文"
 * 这类断言只能靠概率，而"IV 复用"恰恰是 GCM 最致命的误用，值得能被确定性地测。
 * 生产实现是 [SecureRandomBytes]。
 */
fun interface RandomBytes {
    fun nextBytes(size: Int): ByteArray
}

/**
 * `java.security.SecureRandom`。
 *
 * 不带种子构造，也**不调用 `setSeed`**：在 Android 上那会把系统熵源替换掉。
 */
object SecureRandomBytes : RandomBytes {
    private val random = java.security.SecureRandom()

    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

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
 * 密码学安全的随机字节源。
 *
 * 平台各自提供加密安全随机（Android/JVM 用 `SecureRandom`，iOS 用系统 CSPRNG）。
 * 用 `expect object` 声明，`actual` 在 androidMain / jvmMain / iosMain 里给实现。
 *
 * 不带种子构造，也**不调用** Android 上 `SecureRandom.setSeed`（那会替换系统熵源）。
 */
expect object SecureRandomBytes : RandomBytes {
    override fun nextBytes(size: Int): ByteArray
}

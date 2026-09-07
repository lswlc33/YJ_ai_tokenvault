package com.lc33.tokenvault.crypto

import java.security.SecureRandom

/**
 * Android 端的加密安全随机：`java.security.SecureRandom`。
 *
 * 不带种子构造，也**不调用 `setSeed`**：在 Android 上那会把系统熵源替换掉。
 */
actual object SecureRandomBytes : RandomBytes {
    private val random = SecureRandom()

    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

package com.lc33.tokenvault.crypto

import java.security.SecureRandom

/**
 * JVM 端的加密安全随机：`java.security.SecureRandom`（单测用）。
 */
actual object SecureRandomBytes : RandomBytes {
    private val random = SecureRandom()

    actual override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

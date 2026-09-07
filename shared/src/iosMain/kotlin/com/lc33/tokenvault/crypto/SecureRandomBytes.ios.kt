package com.lc33.tokenvault.crypto

import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS 端的加密安全随机：`SecRandomCopyBytes(kSecRandomDefault)`（系统 CSPRNG）。
 */
actual object SecureRandomBytes : RandomBytes {
    override fun nextBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        val status = SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), bytes.refTo(0))
        // 0 表示成功（errSecSuccess）；失败时抛异常，绝不返回弱随机。
        check(status == 0) { "SecRandomCopyBytes failed with status $status" }
        return bytes
    }
}

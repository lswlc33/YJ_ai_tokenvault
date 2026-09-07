package com.lc33.tokenvault.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.apple.Apple

/**
 * iOS 端 provider：Apple（CryptoKit / CommonCrypto 原生实现）。
 *
 * 阶段4 iOS 构建时才真正编译到；这里先写好 actual，让 commonMain 能声明 expect。
 */
actual object CryptoProvider {
    actual val provider: CryptographyProvider = CryptographyProvider.Apple
}

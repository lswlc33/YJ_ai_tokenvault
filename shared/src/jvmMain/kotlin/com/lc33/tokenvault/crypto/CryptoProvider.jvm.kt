package com.lc33.tokenvault.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

/**
 * JVM 端 provider：JDK（JCA 实现，单测用）。
 */
actual object CryptoProvider {
    actual val provider: CryptographyProvider = CryptographyProvider.JDK
}

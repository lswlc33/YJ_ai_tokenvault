package com.lc33.tokenvault.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

/**
 * Android 端 provider：JDK（JCA 实现）。
 *
 * 显式用 `JDK` 而不是 `CryptographyProvider.Default`：后者依赖 ServiceLoader 自动发现，
 * 在 Android 的 R8 混淆后（release 包）需要额外的 keep 规则才可靠；显式引用
 * `CryptographyProvider.JDK` 是编译期强引用的，不依赖运行时的服务发现。
 */
actual object CryptoProvider {
    actual val provider: CryptographyProvider = CryptographyProvider.JDK
}

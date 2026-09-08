package com.lc33.tokenvault.crypto

import dev.whyoleg.cryptography.CryptographyAlgorithm
import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyProviderApi
import dev.whyoleg.cryptography.providers.apple.Apple
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit

/**
 * iOS 端 provider：**Apple 与 CryptoKit 两个一起用，缺一个就启动崩。**
 *
 * 0.5.0 里它们的能力是互补的（逐个核过源码）：
 *
 * | 算法 | CryptoKit | Apple |
 * | --- | --- | --- |
 * | AES.GCM | 有 | **没有** |
 * | PBKDF2 | **没有** | 有 |
 * | HKDF / HMAC / SHA256 | 有 | 有 |
 *
 * 所以只挂 Apple 时，`SecretBox` 的 `provider.get(AES.GCM)` 抛
 * `IllegalStateException: Algorithm not found`，`VaultSession` 建不出来——iOS 打开即闪退
 * 就是它（2026-09-08 由 `IosDiGraphSmokeTest` 在模拟器上抓到）。
 *
 * **为什么自己组合而不用 `CryptographyProvider.Default`**：Default 走 `CryptographySystem`
 * 的注册表，靠两个 provider 各自的 `@EagerInitialization` initHook 注册成 CompositeProvider。
 * 那条路要求两个 provider 都被链接进二进制——而 Kotlin/Native 会按可达性做死代码消除，
 * 没人引用的库代码会被整块裁掉，注册也就跟着没了。这里显式引用两个 provider 对象，
 * 可达性由我们自己保证，不依赖注册时机。
 */
@OptIn(CryptographyProviderApi::class)
private object IosCryptographyProvider : CryptographyProvider() {
    override val name: String get() = "iOS(CryptoKit+Apple)"

    override fun <A : CryptographyAlgorithm> getOrNull(identifier: CryptographyAlgorithmId<A>): A? =
        CryptographyProvider.CryptoKit.getOrNull(identifier)
            ?: CryptographyProvider.Apple.getOrNull(identifier)
}

actual object CryptoProvider {
    actual val provider: CryptographyProvider = IosCryptographyProvider
}

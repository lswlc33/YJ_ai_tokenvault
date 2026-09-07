package com.lc33.tokenvault.crypto

import dev.whyoleg.cryptography.CryptographyProvider

/**
 * cryptography-kotlin 的 provider 入口（阶段1 迁移）。
 *
 * 项目里的所有密码学原语（AES-GCM / PBKDF2 / HKDF / HMAC）都走这一个 provider，
 * 而不是各自去拿底层 provider。这样做的收益有两个：
 *
 * 1. **唯一入口**：换 provider 只需要改各平台的 actual 实现（Android/JVM 用 JDK，
 *    iOS 用 CryptoKit），`SecretBox` / `Hkdf` 等下游代码零改动——它们不该知道
 *    "底层是哪个 provider"。
 * 2. **JVM 单测可替换**：crypto 层的测试可以直接注入一个测试 provider，而不必依赖
 *    ServiceLoader 在测试环境里把 provider 自动注册好。
 *
 * 为什么用 `expect object`：provider 是平台相关的——Android/JVM 走 JCA（JDK provider），
 * iOS 走 CryptoKit（Apple provider）。这是阶段2 KMP 化的标准处理。
 */
expect object CryptoProvider {
    /** 平台生产实现。 */
    val provider: CryptographyProvider
}

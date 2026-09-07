package com.lc33.tokenvault.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

/**
 * cryptography-kotlin 的 provider 入口（阶段1 迁移）。
 *
 * 项目里的所有密码学原语（AES-GCM / PBKDF2 / HKDF / HMAC）都走这一个 provider，
 * 而不是各自去 `CryptographyProvider.JDK`。这样做的收益有两个：
 *
 * 1. **唯一入口**：将来换 provider（比如阶段2 KMP 化时 Android 用 JDK、iOS 用 CryptoKit）
 *    只需要改这一个文件，`SecretBox` / `Hkdf` 等下游代码零改动——它们不该知道
 *    "底层是哪个 provider"。
 * 2. **JVM 单测可替换**：crypto 层的测试可以直接注入一个测试 provider，而不必依赖
 *    ServiceLoader 在测试环境里把 JDK provider 自动注册好。
 *
 * 显式用 `JDK` 而不是 `CryptographyProvider.Default`：后者依赖 ServiceLoader 自动发现，
 * 在 Android 的 R8 混淆后（release 包）需要额外的 keep 规则才可靠；显式引用
 * `CryptographyProvider.JDK` 是编译期强引用的，不依赖运行时的服务发现。
 */
object CryptoProvider {

    /** 生产实现：JDK provider（JCA 实现，Android 与 JVM 单测行为一致）。 */
    val provider: CryptographyProvider = CryptographyProvider.JDK}

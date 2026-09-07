package com.lc33.tokenvault.backup

import kotlinx.serialization.Serializable
import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer

/**
 * 备份包的 payload（§12.1）。纯 Kotlin，零 Android 依赖。
 *
 * payload = gzip(JSON{ groups, providers, apiKeys, providerAccounts, models, clientProfiles, appSettings })
 *
 * 四条硬规矩（红线 27、28 与 §12.1）：
 *
 * 1. **跨表引用一律用自然键**：`provider.groupId` 导出成分组名，`provider.clientProfileId`
 *    导出成 `builtinKey`（内置）或预设名（自定义）。新设备上自增 ID 必然不同，直接搬 ID
 *    会指向错误行或悬空。
 * 2. **密钥 / 账号密码 / 用户名是明文**（整包已加密），恢复端用自己的 DEK 重新加密。
 *    指纹**不带**（`api_keys.fingerprint`、`provider_accounts.usernameFp` 是
 *    `HKDF(DEK,"fp")` 派生的，跨设备 DEK 不同则值不同），导入端重算。
 * 3. **不搬运探测结果**：`health` / `lastOutcome` / `checkedAt` / `okAt` / `probeState` /
 *    `probedAt` 导出时就不写，导入后一律未探测。
 * 4. 客户端预设只备份 `userEdited = true` 或自定义条目；未改动的内置预设由新设备
 *    `ProfileSeeder` 生成。
 *
 * 所有枚举字段存 wireName 字符串（`domain/Kinds.kt` 的统一立场：枚举改名不改数据含义）。
 *
 * **阶段2 迁移**：gzip 从 `java.util.zip` 换成 okio 的 [GzipSink] / [GzipSource]（全 KMP，
 * native 走 zlib）。两者都产标准 gzip（RFC 1952），字节与旧实现互相兼容。
 */

// ------------------------------------------------------------------ 条目 DTO

/** 分组。跨表引用按 name。 */
@Serializable
data class BackupGroup(
    val name: String,
    val sortOrder: Int = 0,
)

/**
 * 供应商。
 *
 * [groupId] 是分组名，[clientProfileKey] 是 `builtinKey`（内置）或预设名（自定义）。
 * 都不搬探测结果、不搬余额（余额是瞬时值，且 §12.1 明确不搬）。
 */
@Serializable
data class BackupProvider(
    val name: String,
    val note: String? = null,
    val websiteUrl: String? = null,
    val apiBaseUrl: String,
    val apiRoot: String,
    val apiVersion: String = "v1",
    val supportedProtocols: List<String> = emptyList(),
    val pathOverrides: Map<String, String> = emptyMap(),
    val authStyle: String = "auto",
    val allowInsecure: Boolean = false,
    val clientProfileKey: String? = null,
    val groupName: String? = null,
    val color: Int? = null,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val balanceKind: String = "none",
    val balanceBaseUrl: String? = null,
    val balanceUserId: String? = null,

    /** NewAPI 那类适配器的独立访问令牌**明文**。 */
    val balanceToken: String? = null,
    val balanceConfig: String = "{}",
    val quotaPerUnit: Double? = null,
    val quotaCalibrated: Boolean = false,
    val timeoutSeconds: Int? = null,

    // 探测开关每家一份（红线 36），这属于配置不是探测结果，要搬。
    val probeEnabled: Boolean = true,
    val probeReachability: Boolean = true,
    val probeKeyValidity: Boolean = true,
    val probeBalance: Boolean = true,
    val probeModels: Boolean = false,
)

/** API 密钥。明文。不搬 health / lastOutcome / checkedAt / okAt。 */
@Serializable
data class BackupApiKey(
    /** 所属供应商，按 name + apiRoot 定位（与合并恢复的去重键一致）。 */
    val providerName: String,
    val providerApiRoot: String,
    val label: String = "",
    val secret: String,
    val isDefault: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)

/** 平台账号。用户名与密码**明文**。 */
@Serializable
data class BackupAccount(
    val providerName: String,
    val providerApiRoot: String,
    val label: String = "",
    val username: String? = null,
    val password: String? = null,
    val loginUrl: String? = null,
    val note: String? = null,
    val sortOrder: Int = 0,
)

/** 模型。不搬 probeState / lastOutcome / probedAt。 */
@Serializable
data class BackupModel(
    val providerName: String,
    val providerApiRoot: String,
    val modelId: String,
    val protocol: String,
    val displayName: String? = null,
    val source: String = "manual",
    val discoveredVia: String? = null,
    val enabled: Boolean = true,
    val favorite: Boolean = false,
    val needsReview: Boolean = false,
    val catalogKey: String? = null,
    val sortOrder: Int = 0,
)

/** 客户端预设。只有 userEdited 或自定义的才进包。 */
@Serializable
data class BackupProfile(
    val name: String,
    val builtinKey: String? = null,
    val userAgent: String,
    val headers: List<List<String>> = emptyList(),
    val bodyPatch: String = "{}",
    val protocols: List<String> = emptyList(),
    val verified: Boolean = false,
    val builtinRev: Int = 0,
    val userEdited: Boolean = false,
    val sortOrder: Int = 0,
)

/** 设置白名单（§12.1：themeMode / localeTag 显式包含，权威在 boot）。 */
@Serializable
data class BackupSetting(
    val key: String,
    val value: String? = null,
)

/** 备份包的完整 payload。 */
@Serializable
data class BackupPayload(
    val format: Int = BackupHeader.FORMAT_VERSION,
    val schema: Int = BackupHeader.SCHEMA_VERSION,
    val groups: List<BackupGroup> = emptyList(),
    val providers: List<BackupProvider> = emptyList(),
    val apiKeys: List<BackupApiKey> = emptyList(),
    val providerAccounts: List<BackupAccount> = emptyList(),
    val models: List<BackupModel> = emptyList(),
    val clientProfiles: List<BackupProfile> = emptyList(),
    val appSettings: List<BackupSetting> = emptyList(),
)

// ------------------------------------------------------------------ gzip

/** payload JSON → gzip 字节。 */
fun gzip(bytes: ByteArray): ByteArray {
    val buffer = Buffer()
    GzipSink(buffer).buffer().use { sink -> sink.write(bytes) }
    return buffer.readByteArray()
}

/** gzip 字节 → payload JSON。损坏抛 [BackupCorruptException]（红线 8，不返回 null）。 */
fun gunzip(bytes: ByteArray): ByteArray = try {
    val buffer = Buffer().write(bytes)
    GzipSource(buffer).buffer().use { it.readByteArray() }
} catch (t: Throwable) {
    throw BackupCorruptException("bad gzip payload")
}

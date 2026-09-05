package com.lc33.tokenvault.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 平台登录账号。**纯记录，不是密码管理器**（§2.3）。
 *
 * 用户名也加密：账号与密码成对才有价值，只加密密码等于把一半信息留在明文里；
 * 而且账号往往是邮箱或手机号，本身就是个人信息。所以搜索只索引 [label]。
 */
@Entity(
    tableName = "provider_accounts",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["providerId", "sortOrder", "id"]),
        Index(value = ["providerId", "usernameFp"], unique = true),
    ],
)
data class ProviderAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val label: String = "",

    /** AAD = `provider_accounts:{id}:usernameEnc`。**与密码列的 AAD 不同**，否则两列能互换。 */
    val usernameEnc: ByteArray? = null,
    val usernameFp: String? = null,

    /** AAD = `provider_accounts:{id}:passwordEnc`。 */
    val passwordEnc: ByteArray? = null,

    val loginUrl: String? = null,
    val note: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderAccountEntity) return false
        return id == other.id &&
            providerId == other.providerId &&
            usernameEnc.contentEquals(other.usernameEnc) &&
            passwordEnc.contentEquals(other.passwordEnc) &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (usernameEnc?.contentHashCode() ?: 0)
        result = 31 * result + (passwordEnc?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 客户端伪装预设。内置与自定义**同表**，靠 [builtinKey] 区分。
 *
 * 自定义预设的 `builtinKey` 是 NULL，而 SQLite 的唯一索引把多个 NULL 视为互不相同，
 * 所以那条唯一索引不会挡住多条自定义预设。
 */
@Entity(
    tableName = "client_profiles",
    indices = [Index(value = ["builtinKey"], unique = true)],
)
data class ClientProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val builtinKey: String? = null,
    val userAgent: String,

    /** JSON **数组** of `[key, value]`，不是对象：部分上游看请求头顺序，而对象顺序不保证。 */
    val headers: String = "[]",

    /** JSON merge patch。 */
    val bodyPatch: String = "{}",

    /** CSV，空表示通用。**是排序提示不是硬过滤**（§8.2）。 */
    val protocols: String = "",

    val verified: Boolean = false,
    val builtinRev: Int = 0,

    /** 用户改过则升级时不覆盖——既能随版本修正指纹，又不丢用户抓包校准的结果。 */
    val userEdited: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "models",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["providerId", "modelId", "protocol"], unique = true),
        Index(value = ["catalogKey"]),
    ],
)
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val modelId: String,
    val protocol: String,
    val displayName: String? = null,

    /** `manual` | `discovered`。手动录入的**永不被自动同步改动**（红线 13）。 */
    val source: String = "manual",

    /** 这一行从哪个协议的列表里发现的。"上游消失即停用"只在同协议内生效（红线 30）。 */
    val discoveredVia: String? = null,

    val enabled: Boolean = true,
    val favorite: Boolean = false,
    val needsReview: Boolean = false,
    val catalogKey: String? = null,
    val probeState: String = "unknown",
    val lastOutcome: String = "skipped",
    val probeDetail: String? = null,
    val latencyMs: Long? = null,
    val probedAt: Long? = null,
    val firstSeenAt: Long,
    val lastSeenAt: Long? = null,
    val sortOrder: Int = 0,
)

/** models.dev 的派生索引，只存用得上的字段。匹配是索引查询而不是全表扫描。 */
@Entity(
    tableName = "model_catalog",
    indices = [Index(value = ["modelId"]), Index(value = ["normId"])],
)
data class ModelCatalogEntity(
    /** `anthropic/claude-opus-4`。 */
    @PrimaryKey val key: String,
    val vendor: String,
    val modelId: String,

    /** 归一化 id，用于模糊匹配（去掉 `-latest`、日期后缀、`:free`）。 */
    val normId: String,
    val name: String? = null,
    val family: String? = null,
    val contextLimit: Int? = null,
    val outputLimit: Int? = null,
    val costInput: Double? = null,
    val costOutput: Double? = null,
    val costCacheRead: Double? = null,
    val costCacheWrite: Double? = null,

    /** CSV。 */
    val inputModalities: String? = null,
    val outputModalities: String? = null,
    val reasoning: Boolean = false,
    val toolCall: Boolean = false,
    val attachment: Boolean = false,
    val releaseDate: String? = null,
    val lastUpdated: String? = null,
)

@Entity(tableName = "probe_runs")
data class ProbeRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** `all` | `provider:{id}` | `key:{id}` | `model:{id}`。 */
    val scope: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val total: Int = 0,
    val done: Int = 0,
    val okCount: Int = 0,
    val failCount: Int = 0,
    val cancelled: Boolean = false,
)

/**
 * 日志。
 *
 * [message] 与 [detail] **入库前必须过 `Redactor.scrub`**（红线 32）。这一层不做脱敏——
 * 脱敏需要"当前会话已知的秘密"，那是 `VaultSession` 的知识；放在这里会变成
 * "有时候脱敏了有时候没脱"。
 */
@Entity(
    tableName = "audit_log",
    indices = [Index(value = ["at"], orders = [Index.Order.DESC])],
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val level: String,
    val category: String,
    val providerId: Long? = null,

    /** 归到具体某张密钥上。详情页的"查看日志"按它过滤。 */
    val keyId: Long? = null,
    val runId: Long? = null,
    val message: String,
    val detail: String? = null,
)

/**
 * 设置项。
 *
 * [value] 与 [valueBlob] **互斥**：加密设置项（WebDAV 凭据）走 blob 而不是把密文 base64
 * 塞进 TEXT 列——往 TEXT 里塞二进制迟早出编码问题（§6.2）。
 *
 * `themeMode` / `localeTag` / `onboarded` **不在这张表里**：它们的权威存储是 boot，
 * 因为锁屏页要用（红线 31）。`SettingsRepository` 对这三个键透传到 `BootStore`。
 */
@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String? = null,
    val valueBlob: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppSettingEntity) return false
        return key == other.key && value == other.value && valueBlob.contentEquals(other.valueBlob)
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + (value?.hashCode() ?: 0)
        result = 31 * result + (valueBlob?.contentHashCode() ?: 0)
        return result
    }
}

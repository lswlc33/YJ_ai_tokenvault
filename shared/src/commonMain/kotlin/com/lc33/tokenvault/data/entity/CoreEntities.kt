package com.lc33.tokenvault.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体（§6.2 的 DDL）。
 *
 * **实体一律用原始类型**：枚举存 `String`、协议集合存 CSV、覆盖表存 JSON 串。
 * 不用 `@TypeConverter` 是刻意的——转换器是隐式的，出问题时你在实体上看不到任何线索；
 * 而 CLAUDE.md 要求"Room 实体与 domain 的模型是两套类，中间有**显式**映射器"，
 * 那映射就该是唯一发生转换的地方，实体这一层只管字节怎么落盘。
 *
 * 密文列一律 `ByteArray`（BLOB）。这一层不解密，也拿不到 DEK（§6.1 推论 3）。
 */
@Entity(
    tableName = "groups",
    indices = [Index(value = ["name"], unique = true)],
)
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "providers",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ClientProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["clientProfileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["groupId"]),
        // 外键列必须有索引，否则父表每次改动都要全表扫 providers。
        // Room 会为此发警告，而警告在这个项目里等于"迟早变成性能 bug"。
        Index(value = ["clientProfileId"]),
        Index(value = ["pinned", "sortOrder", "id"]),
    ],
)
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String? = null,
    val websiteUrl: String? = null,

    /** 用户原样输入。 */
    val apiBaseUrl: String,

    /** 规范化结果。 */
    val apiRoot: String,
    val apiVersion: String = "v1",

    /** CSV：`chat,responses,anthropic`。用 `wireName` 而不是枚举名（改名不该改变已存数据的含义）。 */
    val supportedProtocols: String = "",

    /** JSON：`{"anthropic":"/anthropic/v1/messages"}`。 */
    val pathOverrides: String = "{}",

    val authStyle: String = "auto",

    /** 允许 `http://`。默认关，必须由用户在编辑页显式打开（§7.5）。 */
    val allowInsecure: Boolean = false,

    val clientProfileId: Long? = null,
    val groupId: Long? = null,
    val color: Int? = null,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,

    val balanceKind: String = "none",
    val balanceBaseUrl: String? = null,
    val balanceUserId: String? = null,

    /** NewAPI 访问令牌，字段级加密。AAD = `providers:{id}:balanceTokenEnc`。 */
    val balanceTokenEnc: ByteArray? = null,
    val balanceConfig: String = "{}",
    val quotaPerUnit: Double? = null,

    val balanceAmount: Double? = null,
    val balanceUsed: Double? = null,
    val balanceCurrency: String? = null,
    val balanceRaw: String? = null,
    val balanceCheckedAt: Long? = null,
    val balanceError: String? = null,
    val quotaCalibrated: Boolean = false,

    val timeoutSeconds: Int? = null,

    val reachabilityLatencyMs: Long? = null,
    val reachabilityCheckedAt: Long? = null,
    val reachabilityError: String? = null,

    // 探测开关，每家一份（红线 36）
    val probeEnabled: Boolean = true,
    val probeReachability: Boolean = true,
    val probeKeyValidity: Boolean = true,
    val probeBalance: Boolean = true,
    val probeModels: Boolean = false,
    val probeModelReachability: Boolean = false,

    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderEntity) return false
        // 只有 balanceTokenEnc 需要按内容比，其余交给下面的字段逐个比
        return id == other.id &&
            name == other.name &&
            apiRoot == other.apiRoot &&
            balanceTokenEnc.contentEquals(other.balanceTokenEnc) &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (balanceTokenEnc?.contentHashCode() ?: 0)
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

@Entity(
    tableName = "api_keys",
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
        Index(value = ["providerId", "fingerprint"], unique = true),
    ],
)
data class ApiKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val label: String = "",

    /** 自描述密文封套。AAD = `api_keys:{id}:secretEnc`（红线 24）。 */
    val secretEnc: ByteArray,

    /** `HMAC-SHA256(HKDF(DEK,"fp"), 明文)` 前 32 hex。**设备本地**，不能跨设备比较。 */
    val fingerprint: String,

    /**
     * 每个供应商至多一张。
     *
     * "至多"由**部分唯一索引** `idx_keys_default` 在数据库层面保证——Room 的 `@Index`
     * 不支持 `WHERE`，所以那条索引是手写 SQL（见 `VaultDatabase`）。
     * "至少"保证不了，靠 `DefaultKeyPolicy` + 单测。
     */
    val isDefault: Boolean = false,
    val enabled: Boolean = true,

    /** `KeyHealth.wireName`，持久结论。 */
    val health: String = "unknown",

    /** `ProbeOutcome.wireName`，最近一次探测发生了什么。**与 health 分两列**（红线 11）。 */
    val lastOutcome: String = "skipped",

    val healthDetail: String? = null,
    val httpStatus: Int? = null,
    val latencyMs: Long? = null,
    val checkedAt: Long? = null,
    val okAt: Long? = null,

    /** 这张 Key 的余额快照。供应商展示值由所有 Key 的这些列求和。 */
    val balanceAmount: Double? = null,
    val balanceUsed: Double? = null,
    val balanceCurrency: String? = null,
    val balanceRaw: String? = null,
    val balanceCheckedAt: Long? = null,
    val balanceError: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApiKeyEntity) return false
        return id == other.id &&
            providerId == other.providerId &&
            secretEnc.contentEquals(other.secretEnc) &&
            fingerprint == other.fingerprint &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + secretEnc.contentHashCode()
        result = 31 * result + fingerprint.hashCode()
        return result
    }
}

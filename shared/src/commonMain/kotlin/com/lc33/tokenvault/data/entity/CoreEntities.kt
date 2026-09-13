package com.lc33.tokenvault.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体（§6.2 的 DDL）。
 *
 * **实体一律用原始类型**：枚举存 `String`、协议集合存 CSV、覆盖表存 JSON 串。
 * 不用 `@TypeConverter` 是刻意的——转换器是隐式的，出问题时你在实体上看不到任何线索；
 * 而 CLAUDE.md 要求“Room 实体与 domain 的模型是两套类，中间有**显式**映射器”，
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

/** v3 起供应商只是 Key 合集：这里只存组织信息与官网连通性。 */
@Entity(
    tableName = "providers",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["pinned", "sortOrder", "id"]),
    ],
)
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String? = null,
    val websiteUrl: String? = null,
    val websiteLatencyMs: Long? = null,
    val websiteCheckedAt: Long? = null,
    val websiteError: String? = null,
    val groupId: Long? = null,
    val color: Int? = null,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

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

    /** Key 备注。`label` 是短名，`note` 是长说明；两者都是明文元数据。 */
    val note: String = "",

    /** 自描述密文封套。AAD = `api_keys:{id}:secretEnc`（红线 24）。 */
    val secretEnc: ByteArray,

    /** `HMAC-SHA256(HKDF(DEK,"fp"), 明文)` 前 32 hex。**设备本地**，不能跨设备比较。 */
    val fingerprint: String,

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

/** 一把 Key 一行，保存全部会影响请求结果的配置。 */
@Entity(
    tableName = "key_settings",
    foreignKeys = [
        ForeignKey(
            entity = ApiKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ClientProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["clientProfileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["keyId"], unique = true),
        Index(value = ["clientProfileId"]),
    ],
)
data class KeySettingsEntity(
    @PrimaryKey val keyId: Long,
    val apiBaseUrl: String,
    val apiRoot: String,
    val apiVersion: String = "v1",
    val supportedProtocols: String = "",
    val pathOverrides: String = "{}",
    val authStyle: String = "auto",
    val allowInsecure: Boolean = false,
    val clientProfileId: Long? = null,
    val timeoutSeconds: Int? = null,
    val balanceKind: String = "none",
    val balanceBaseUrl: String? = null,
    val balanceUserId: String? = null,
    val balanceTokenEnc: ByteArray? = null,
    val balanceConfig: String = "{}",
    val quotaPerUnit: Double? = null,
    val quotaCalibrated: Boolean = false,
    val probeEnabled: Boolean = true,
    val probeReachability: Boolean = true,
    val probeKeyValidity: Boolean = true,
    val probeBalance: Boolean = true,
    val probeModels: Boolean = false,
    val probeModelReachability: Boolean = false,
    val probeQuickModel: Boolean = false,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeySettingsEntity) return false
        return keyId == other.keyId &&
            apiBaseUrl == other.apiBaseUrl &&
            apiRoot == other.apiRoot &&
            balanceTokenEnc.contentEquals(other.balanceTokenEnc) &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = keyId.hashCode()
        result = 31 * result + apiBaseUrl.hashCode()
        result = 31 * result + apiRoot.hashCode()
        result = 31 * result + (balanceTokenEnc?.contentHashCode() ?: 0)
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

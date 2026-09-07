package com.lc33.tokenvault.domain.model

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ModelProbeState
import com.lc33.tokenvault.domain.ModelSource
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.Protocol

/**
 * API 密钥。
 *
 * [secretEnc] 是**密文**，这一层永远不解密（§6.1 推论 3）。所以这个类型上**没有任何
 * 能拿到明文的方法**——想拿明文必须去借 DEK，而那件事发生在明确的 UseCase 里。
 * 遮蔽串也不存：它是解密后现算的，因此只出现在详情页那种确实要展示密钥的地方（§6.2）。
 *
 * [health] 与 [lastOutcome] 是**两列**，这是红线 11 在数据层的表达：只有一列时，
 * UI 无法既显示"上次成功于 …"又说明这次为什么没验证成功，只能去解析字符串。
 */
data class ApiKey(
    val id: Long = 0,
    val providerId: Long,
    val label: String = "",
    val secretEnc: ByteArray,

    /**
     * `HMAC-SHA256(HKDF(DEK,"fp"), 明文)` 前 32 hex。
     *
     * **设备本地**：DEK 不同则指纹不同，所以它只能用于本机去重，**不能跨设备比较**——
     * 备份恢复时指纹必须在导入端重算（红线 27）。
     */
    val fingerprint: String,

    val isDefault: Boolean = false,
    val enabled: Boolean = true,

    /** 持久结论。只被判定性响应改写。 */
    val health: KeyHealth = KeyHealth.UNKNOWN,

    /** 最近一次探测发生了什么。每次都写。 */
    val lastOutcome: ProbeOutcome = ProbeOutcome.SKIPPED,

    val healthDetail: String? = null,
    val httpStatus: Int? = null,
    val latencyMs: Long? = null,

    /** 最近一次探测（含失败）。`SKIPPED` / `CANCELLED` 不写。 */
    val checkedAt: Long? = null,

    /** 最近一次**确认可用**。UI 的"上次成功于 …"读它。 */
    val okAt: Long? = null,

    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApiKey) return false
        return id == other.id &&
            providerId == other.providerId &&
            label == other.label &&
            secretEnc.contentEquals(other.secretEnc) &&
            fingerprint == other.fingerprint &&
            isDefault == other.isDefault &&
            enabled == other.enabled &&
            health == other.health &&
            lastOutcome == other.lastOutcome &&
            healthDetail == other.healthDetail &&
            httpStatus == other.httpStatus &&
            latencyMs == other.latencyMs &&
            checkedAt == other.checkedAt &&
            okAt == other.okAt &&
            sortOrder == other.sortOrder
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + providerId.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + secretEnc.contentHashCode()
        result = 31 * result + fingerprint.hashCode()
        result = 31 * result + health.hashCode()
        result = 31 * result + lastOutcome.hashCode()
        return result
    }
}

/**
 * 平台登录账号。**纯记录，不是密码管理器**（§2.3 的六条禁止项）。
 *
 * 用户名也加密（§6.2）：账号与密码成对才有价值，只加密密码等于把一半信息留在明文里；
 * 而且账号往往是邮箱或手机号，本身就是个人信息。
 * 账号**不参与搜索**——搜索只索引 [label]，因为加密列没法做 LIKE。
 */
data class ProviderAccount(
    val id: Long = 0,
    val providerId: Long,
    val label: String = "",
    val usernameEnc: ByteArray? = null,

    /** 与密钥指纹同算法，只为合并恢复时去重，同样是设备本地的。 */
    val usernameFp: String? = null,
    val passwordEnc: ByteArray? = null,

    /** 空则用 `provider.websiteUrl`。 */
    val loginUrl: String? = null,
    val note: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderAccount) return false
        return id == other.id &&
            providerId == other.providerId &&
            label == other.label &&
            usernameEnc.contentEquals(other.usernameEnc) &&
            usernameFp == other.usernameFp &&
            passwordEnc.contentEquals(other.passwordEnc) &&
            loginUrl == other.loginUrl &&
            note == other.note &&
            sortOrder == other.sortOrder
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + providerId.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + (usernameEnc?.contentHashCode() ?: 0)
        result = 31 * result + (passwordEnc?.contentHashCode() ?: 0)
        return result
    }
}

/** 模型。协议属于它，不属于供应商（红线 18）。 */
data class AiModel(
    val id: Long = 0,
    val providerId: Long,
    val modelId: String,
    val protocol: Protocol,
    val displayName: String? = null,
    val source: ModelSource = ModelSource.MANUAL,

    /**
     * 这一行是从**哪个协议的列表**里发现的（红线 30）。
     *
     * "上游消失即停用"只在 `discoveredVia == 本轮查询的协议` 的行上生效。少了这个限定，
     * 只拉了 CHAT 列表就会把所有 ANTHROPIC 发现项一起停用。
     */
    val discoveredVia: Protocol? = null,

    val enabled: Boolean = true,
    val favorite: Boolean = false,

    /** 疑似显示名而非真实 id（`DeepSeek V4 Pro` 这种）。黄色提示，不阻止导入。 */
    val needsReview: Boolean = false,

    val catalogKey: String? = null,
    val probeState: ModelProbeState = ModelProbeState.UNKNOWN,
    val lastOutcome: ProbeOutcome = ProbeOutcome.SKIPPED,
    val probeDetail: String? = null,
    val latencyMs: Long? = null,
    val probedAt: Long? = null,
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long? = null,
    val sortOrder: Int = 0,
)

package com.lc33.tokenvault.domain.model

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.ModelProbeState
import com.lc33.tokenvault.domain.ModelSource
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.Protocol

/**
 * API 密钥。
 *
 * v3 起 [settings] 保存全部请求与探测配置；`isDefault` 已取消，排序本身就是优先级。
 */
data class ApiKey(
    val id: Long = 0,
    val providerId: Long,
    val label: String = "",
    val note: String = "",
    val secretEnc: ByteArray,
    val fingerprint: String,
    val enabled: Boolean = true,
    val settings: KeySettings,

    val health: KeyHealth = KeyHealth.UNKNOWN,
    val lastOutcome: ProbeOutcome = ProbeOutcome.SKIPPED,
    val healthDetail: String? = null,
    val httpStatus: Int? = null,
    val latencyMs: Long? = null,
    val checkedAt: Long? = null,
    val okAt: Long? = null,
    val balance: BalanceSnapshot? = null,
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
            note == other.note &&
            secretEnc.contentEquals(other.secretEnc) &&
            fingerprint == other.fingerprint &&
            enabled == other.enabled &&
            settings == other.settings &&
            health == other.health &&
            lastOutcome == other.lastOutcome &&
            healthDetail == other.healthDetail &&
            httpStatus == other.httpStatus &&
            latencyMs == other.latencyMs &&
            checkedAt == other.checkedAt &&
            okAt == other.okAt &&
            balance == other.balance &&
            sortOrder == other.sortOrder &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + providerId.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + note.hashCode()
        result = 31 * result + secretEnc.contentHashCode()
        result = 31 * result + fingerprint.hashCode()
        result = 31 * result + settings.hashCode()
        result = 31 * result + health.hashCode()
        result = 31 * result + lastOutcome.hashCode()
        result = 31 * result + (balance?.hashCode() ?: 0)
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
    val usernameFp: String? = null,
    val passwordEnc: ByteArray? = null,
    val loginUrl: String? = null,
    val loginMethods: Set<LoginMethod> = emptySet(),
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
            loginMethods == other.loginMethods &&
            sortOrder == other.sortOrder
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + providerId.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + (usernameEnc?.contentHashCode() ?: 0)
        result = 31 * result + (passwordEnc?.contentHashCode() ?: 0)
        result = 31 * result + loginMethods.hashCode()
        return result
    }
}

/** 模型。协议属于它，不属于供应商（红线 18）。 */
data class AiModel(
    val id: Long = 0,
    val providerId: Long,
    val keyId: Long? = null,
    val modelId: String,
    val protocol: Protocol,
    val displayName: String? = null,
    val source: ModelSource = ModelSource.MANUAL,
    val discoveredVia: Protocol? = null,
    val enabled: Boolean = true,
    val favorite: Boolean = false,
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
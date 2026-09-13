package com.lc33.tokenvault.domain.model

import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.Protocol

/** 用户自定义分组。管理页的筛选条就是它们（§13.4）。 */
data class Group(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

/** 供应商官网连通性的最近一次结果。只说明“网站能不能打开”，不推断任何 API Key 是否有效。 */
data class WebsiteStatus(
    val latencyMs: Long? = null,
    val checkedAt: Long? = null,
    val error: String? = null,
) {
    val checked: Boolean get() = checkedAt != null
    val ok: Boolean get() = checkedAt != null && error == null
}

/**
 * 供应商。
 *
 * v3 起供应商只是 **Key 合集**：这里只保存组织信息与官网连通性。
 * 所有会影响请求结果的字段都在 [ApiKey.settings] 上，避免“合集层假共享配置”。
 */
data class Provider(
    val id: Long = 0,
    val name: String,
    val note: String? = null,
    val websiteUrl: String? = null,
    val website: WebsiteStatus = WebsiteStatus(),
    val groupId: Long? = null,
    val color: Int? = null,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** 列表页要的聚合投影（§6.3 那条聚合查询）。 */
data class ProviderSummary(
    val provider: Provider,
    val keyCount: Int,
    val okKeyCount: Int,
    val modelCount: Int,
    val accountCount: Int,
)

/** 一把 Key 的全部请求与探测配置。v3 起这些字段不再挂在供应商上。 */
data class KeySettings(
    val apiBaseUrl: String,
    val apiRoot: String,
    val apiVersion: String = "v1",

    /** 这把 Key 能走哪些协议。与模型自己的 `protocol` 不能互相推导（红线 18）。 */
    val supportedProtocols: Set<Protocol> = emptySet(),

    /** 协议 → 完整路径覆盖。 */
    val pathOverrides: Map<Protocol, String> = emptyMap(),
    val authStyle: AuthStyle = AuthStyle.AUTO,
    val allowInsecure: Boolean = false,
    val clientProfileId: Long? = null,
    val timeoutSeconds: Int? = null,

    val balanceKind: BalanceKind = BalanceKind.NONE,
    val balanceBaseUrl: String? = null,
    val balanceUserId: String? = null,

    /** NewAPI 访问令牌密文。它是明文秘密的密文形态，只在仓库加密/解密边界出现。 */
    val balanceTokenEnc: ByteArray? = null,
    val balanceConfig: String = "{}",
    val quotaPerUnit: Double? = null,
    val quotaCalibrated: Boolean = false,

    val probe: KeyProbeSettings = KeyProbeSettings(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeySettings) return false
        return apiBaseUrl == other.apiBaseUrl &&
            apiRoot == other.apiRoot &&
            apiVersion == other.apiVersion &&
            supportedProtocols == other.supportedProtocols &&
            pathOverrides == other.pathOverrides &&
            authStyle == other.authStyle &&
            allowInsecure == other.allowInsecure &&
            clientProfileId == other.clientProfileId &&
            timeoutSeconds == other.timeoutSeconds &&
            balanceKind == other.balanceKind &&
            balanceBaseUrl == other.balanceBaseUrl &&
            balanceUserId == other.balanceUserId &&
            balanceTokenEnc.contentEquals(other.balanceTokenEnc) &&
            balanceConfig == other.balanceConfig &&
            quotaPerUnit == other.quotaPerUnit &&
            quotaCalibrated == other.quotaCalibrated &&
            probe == other.probe
    }

    override fun hashCode(): Int {
        var result = apiBaseUrl.hashCode()
        result = 31 * result + apiRoot.hashCode()
        result = 31 * result + supportedProtocols.hashCode()
        result = 31 * result + pathOverrides.hashCode()
        result = 31 * result + authStyle.hashCode()
        result = 31 * result + balanceKind.hashCode()
        result = 31 * result + (balanceTokenEnc?.contentHashCode() ?: 0)
        result = 31 * result + probe.hashCode()
        return result
    }
}

/**
 * 每把 Key 单独一份的探测开关（红线 36）。
 *
 * 全局开关只能表达“应用要不要自动探测”，不能表达“这把 Key 所属的站点条款禁止自动化”
 * 或“这把 Key 一探测就烧钱”。所以权威在 Key 上，不在供应商或全局设置上。
 */
data class KeyProbeSettings(
    val enabled: Boolean = true,
    val reachability: Boolean = true,
    val keyValidity: Boolean = true,
    val balance: Boolean = true,
    val models: Boolean = false,
    val modelReachability: Boolean = false,
    val quickModelProbe: Boolean = false,
)

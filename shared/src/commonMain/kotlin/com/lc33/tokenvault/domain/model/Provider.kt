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

/**
 * 供应商。
 *
 * `balanceTokenEnc` 是**密文 `ByteArray`**，这一层永远不解密（§6.1 推论 3）：
 * `Flow<List<Provider>>` 在锁定瞬间如果去解密，就会在 Flow 内部抛异常、把整条订阅打断，
 * 于是列表页看起来"数据全没了"。明文只出现在明确借用 DEK 的 UseCase 里。
 */
data class Provider(
    val id: Long = 0,
    val name: String,
    val note: String? = null,
    val websiteUrl: String? = null,

    /** 用户原样输入的地址。保留它，规范化结果才有得对照（编辑页的实时预览要两边都显示）。 */
    val apiBaseUrl: String,

    /** 规范化后的根，去掉了版本段。 */
    val apiRoot: String,
    val apiVersion: String = "v1",

    /** 这家**能拉哪些模型列表、界面上给哪些选项**。与 `model.protocol` 不能互相推导（红线 18）。 */
    val supportedProtocols: Set<Protocol> = emptySet(),

    /** 协议 → 完整路径覆盖。DeepSeek 的 Anthropic 端点在 `/anthropic` 下，靠这个而不是启发式。 */
    val pathOverrides: Map<Protocol, String> = emptyMap(),

    val authStyle: AuthStyle = AuthStyle.AUTO,

    /** 允许 `http://`。**必须由用户在编辑页显式打开**，否则 `net/` 层直接拒绝（§7.5）。 */
    val allowInsecure: Boolean = false,

    val clientProfileId: Long? = null,
    val groupId: Long? = null,

    /** 色块下标。编辑页的色块选择器是它的**唯一入口**（红线 16）。 */
    val color: Int? = null,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,

    val balanceKind: BalanceKind = BalanceKind.NONE,
    val balanceBaseUrl: String? = null,
    val balanceUserId: String? = null,

    /** NewAPI 访问令牌的**密文**。与 API 密钥无关，是中转站个人安全设置里那个独立令牌。 */
    val balanceTokenEnc: ByteArray? = null,
    val balanceConfig: String = "{}",

    /** NewAPI 换算比。M0.5 在两家上都实测到 500000。 */
    val quotaPerUnit: Double? = null,

    val balance: BalanceSnapshot? = null,

    /** `quotaPerUnit` 有没有被 `/api/status` 校准过。没校准时 UI 要标出来（§9.2）。 */
    val quotaCalibrated: Boolean = false,

    /** 单独超时，null 表示用全局值。 */
    val timeoutSeconds: Int? = null,

    /** 最近一次供应商可达性探测的延迟。只表示站点可达，不代表任何密钥有效。 */
    val reachabilityLatencyMs: Long? = null,
    val reachabilityCheckedAt: Long? = null,
    val reachabilityError: String? = null,

    /** 探测开关，**每家单独一份**（红线 36）。 */
    val probe: ProviderProbeSettings = ProviderProbeSettings(),

    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Provider) return false
        return id == other.id &&
            name == other.name &&
            note == other.note &&
            websiteUrl == other.websiteUrl &&
            apiBaseUrl == other.apiBaseUrl &&
            apiRoot == other.apiRoot &&
            apiVersion == other.apiVersion &&
            supportedProtocols == other.supportedProtocols &&
            pathOverrides == other.pathOverrides &&
            authStyle == other.authStyle &&
            allowInsecure == other.allowInsecure &&
            clientProfileId == other.clientProfileId &&
            groupId == other.groupId &&
            color == other.color &&
            pinned == other.pinned &&
            sortOrder == other.sortOrder &&
            balanceKind == other.balanceKind &&
            balanceBaseUrl == other.balanceBaseUrl &&
            balanceUserId == other.balanceUserId &&
            balanceTokenEnc.contentEquals(other.balanceTokenEnc) &&
            balanceConfig == other.balanceConfig &&
            quotaPerUnit == other.quotaPerUnit &&
            balance == other.balance &&
            quotaCalibrated == other.quotaCalibrated &&
            timeoutSeconds == other.timeoutSeconds &&
            reachabilityLatencyMs == other.reachabilityLatencyMs &&
            reachabilityCheckedAt == other.reachabilityCheckedAt &&
            reachabilityError == other.reachabilityError &&
            probe == other.probe &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + apiRoot.hashCode()
        result = 31 * result + supportedProtocols.hashCode()
        result = 31 * result + (balanceTokenEnc?.contentHashCode() ?: 0)
        result = 31 * result + balanceKind.hashCode()
        result = 31 * result + (balance?.hashCode() ?: 0)
        result = 31 * result + (reachabilityLatencyMs?.hashCode() ?: 0)
        result = 31 * result + probe.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/**
 * 每家单独一份的探测开关（红线 36）。
 *
 * 全局一份开关没法表达"这一家先别自动探"，而这是真实需求：额度紧的那家不想被 L3 烧钱，
 * 挂在 Cloudflare 后面的那家不想被定时任务反复撞 1015。设置里的同名项只是**新建时的默认值**。
 *
 * [enabled] 是总闸。关掉时 UI 上其余五项**灰掉而不是隐藏**——藏起来会让人以为设置丢了。
 */
data class ProviderProbeSettings(
    val enabled: Boolean = true,
    val reachability: Boolean = true,
    val keyValidity: Boolean = true,
    val balance: Boolean = true,

    /** 模型列表自动检测。开启后探测时按 Key 拉取并合并模型列表。 */
    val models: Boolean = false,

    /** 模型可达性探测（快捷）。默认关，只允许用户长按模型手动触发。 */
    val modelReachability: Boolean = false,
)

/**
 * 列表页要的聚合投影（§6.3 那条聚合查询）。
 *
 * 单独一个类型而不是让 UI 自己去数：首页只要计数与余额，不该为此拉全量模型。
 */
data class ProviderSummary(
    val provider: Provider,
    val keyCount: Int,
    val okKeyCount: Int,
    val modelCount: Int,
    val accountCount: Int,
)

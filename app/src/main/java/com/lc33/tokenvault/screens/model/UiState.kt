package com.lc33.tokenvault.screens.model

/**
 * 仪表盘与管理页的 UiState（计划.md §13.4）。
 *
 * **需要本地化的东西一律不在这里拼好**：ViewModel 读不到资源（红线 19），所以
 * 相对时间给的是时间戳、“需要处理”给的是原因枚举，文案由页面现取。
 * 金额反过来：它必须在这一层就定点舍入成字符串，因为页面不允许做浮点运算（§9.1）。
 */

data class BalanceSummary(
    /** 按币种分组的合计。**不是总和**——不做汇率换算，所以永远是并列展示。 */
    val perCurrency: List<UiMoney> = emptyList(),

    /**
     * 最近一次成功查询的时间戳，**不是格式化好的相对时间串**（同 [UiKeyRow.checkedAt]）。
     *
     * 分档与文案用 `relativeBucketOf` + `relativeTimeLabel`，那两个函数本来就是为此拆开的。
     */
    val updatedAt: Long? = null,

    val failedProviderCount: Int = 0,
)

data class ContentCounts(
    val providers: Int = 0,
    val keys: Int = 0,
    val models: Int = 0,
    val accounts: Int = 0,
)

data class HealthBreakdown(
    val ok: Int = 0,
    val warn: Int = 0,
    val error: Int = 0,
    val unknown: Int = 0,
) {
    val total: Int get() = ok + warn + error + unknown
    val allOk: Boolean get() = total > 0 && ok == total
}

/**
 * 需要动手处理的那一类事（§13.4 第四块卡）。
 *
 * 四档全都是“用户真的能动手改”的：换一把密钥 / 换客户端预设 / 改配置 / 充钱。
 * **瞬时失败不在这里**（红线 11）：网络不可达与 429 不是要动手的事，混进来只会让用户
 * 以为有三样东西坏了，而其实只坏了一样。
 */
enum class AttentionKind {
    /** 401 / 403：这把钥匙不对。 */
    KeyRejected,

    /** 上游按客户端指纹拦了。这一行行内直接给“换客户端预设”。 */
    ClientBlocked,

    /** 端点、路径或参数不被接受。 */
    ConfigError,

    /** 余额低于该币种的提醒阈值（含负数）。 */
    LowBalance,
}

/** 需要动手处理的一项。瞬时失败不进这个列表（红线 11）。 */
data class AttentionItem(
    val providerId: Long,
    val providerName: String,
    val health: UiHealth,

    /**
     * 为什么进了这个列表。**枚举而不是已经本地化的一句话**：句子在 strings.xml 里，
     * 而 ViewModel 读不到资源（红线 19）。页面还靠它判断要不要给“换客户端预设”那个按钮。
     */
    val kind: AttentionKind,
)

data class ProbeRunSummary(
    /**
     * 这一轮完成的时间戳。**不是格式化好的相对时间串**（红线 19，同 [UiKeyRow.checkedAt]）：
     * ViewModel 拿不到资源，所以给时间戳，相对时间由页面用 `relativeLabel` 现算。
     */
    val finishedAtMs: Long,

    /** 这一轮花了多久（毫秒）。耗时文案由页面用 `durationSeconds` + 资源现算。 */
    val durationMs: Long,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
)

data class ProbeProgress(
    val done: Int,
    val total: Int,
    val currentLabel: String,
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total.toFloat()
}

data class BackupStatus(
    val lastBackupAgo: String? = null,
    val targetLabel: String? = null,
    val sizeLabel: String? = null,
)

data class DashboardUiState(
    val loading: Boolean = false,
    val balance: BalanceSummary = BalanceSummary(),
    val counts: ContentCounts = ContentCounts(),
    val health: HealthBreakdown = HealthBreakdown(),
    val attention: List<AttentionItem> = emptyList(),
    val lastRun: ProbeRunSummary? = null,
    val progress: ProbeProgress? = null,
    val backup: BackupStatus = BackupStatus(),

    /**
     * 渲染这一屏时的“现在”，给相对时间用。同 [ProviderDetailUiState.nowMs]：
     * 同一屏里的两个“3 小时前”必须以同一个基准算。
     */
    val nowMs: Long = 0,

    /**
     * 两个“这个动作现在真的能做”开关。
     *
     * 它们存在的理由是：**一个点下去什么都不会发生的按钮也是在撑谎**。探测引擎在 M5、
     * 余额适配器在 M7，在那之前“开始探测”与“刷新余额”没有实现，于是干脆不画。
     * 各自那个里程碑做完就把自己那一个翻成 true，两个都为 true 之后这两个字段一起删掉。
     */
    val canProbe: Boolean = false,
    val canRefreshBalance: Boolean = false,
) {
    val isEmpty: Boolean get() = counts.providers == 0
}

/**
 * 管理页。**只列供应商**——密钥 / 模型 / 平台账号在 [ProviderDetailUiState] 里。
 *
 * 第一版做成四个分段是过度设计：四张表里三张的每一行都得带"所属供应商"才看得懂，
 * 等于把详情页的信息拆碎摊在四个地方。
 */
data class ManageUiState(
    val query: String = "",
    /** 第一枚固定是「全部」（`id == null`），其余是用户自定义分组。 */
    val groups: List<UiGroup> = emptyList(),
    val selectedGroupId: Long? = null,
    val providers: List<UiProviderRow> = emptyList(),
) {
    /** 当前分组下要显示的行。分组是纯 UI 筛选，不需要回数据层重查。 */
    val visibleProviders: List<UiProviderRow>
        get() = if (selectedGroupId == null) providers else providers.filter { it.groupId == selectedGroupId }
}

data class ProviderDetailUiState(
    val provider: UiProviderRow,
    val keys: List<UiKeyRow>,
    val models: List<UiModelRow>,
    val accounts: List<UiAccountRow>,
    /**
     * 渲染这一屏时的"现在"，给相对时间用。
     *
     * 由 ViewModel 一次取好而不是页面各自调 `System.currentTimeMillis()`：同一屏里
     * 两行的"3 小时前"必须以同一个基准算，否则滚动时它们会各自漂移。
     */
    val nowMs: Long = 0,
)

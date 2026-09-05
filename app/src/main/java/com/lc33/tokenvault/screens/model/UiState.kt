package com.lc33.tokenvault.screens.model

/**
 * 仪表盘与管理页的 UiState（计划.md §13.4）。
 *
 * M0.8 阶段这些由 `SampleContent` 直接构造；M3 换成 ViewModel + 仓库时字段大概会调一轮，
 * 但页面只认这几个类型，所以调整不会扩散到布局代码里。
 */

data class BalanceSummary(
    /** 按币种分组的合计。**不是总和**——不做汇率换算，所以永远是并列展示。 */
    val perCurrency: List<UiMoney>,
    val updatedAgo: String?,
    val failedProviderCount: Int,
)

data class ContentCounts(
    val providers: Int,
    val keys: Int,
    val models: Int,
    val accounts: Int,
)

data class HealthBreakdown(
    val ok: Int,
    val warn: Int,
    val error: Int,
    val unknown: Int,
) {
    val total: Int get() = ok + warn + error + unknown
    val allOk: Boolean get() = total > 0 && ok == total
}

/** 需要动手处理的一项。瞬时失败不进这个列表（红线 11）。 */
data class AttentionItem(
    val providerId: Long,
    val providerName: String,
    val health: UiHealth,
    /** 已本地化的一句话，页面直接画。 */
    val message: String,
    /** 为真时行内给"换客户端预设"按钮（`CLIENT_BLOCKED`）。 */
    val offerClientProfileFix: Boolean = false,
)

data class ProbeRunSummary(
    val finishedAgo: String,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val durationLabel: String,
)

data class ProbeProgress(
    val done: Int,
    val total: Int,
    val currentLabel: String,
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total.toFloat()
}

data class BackupStatus(
    val lastBackupAgo: String?,
    val targetLabel: String?,
    val sizeLabel: String?,
)

data class DashboardUiState(
    val loading: Boolean = false,
    val balance: BalanceSummary,
    val counts: ContentCounts,
    val health: HealthBreakdown,
    val attention: List<AttentionItem>,
    val lastRun: ProbeRunSummary?,
    val progress: ProbeProgress?,
    val backup: BackupStatus,
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

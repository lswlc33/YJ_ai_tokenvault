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

enum class ManageTab {
    Providers,
    Keys,
    Models,
    Accounts,
}

data class ManageUiState(
    val tab: ManageTab = ManageTab.Providers,
    val query: String = "",
    val providers: List<UiProviderRow> = emptyList(),
    val keys: List<UiKeyRow> = emptyList(),
    val models: List<UiModelRow> = emptyList(),
    val accounts: List<UiAccountRow> = emptyList(),
)

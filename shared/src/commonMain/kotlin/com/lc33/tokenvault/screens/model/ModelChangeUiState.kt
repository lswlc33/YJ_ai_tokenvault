package com.lc33.tokenvault.screens.model

import com.lc33.tokenvault.catalog.ModelChangeEntry

/**
 * 模型变化页的整屏状态。
 *
 * 与 [BalanceTrendUiState] 同一条约定：只带原始数值与枚举、不带已翻译文案——日期与
 * "新增 / 下架"这些字样都在页面里经 `relativeLabelWithinDay` 与资源成形，ViewModel
 * 拿不到 `stringResource`。
 */
data class ModelChangeUiState(
    val loading: Boolean = true,
    /** 与余额趋势同一个窗口口径（按本地日历日、今天算一天），两页共用那一排 chips。 */
    val range: TrendRange = TrendRange.D30,
    val rows: List<ProviderModelChangeRow> = emptyList(),

    /**
     * 流水表里**到底有没有东西**，与"这个窗口里有没有"是两件事，空态要说不同的话：
     * 从没记过 = 升级到 v11 之后还没成功刷过一次模型列表；记过而窗口里没有 = 这段时间真没变化。
     */
    val hasAnyEvent: Boolean = false,
    val nowMs: Long = 0L,
)

/**
 * 一家站点在这个窗口内的净变化。
 *
 * @param colorIndex 手选颜色下标，null = 生成色；取色走 `LocalProviderPalette.colorFor`，
 *   与管理页那排列表、余额趋势的线**同一个颜色**。
 * @param added 与 [removed] 互斥：同一个模型只会出现在其中一段（规则在
 *   `catalog/ModelChangeSummary`，那里还负责与 `models` 当前态对账）。
 */
data class ProviderModelChangeRow(
    val providerId: Long,
    val providerName: String,
    val colorIndex: Int?,
    val added: List<ModelChangeEntry>,
    val removed: List<ModelChangeEntry>,
)

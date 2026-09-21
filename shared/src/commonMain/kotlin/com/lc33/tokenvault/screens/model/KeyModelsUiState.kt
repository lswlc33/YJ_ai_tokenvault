package com.lc33.tokenvault.screens.model

import com.lc33.tokenvault.catalog.CatalogSyncState
import com.lc33.tokenvault.domain.ModelFilter
import com.lc33.tokenvault.domain.ModelGroupBy
import com.lc33.tokenvault.domain.ModelSort

/**
 * 整屏模型页的状态。
 *
 * 这里带的是**枚举而不是文案**：分组方式、排序方式、预设筛选那三项要在界面里
 * 翻译成资源串（红线：Kotlin 里没有中文字面量），ViewModel 拿不到 `stringResource`，
 * 所以只把语义传过去，由页面 `when` 出来。
 */
data class KeyModelsUiState(
    val keyLabel: String,
    val providerName: String,

    /** 可写模式 = 这把 Key 没开「模型列表自动获取」。开了的话下次同步就覆盖，编辑没意义。 */
    val editable: Boolean,

    /** 长按一行能不能真发一次模型可达性探测——两档开关都开才算，与详情页同一条判定。 */
    val quickProbe: Boolean,

    /**
     * 模型那条流还没发过第一帧。
     *
     * true 时不许说"该密钥没有模型"：那一帧 `groups` 也是空的，与"真的没有"长得一样，
     * 于是进页面会先闪一句空态、整屏列表才跳出来。
     */
    val loading: Boolean,

    val groups: List<UiModelGroup>,

    /** 库里这一把 Key 的模型总数（不受筛选影响，摘要那句要说的是真相而不是筛完的残影）。 */
    val totalModels: Int,

    /** 挂上目录的行数。0 = 目录还没同步过，页面据此决定顶栏那句提示。 */
    val matchedModels: Int,

    /** 筛完还剩几行。为 0 而 [totalModels] 不为 0 时是"筛没了"，不是"一个都没有"。 */
    val visibleModels: Int,

    val groupBy: ModelGroupBy,
    val sort: ModelSort,
    val filter: ModelFilter,
    val query: String,

    /**
     * 目录同步的阶段。null = 没在跑。
     *
     * 失败那一档的 [CatalogSyncState.Failed.reason] 是英文诊断串，不是界面文案——
     * 页面只念"更新失败"，原因留给日志页。
     */
    val catalogSync: CatalogSyncState?,

    /** 目录从没同步成功过（`model_catalog` 行数为 0）。页面据此把「更新目录」摆到显眼处。 */
    val catalogNeverSynced: Boolean,

    /**
     * 当前时刻，给"3 小时前探测过"那类相对时间用。
     *
     * 与 [com.lc33.tokenvault.screens.model.KeyDetailUiState.nowMs] 同一个理由：分档与
     * 文案要 `stringResource`，ViewModel 拿不到资源，所以这里只给时间戳。
     */
    val nowMs: Long,
)

data class UiModelGroup(
    val key: String,
    val title: String,
    val collapsed: Boolean,
    val rows: List<UiModelCardRow>,
)

data class UiModelCardRow(
    val id: Long,
    val modelId: String,
    val protocol: String,
    val source: UiModelSource,
    val health: UiHealth,
    /** 探测这一行需不需要真花钱——只在两档开关都开时给 true，沿用详情页那条判定。 */
    val quickProbe: Boolean,
    val probedAt: Long?,
    val latencyMs: Long?,
    val meta: UiModelMeta?,
) {
    /** 没匹配上目录时列表右侧那句轻提示。 */
    val unmatched: Boolean get() = meta == null
}

/**
 * 目录给出来的模型能力。
 *
 * **不含价格**：他明确说这一页只看能力，models.dev 那份价又是各家自填的转售价，
 * 摆在只读列表旁边只会让人以为是厂方定价。
 *
 * 数值一律在这里就格式化成 `128K` 这种短串：`32768` 要在行内读出位数很吃力，
 * 而 "K/M" 不是需要翻译的词，留在 ViewModel 侧比塞进资源里干净。
 */
data class UiModelMeta(
    val displayName: String?,
    val vendorName: String?,
    val description: String?,
    val context: String?,
    val output: String?,
    val reasoning: Boolean,
    val toolCall: Boolean,
    val structuredOutput: Boolean,
    val attachment: Boolean,
    val openWeights: Boolean,
    val vision: Boolean,
    val audioIn: Boolean,
    val videoIn: Boolean,
    val releaseDate: String?,
    val knowledgeCutoff: String?,
    val status: String?,
)

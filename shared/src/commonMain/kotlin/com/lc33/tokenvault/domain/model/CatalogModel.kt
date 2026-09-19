package com.lc33.tokenvault.domain.model

/**
 * models.dev 目录里的一条模型，**平台无关那份**。
 *
 * 放在 `domain/` 而不是直接用 `data/entity/ModelCatalogEntity`：`catalog/` 是纯 Kotlin 包，
 * `ArchitectureRulesTest.纯 Kotlin 层不依赖平台` 禁止它引入任何 AndroidX 类型（含 Room），
 * 而 Room 实体正是那类。和 [AiModel] ↔ `ModelEntity` 同一套分工，
 * 转换在 `data/mapper/EntityMappers.kt`。
 *
 * 四个 id 各不相同，含义见 [com.lc33.tokenvault.catalog.CatalogParser] 的类注释——
 * 弄混其中一个的后果不是报错，而是**静默匹配错**，所以每个字段都写清楚是谁拼出来的：
 *
 * - [key] = `providerSlug/mapKey`，主键，唯一性由外层兜底。
 * - [modelId] = mapKey **原样**，可能带厂商前缀。
 * - [qualifiedId] = `vendor/裸 id`，把「同一模型的原创条目和各聚合站条目」归到同一个桶。
 * - [normId] = 归一化后的**裸 id**，第三级模糊匹配用。
 */
data class CatalogModel(
    val key: String,
    /** models.dev 的外层 key——「这份列表是谁给的」。 */
    val providerSlug: String,
    /** 展示与分组用的厂商 slug。 */
    val vendor: String,
    /** 厂商展示名；查不到时为 null，界面退回显示 [vendor] 这个 slug。 */
    val vendorName: String?,
    val modelId: String,
    val qualifiedId: String,
    val normId: String,

    /** 这条是不是厂商自己挂出来的（[providerSlug] == [vendor]），不是聚合站转售。 */
    val canonical: Boolean,

    val name: String? = null,
    val description: String? = null,
    val family: String? = null,
    val contextLimit: Int? = null,
    val outputLimit: Int? = null,
    val costInput: Double? = null,
    val costOutput: Double? = null,
    val costCacheRead: Double? = null,
    val costCacheWrite: Double? = null,

    /** 模态，原始小写值：`text` / `image` / `audio` / `video`。 */
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    val reasoning: Boolean = false,
    val toolCall: Boolean = false,
    val attachment: Boolean = false,
    val structuredOutput: Boolean = false,
    val openWeights: Boolean = false,
    val releaseDate: String? = null,
    val lastUpdated: String? = null,

    /** `preview` / `deprecated` 之类，上游只有 283/7864 行有。 */
    val status: String? = null,

    /** 训练知识截止时间。 */
    val knowledgeCutoff: String? = null,
)

/** models.dev 的一家厂商。分组标题和「查看官方文档」用它。 */
data class CatalogVendor(
    val slug: String,
    val name: String,
    val apiUrl: String? = null,
    val docUrl: String? = null,
)

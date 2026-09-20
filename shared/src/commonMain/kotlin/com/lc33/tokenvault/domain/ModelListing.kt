package com.lc33.tokenvault.domain

import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.CatalogModel

/** 分组方式。每一项都要在 `string-array` 里有同序的一条，见 `ArchitectureRulesTest`。 */
enum class ModelGroupBy {
    /** 按模型名前缀归族（[ModelFamily]）。默认。 */
    FAMILY,

    /** 按来源：手动录入 / 自动发现。 */
    SOURCE,

    /** 不分组，一整列。 */
    NONE,
}

/** 组内排序方式。 */
enum class ModelSort {
    NAME_ASC,
    NAME_DESC,
    CONTEXT_DESC,
    RECENT_PROBE,
    ADDED,
}

/**
 * 预设筛选。
 *
 * 没有"常见"这一档：分组既然按前缀，OpenAI / DeepSeek 这些常见厂商天然就是最大的几组，
 * 再叠一个"常见"要么靠我们自己编热度规则（models.dev 没有流行度字段），要么和分组重复。
 * 能按能力筛才是这一排的真价值——"这把 Key 上哪些模型能看图""哪些支持工具调用"
 * 是配客户端时真正会问的问题。
 */
enum class ModelFilter {
    ALL,
    REASONING,
    TOOL_CALL,
    VISION,
    MATCHED,
    UNMATCHED,
}

/**
 * 一页模型列表的派生结果。
 *
 * @param groupCount 分组数（不含被筛掉的），给"共 N 个 · M 类"那句摘要用。
 * @param visibleModels 通过筛选与搜索的行数。与 [totalModels] 不相等时说明有行被筛掉。
 */
data class ModelListing(
    val groups: List<ModelGroup>,
    val totalModels: Int,
    val matchedModels: Int,
    val visibleModels: Int,
)

data class ModelGroup(
    val key: String,
    /** 展示用键：分组是 [ModelGroupBy.FAMILY] 时这个就是要拿去查厂商展示名的 slug。 */
    val titleKey: String,
    val rows: List<ListedModel>,
)

/** 一行模型 + 它挂到的目录条目（没挂上为 null）。 */
data class ListedModel(
    val model: AiModel,
    val catalog: CatalogModel?,
)

/**
 * 模型页的分组 / 筛选 / 排序。纯函数，不碰 DAO 也不碰 Compose。
 *
 * 放在 `domain/` 是因为这一层的全部难度都在"这几条规则互相怎么让路"，而不是画面上：
 * 排序在分组之内还是全局、筛掉一组时组头要不要留、目录没同步时能力筛选该不该亮着。
 * 这些都能在 JVM 上钉死，放进 composable 就只能靠手滑真机验证了。
 *
 * @param vendorNames 目录厂商展示名，来自 `model_vendors`（见 [ModelFamily.displayOf]）。
 *   传进来而不是让这里去查表：这一层不发查询。
 */
fun listModels(
    models: List<AiModel>,
    catalogByKey: Map<String, CatalogModel>,
    groupBy: ModelGroupBy,
    sort: ModelSort,
    filter: ModelFilter,
    query: String,
    vendorNames: Map<String, String> = emptyMap(),
): ModelListing {
    val paired = models.map { model ->
        ListedModel(model = model, catalog = model.catalogKey?.let(catalogByKey::get))
    }
    val kept = paired.filter { passes(it, query, filter) }
    val groups = when (groupBy) {
        ModelGroupBy.NONE ->
            listOf(ModelGroup(key = "", titleKey = "", rows = sorted(kept, sort)))

        ModelGroupBy.SOURCE -> kept
            // 组头按"手动在前"排：这一档下用户多半是来找自己填的那几个的。
            .groupBy { if (it.model.source == ModelSource.MANUAL) "manual" else "discovered" }
            .map { (key, rows) -> ModelGroup(key = key, titleKey = key, rows = sorted(rows, sort)) }
            .sortedBy { it.key }

        ModelGroupBy.FAMILY -> kept
            .groupBy { ModelFamily.keyOf(it.model.modelId) }
            // 组按"数量多的在前"排，同数量按展示名。中转站里真正在用的那几家会一大把模型，
            // 让它们顶在前面才叫分组；纯按字母排会被十几个单行小组挤得看不见。
            .let { byFamily ->
                byFamily.entries.sortedWith(
                    compareByDescending<Map.Entry<String, List<ListedModel>>> { it.value.size }
                        .thenBy { ModelFamily.displayOfKey(it.key, vendorNames[it.key]) },
                )
            }
            .map { entry ->
                ModelGroup(
                    key = entry.key,
                    titleKey = entry.key,
                    rows = sorted(entry.value, sort),
                )
            }
    }
    return ModelListing(
        groups = groups,
        totalModels = paired.size,
        matchedModels = paired.count { it.catalog != null },
        visibleModels = kept.size,
    )
}

/**
 * 一行是否留在页面上。
 *
 * 搜索同时命中模型 id、显示名和厂商展示名：用户记住的可能是"那个 claude"，
 * 也可能是客户端里贴的 id，只搜 id 会让搜索看起来"不认识的模型就搜不到"。
 */
private fun passes(row: ListedModel, query: String, filter: ModelFilter): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isNotEmpty()) {
        val haystack = listOfNotNull(
            row.model.modelId,
            row.model.displayName,
            row.catalog?.name,
            row.catalog?.vendorName,
            row.catalog?.family,
        ).joinToString(" ").lowercase()
        if (!haystack.contains(needle)) return false
    }
    val catalog = row.catalog
    return when (filter) {
        ModelFilter.ALL -> true
        ModelFilter.REASONING -> catalog?.reasoning == true
        ModelFilter.TOOL_CALL -> catalog?.toolCall == true
        ModelFilter.VISION -> catalog?.inputModalities?.contains("image") == true
        ModelFilter.MATCHED -> catalog != null
        // "没匹配上"这一档是真有用途的：它是"我这份目录还缺多少没认出来的东西"的诊断入口，
        // 也是唯一能一眼看出上游改名的地方。
        ModelFilter.UNMATCHED -> catalog == null
    }
}

/**
 * 组内排序。
 *
 * [ModelSort.CONTEXT_DESC] 把没匹配上目录的行统一垫到最后：它们没有上下文这个属性，
 * 而不是"上下文为 0"——当成 0 会让"我的私有网关"这种行出现在末尾但看起来像数据错。
 */
private fun sorted(rows: List<ListedModel>, sort: ModelSort): List<ListedModel> = when (sort) {
    ModelSort.NAME_ASC -> rows.sortedBy { it.model.modelId.lowercase() }
    ModelSort.NAME_DESC -> rows.sortedByDescending { it.model.modelId.lowercase() }
    ModelSort.CONTEXT_DESC -> rows.sortedWith(
        compareByDescending<ListedModel> { it.catalog?.contextLimit ?: -1 }
            .thenBy { it.model.modelId.lowercase() },
    )

    ModelSort.RECENT_PROBE -> rows.sortedWith(
        compareByDescending<ListedModel> { it.model.probedAt ?: 0L }
            .thenBy { it.model.modelId.lowercase() },
    )

    ModelSort.ADDED -> rows.sortedWith(
        compareBy<ListedModel> { it.model.sortOrder }.thenBy { it.model.id },
    )
}

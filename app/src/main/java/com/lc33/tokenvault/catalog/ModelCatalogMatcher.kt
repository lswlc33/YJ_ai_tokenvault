package com.lc33.tokenvault.catalog

/**
 * 模型元数据的**匹配决策**（§10）。纯函数，不碰 DAO / 网络。
 *
 * 三级匹配，全部走索引（§10）：DAO 层提供候选，这一层只做"该选哪个"的决策——
 * 因为"多厂商同名时用 vendorHint 消歧、否则取 lastUpdated 最新"是**决策**不是查询，
 * 放进 SQL 里会变成一段没法单测的 SQL 字符串。
 *
 * 三个级别：
 * 1. modelId 含 `/` → 直接命中（它是 `vendor/model` 形式，就是主键）。
 * 2. 精确 modelId 唯一命中 → 用它；多厂商同名 → vendorHint 消歧，否则取最新。
 * 3. 前两级都空 → 归一化后查 normId（[CatalogNormalize]）。
 */
object ModelCatalogMatcher {

    /** 一个候选（DAO 查回来的行，已投影成纯数据）。 */
    data class CatalogEntry(
        val key: String,
        val vendor: String,
        val modelId: String,
        val normId: String,
        /** `lastUpdated` 的字符串形式；比较时按字典序即可（models.dev 给的是 ISO 日期）。 */
        val lastUpdated: String? = null,
    )

    /**
     * 三级匹配。
     *
     * @param modelId 要匹配的模型 id（用户录入或探测发现的）。
     * @param vendorHint 供应商名 / host 的消歧提示（可选）。
     * @param byKey 主键精确查的结果（modelId 含 `/` 时用它）。
     * @param byModelId 精确 modelId 查的结果（可能多个）。
     * @param byNormId 归一化后查的结果（可能多个）。
     * @return 命中的 [CatalogEntry]，没匹配到返回 null（详情页就只显示模型 id，不显示占位符）。
     */
    fun match(
        modelId: String,
        vendorHint: String?,
        byKey: CatalogEntry?,
        byModelId: List<CatalogEntry>,
        byNormId: List<CatalogEntry>,
    ): CatalogEntry? {
        // 1. 含 / 的主键命中。
        if (modelId.contains('/')) return byKey

        // 2. 精确 modelId。
        if (byModelId.isNotEmpty()) {
            return disambiguate(byModelId, vendorHint)
        }

        // 3. 归一化。
        if (byNormId.isNotEmpty()) {
            return disambiguate(byNormId, vendorHint)
        }
        return null
    }

    /**
     * 多候选消歧：vendorHint 命中的优先；否则取 lastUpdated 最新（字典序最大）。
     */
    private fun disambiguate(candidates: List<CatalogEntry>, vendorHint: String?): CatalogEntry? {
        if (candidates.size == 1) return candidates.first()
        if (vendorHint != null) {
            val hint = vendorHint.lowercase()
            candidates.firstOrNull { it.vendor.lowercase().contains(hint) }?.let { return it }
        }
        // 取 lastUpdated 最新的（ISO 日期字典序 == 时间序）。
        return candidates.maxByOrNull { it.lastUpdated ?: "" }
    }
}

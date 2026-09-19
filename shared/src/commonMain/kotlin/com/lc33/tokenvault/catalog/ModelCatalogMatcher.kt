package com.lc33.tokenvault.catalog

/**
 * 模型元数据的**匹配决策**（§10）。纯函数，不碰 DAO / 网络。
 *
 * 三级匹配，全部走索引（§10）：DAO 层提供候选，这一层只做"该选哪个"的决策——
 * 因为"多厂商同名时用 vendorHint 消歧、否则取 lastUpdated 最新"是**决策**不是查询，
 * 放进 SQL 里会变成一段没法单测的 SQL 字符串。
 *
 * 三个级别（依次回退，每级都是"多候选 → [disambiguate]"）：
 * 1. `vendor/model` 形式的输入 → 按 [CatalogEntry.qualifiedId] 命中。
 * 2. 精确 modelId（上游 mapKey 原样，可能自带前缀）。
 * 3. 前两级都空 → 归一化后查 [CatalogEntry.normId]（[CatalogNormalize]）。
 *
 * 第一级原先是"含 `/` 就拿输入当目录主键查一条"。那在主键还是 `vendor/model` 的年代成立，
 * 但 v9 之后主键是 `providerSlug/mapKey`（见 [CatalogParser]），聚合站条目的主键会长成
 * `tokengo/deepseek/deepseek-chat`，拿用户的 `deepseek/deepseek-chat` 去比主键**永远比不上**，
 * 含斜杠输入于是整级失效、退到第二级才勉强命中。改成按 qualifiedId 查列表，既修回这一级，
 * 也顺带把"同一模型的原创条目和转售条目"放进同一次消歧里。
 */
object ModelCatalogMatcher {

    /** 一个候选（DAO 查回来的行，已投影成纯数据）。 */
    data class CatalogEntry(
        /** 目录主键 `providerSlug/mapKey`，最终写进 `models.catalogKey` 的就是它。 */
        val key: String,
        val vendor: String,
        val modelId: String,
        val normId: String,
        /** `vendor/裸 id`，第一级的查找键。 */
        val qualifiedId: String = "",
        /** 这条是不是厂商自己挂出来的，不是聚合站转售。见 [CatalogParser]。 */
        val canonical: Boolean = false,
        /** `lastUpdated` 的字符串形式；比较时按字典序即可（models.dev 给的是 ISO 日期）。 */
        val lastUpdated: String? = null,
    )

    /**
     * 三级匹配。
     *
     * @param modelId 要匹配的模型 id（用户录入或探测发现的）。
     * @param vendorHint 供应商名 / host 的消歧提示（可选）。
     * @param byQualifiedId 第一级：`vendor/model` 形式输入查回的候选（可能多条）。
     * @param byModelId 第二级：精确 modelId 查的结果（可能多个）。
     * @param byNormId 第三级：归一化后查的结果（可能多个）。
     * @return 命中的 [CatalogEntry]，没匹配到返回 null（详情页就只显示模型 id，不显示占位符）。
     */
    fun match(
        modelId: String,
        vendorHint: String?,
        byQualifiedId: List<CatalogEntry> = emptyList(),
        byModelId: List<CatalogEntry> = emptyList(),
        byNormId: List<CatalogEntry> = emptyList(),
    ): CatalogEntry? =
        // 含 `/` 的输入先走第一级；不含 `/` 的输入 qualifiedId 那一路压根没查（DAO 不会给），
        // 所以这里不必再判斜杠，三级依次回退就够了。
        disambiguate(byQualifiedId, vendorHint)
            ?: disambiguate(byModelId, vendorHint)
            ?: disambiguate(byNormId, vendorHint)

    /**
     * 多候选消歧，优先级：**vendorHint > canonical > lastUpdated 最新**。
     *
     * canonical 插在中间而不是最前，是为了保留"用户说这是 anthropic 的 claude"这个显式信号：
     * 提示命中哪家就用哪家，没提示才优先原创条目。
     *
     * 但少了 canonical 这一档会出一个很难看的结果：一个热门 id 在目录里有原创厂商那一条 +
     * 十几家聚合站各一条，价格互不相同，而"取 lastUpdated 最新"完全可能挑中某家转售条目——
     * 于是界面上显示的是别家的价。DAO 侧已经把 `canonical DESC` 排进候选顺序，这里才是
     * 真正做决定的地方。
     *
     * **只在"唯一原创"时直接采信**。两条都原创（两个厂商各自有一个同名不同实体的模型）时，
     * 取列表第一条等于把决定权交给 SQLite 的返回顺序；这时退回 lastUpdated，
     * 至少是个有定义、可复现的规则。
     */
    private fun disambiguate(candidates: List<CatalogEntry>, vendorHint: String?): CatalogEntry? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        if (vendorHint != null) {
            val hint = vendorHint.lowercase()
            candidates.firstOrNull { it.vendor.lowercase().contains(hint) }?.let { return it }
        }
        val canonical = candidates.filter { it.canonical }
        if (canonical.size == 1) return canonical.first()
        // 有原创条目就先在原创条目里比新旧，一个都没有才在全部候选里比。
        val pool = canonical.ifEmpty { candidates }
        return pool.maxByOrNull { it.lastUpdated ?: "" }
    }
}

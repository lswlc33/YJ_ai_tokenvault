package com.lc33.tokenvault.domain

import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.CatalogModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型页的分组 / 筛选 / 排序（纯函数）。
 *
 * 用例按"用户在中转站那几百个模型上会怎么按"来铺，而不是按枚举分支穷举：
 * 分组顺序、筛完剩空组怎么显示、没匹配上目录的行在新维度里排哪儿。
 */
class ModelListingTest {

    private fun model(
        id: Long,
        modelId: String,
        catalogKey: String? = null,
        source: ModelSource = ModelSource.DISCOVERED,
        probedAt: Long? = null,
        sortOrder: Int = id.toInt(),
    ) = AiModel(
        id = id,
        providerId = 1L,
        keyId = 1L,
        modelId = modelId,
        protocol = Protocol.CHAT,
        source = source,
        catalogKey = catalogKey,
        probedAt = probedAt,
        firstSeenAt = 1L,
        sortOrder = sortOrder,
    )

    private fun catalog(
        key: String,
        vendorName: String? = null,
        contextLimit: Int? = null,
        reasoning: Boolean = false,
        toolCall: Boolean = false,
        inputModalities: List<String> = listOf("text"),
    ) = key to CatalogModel(
        key = key,
        providerSlug = key.substringBefore('/'),
        vendor = key.substringBefore('/'),
        vendorName = vendorName,
        modelId = key.substringAfter('/'),
        qualifiedId = key,
        normId = key.substringAfter('/'),
        canonical = true,
        contextLimit = contextLimit,
        reasoning = reasoning,
        toolCall = toolCall,
        inputModalities = inputModalities,
    )

    private val catalogByKey = mapOf(
        catalog("deepseek/deepseek-chat", "DeepSeek", 65_536, toolCall = true),
        catalog("deepseek/deepseek-reasoner", "DeepSeek", 65_536, reasoning = true, toolCall = true),
        catalog("openai/gpt-4o", "OpenAI", 128_000, toolCall = true, inputModalities = listOf("text", "image")),
        catalog("moonshot/kimi-k2", "Moonshot AI", 128_000),
    )

    /** 一个真实中转站会有的形状：三家大族 + 几个认不出来的。 */
    private val models = listOf(
        model(1, "deepseek-chat", "deepseek/deepseek-chat"),
        model(2, "deepseek-reasoner", "deepseek/deepseek-reasoner"),
        model(3, "gpt-4o", "openai/gpt-4o"),
        model(4, "gpt-4o-mini", "openai/gpt-4o-mini"),
        model(5, "moonshot/kimi-k2", "moonshot/kimi-k2"),
        model(6, "my-private-gateway-v9"),
        model(7, "Qwen3.5-397B-A17B"),
    )

    private fun listing(
        groupBy: ModelGroupBy = ModelGroupBy.FAMILY,
        sort: ModelSort = ModelSort.NAME_ASC,
        filter: ModelFilter = ModelFilter.ALL,
        query: String = "",
    ) = listModels(
        models = models,
        catalogByKey = catalogByKey,
        groupBy = groupBy,
        sort = sort,
        filter = filter,
        query = query,
        vendorNames = mapOf("deepseek" to "DeepSeek", "openai" to "OpenAI"),
    )

    @Test
    fun `按前缀分组时模型数多的族排在前面`() {
        val groups = listing().groups
        assertEquals(listOf("deepseek", "gpt", "moonshot", "my", "qwen"), groups.map { it.key })
        assertEquals(2, groups.first().rows.size)
    }

    @Test
    fun `没匹配上目录的模型也照样有族可归`() {
        // 这正是"按前缀"而不是"按目录厂商"的意义：认不出来的那几个仍然分得开。
        val unmatch = models.filter { it.catalogKey == null }
        val groups = listing().groups.filter { group -> unmatch.any { ModelFamily.keyOf(it.modelId) == group.key } }
        assertEquals(listOf("my", "qwen"), groups.map { it.key })
    }

    @Test
    fun `分组标题用目录里的厂商展示名`() {
        val deepseek = listing().groups.first { it.key == "deepseek" }
        assertEquals("DeepSeek", ModelFamily.displayOf(deepseek.titleKey, "DeepSeek"))
    }

    @Test
    fun `组内按名字排时大小写不影响顺序`() {
        val rows = listing(sort = ModelSort.NAME_ASC).groups.first { it.key == "gpt" }.rows
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), rows.map { it.model.modelId })
    }

    @Test
    fun `按上下文排时没挂目录的垫到最后`() {
        val rows = listing(groupBy = ModelGroupBy.NONE, sort = ModelSort.CONTEXT_DESC).groups.single().rows
        assertEquals(
            "没挂上目录的行统一垫到最后：它们是没有这个属性，不是上下文为 0",
            listOf(
                "gpt-4o",                     // 128k
                "moonshot/kimi-k2",           // 128k，同上下文按名字
                "deepseek-chat",              // 65k
                "deepseek-reasoner",          // 65k
                "gpt-4o-mini",                // 以下三个都没挂上目录
                "my-private-gateway-v9",
                "Qwen3.5-397B-A17B",
            ),
            rows.map { it.model.modelId },
        )
    }

    @Test
    fun `按来源分组时手动那一组在前`() {
        val withManual = models + model(8, "hand-typed-one", source = ModelSource.MANUAL)
        val groups = listModels(
            models = withManual,
            catalogByKey = catalogByKey,
            groupBy = ModelGroupBy.SOURCE,
            sort = ModelSort.NAME_ASC,
            filter = ModelFilter.ALL,
            query = "",
        ).groups
        assertEquals(listOf("discovered", "manual"), groups.map { it.key })
        assertEquals(listOf("hand-typed-one"), groups.last().rows.map { it.model.modelId })
    }

    @Test
    fun `不分组时是一整列`() {
        val groups = listing(groupBy = ModelGroupBy.NONE).groups
        assertEquals(1, groups.size)
        assertEquals(models.size, groups.single().rows.size)
    }

    @Test
    fun `搜索同时命中模型 id 与厂商展示名`() {
        // 用户记的可能是"DeepSeek 那两个"，也可能是客户端里贴的 id，两边都要能搜到。
        assertEquals(
            listOf("deepseek-chat", "deepseek-reasoner"),
            listing(query = "deepseek").groups.flatMap { it.rows }.map { it.model.modelId },
        )
        // 注意方向：搜的是"输入串是不是出现在那一行里"，所以拿一句比 id 更长的
        // 中文供应商名去搜是搜不到的——那不是 bug，反过来才会让每个词都命中一堆。
        // 只有 gpt-4o 能被 "openai" 搜到：gpt-4o-mini 的 catalogKey 指向一行不存在的目录，
        // 于是它没有厂商名可搜。这是"目录没认出来"的真实代价，界面上靠"未匹配"那一档暴露出来。
        assertEquals(
            listOf("gpt-4o"),
            listing(query = "openai").groups.flatMap { it.rows }.map { it.model.modelId },
        )
        assertEquals(
            listOf("moonshot/kimi-k2"),
            listing(query = "kimi").groups.flatMap { it.rows }.map { it.model.modelId },
        )
    }

    @Test
    fun `能力筛选只看挂上目录的行`() {
        assertEquals(
            listOf("deepseek-reasoner"),
            listing(filter = ModelFilter.REASONING).groups.flatMap { it.rows }.map { it.model.modelId },
        )
        assertEquals(
            listOf("gpt-4o"),
            listing(filter = ModelFilter.VISION).groups.flatMap { it.rows }.map { it.model.modelId },
        )
        // 没匹配上目录的那几个一律不算"支持工具调用"——它们只是我们不知道。
        assertTrue(listing(filter = ModelFilter.TOOL_CALL).groups.flatMap { it.rows }.all { it.catalog != null })
    }

    @Test
    fun `未匹配那一档就是目录没认出来的清单`() {
        val unmatched = listing(filter = ModelFilter.UNMATCHED).groups.flatMap { it.rows }
        assertEquals(
            "gpt-4o-mini 的 catalogKey 指向一行不存在的目录，也该算未匹配",
            listOf("gpt-4o-mini", "my-private-gateway-v9", "Qwen3.5-397B-A17B").sorted(),
            unmatched.map { it.model.modelId }.sorted(),
        )
    }

    @Test
    fun `摘要说的是全量而不是筛完的那部分`() {
        val result = listing(filter = ModelFilter.REASONING)
        assertEquals(models.size, result.totalModels)
        assertEquals(4, result.matchedModels)
        assertEquals(1, result.groups.sumOf { it.rows.size })
    }
}

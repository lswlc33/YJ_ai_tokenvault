package com.lc33.tokenvault.catalog

import com.lc33.tokenvault.catalog.ModelCatalogMatcher.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模型元数据匹配（§10）纯函数测试（测试 13）。
 *
 * 覆盖：归一化规则、三级匹配（qualifiedId、精确 modelId、normId）、
 * vendorHint 消歧、canonical（原创条目优先于聚合站转售）与 lastUpdated 兜底。
 */
class ModelCatalogMatcherTest {

    private fun entry(
        key: String = "anthropic/claude-opus-4",
        vendor: String = "anthropic",
        modelId: String = "claude-opus-4",
        normId: String = "claude-opus-4",
        qualifiedId: String = "anthropic/claude-opus-4",
        canonical: Boolean = vendor == key.substringBefore('/'),
        lastUpdated: String? = "2026-01-01",
    ) = CatalogEntry(
        key = key,
        vendor = vendor,
        modelId = modelId,
        normId = normId,
        qualifiedId = qualifiedId,
        canonical = canonical,
        lastUpdated = lastUpdated,
    )

    // ------------------------------------------------------------------ 归一化

    @Test
    fun `归一化去掉 latest 与日期后缀`() {
        assertEquals("claude-opus-4", CatalogNormalize.normalize("claude-opus-4-latest"))
        assertEquals("claude-opus-4", CatalogNormalize.normalize("claude-opus-4-20250101"))
        assertEquals("claude-opus-4", CatalogNormalize.normalize("claude-opus-4-2025-01-01"))
    }

    @Test
    fun `归一化去掉能力后缀并归一点与下划线`() {
        assertEquals("claude-opus-4", CatalogNormalize.normalize("claude-opus-4:free"))
        assertEquals("claude-opus-4", CatalogNormalize.normalize("claude.opus.4"))
        assertEquals("claude-opus-4", CatalogNormalize.normalize("claude_opus_4"))
    }

    @Test
    fun `归一化转小写`() {
        assertEquals("claude-opus-4", CatalogNormalize.normalize("Claude-Opus-4"))
    }

    @Test
    fun `归一化剥离复合后缀到不动点`() {
        // 日期与能力两类正则都锚在行尾，一次只能剥掉最末尾那一层。
        // `gpt-4o-2024-08-06:free` 必须先去掉 `:free` 才轮得到日期，否则结果停在
        // `gpt-4o-2024-08-06`，与目录里的 `gpt-4o` 对不上，第三级归一化匹配整段失效。
        assertEquals("gpt-4o", CatalogNormalize.normalize("gpt-4o-2024-08-06:free"))
        assertEquals("gpt-4o", CatalogNormalize.normalize("gpt-4o-20240806:thinking"))
        assertEquals("gpt-4o", CatalogNormalize.normalize("gpt-4o:free-latest"))
        assertEquals("gpt-4o", CatalogNormalize.normalize("gpt-4o-2024-08-06:free:beta"))
        // 每一轮只会让串变短，所以循环必然收敛；不在表里的中段后缀不受影响。
        assertEquals("gpt-4o-mini", CatalogNormalize.normalize("gpt-4o-mini-2024-07-18"))
        assertEquals("gpt-4o:mini", CatalogNormalize.normalize("gpt-4o:mini"))
        assertEquals("o3", CatalogNormalize.normalize("o3"))
    }

    @Test
    fun `归一化去掉六位上线日与批处理后缀`() {
        // 真机实测补的两条：火山的 doubao 全是 6 位 YYMMDD 后缀（133 个只认得出 28 个），
        // openrouter 有几十个 `:batch` 档位。
        assertEquals("doubao-lite-128k", CatalogNormalize.normalize("doubao-lite-128k-240428"))
        assertEquals("doubao-pro-4k", CatalogNormalize.normalize("doubao-pro-4k-240515"))
        assertEquals("gpt-6-astra", CatalogNormalize.normalize("gpt-6-astra:batch"))
        // 8 位在前：`20250101` 不能被 6 位分支先咬掉一半。
        assertEquals("claude-opus-4", CatalogNormalize.normalize("claude-opus-4-20250101"))
    }

    // ------------------------------------------------------------------ 三级匹配

    @Test
    fun `含斜杠的输入按 qualifiedId 命中`() {
        val hit = ModelCatalogMatcher.match(
            modelId = "anthropic/claude-opus-4",
            vendorHint = null,
            byQualifiedId = listOf(entry()),
        )
        assertEquals("anthropic/claude-opus-4", hit?.key)
    }

    @Test
    fun `含斜杠的输入在主键对不上时仍能从精确 modelId 命中`() {
        // 聚合站条目的主键是 `tokengo/deepseek/deepseek-chat`，用户手里的
        // `deepseek/deepseek-chat` 比不上主键；老实现在这一步直接 return null，
        // 于是所有带厂商前缀的模型 id 一级匹配整段失效。
        val resale = entry(
            key = "tokengo/deepseek/deepseek-chat",
            vendor = "deepseek",
            modelId = "deepseek/deepseek-chat",
            normId = "deepseek-chat",
            qualifiedId = "deepseek/deepseek-chat",
            canonical = false,
        )
        val hit = ModelCatalogMatcher.match(
            modelId = "deepseek/deepseek-chat",
            vendorHint = null,
            byQualifiedId = listOf(resale),
        )
        assertEquals("tokengo/deepseek/deepseek-chat", hit?.key)
    }

    @Test
    fun `同一条模型有原创和转售两条时取原创那条`() {
        // 转售那条 last_updated 更新。没有 canonical 这一档时它会赢，
        // 于是详情页显示的是聚合站的转售价，不是厂方价。
        val official = entry(
            key = "deepseek/deepseek-chat",
            vendor = "deepseek",
            modelId = "deepseek-chat",
            normId = "deepseek-chat",
            qualifiedId = "deepseek/deepseek-chat",
            canonical = true,
            lastUpdated = "2026-01-01",
        )
        val resale = entry(
            key = "tokengo/deepseek/deepseek-chat",
            vendor = "deepseek",
            modelId = "deepseek/deepseek-chat",
            normId = "deepseek-chat",
            qualifiedId = "deepseek/deepseek-chat",
            canonical = false,
            lastUpdated = "2026-06-01",
        )
        val hit = ModelCatalogMatcher.match(
            modelId = "deepseek-chat",
            vendorHint = null,
            byModelId = listOf(resale, official),
        )
        assertEquals("deepseek/deepseek-chat", hit?.key)
    }

    @Test
    fun `两条都原创时退回新旧而不是列表顺序`() {
        // 两个厂商各有一个同名不同实体的模型，没有哪条"更原创"。这时取第一条
        // 等于让 SQLite 的返回顺序决定显示谁的价格，所以退回 lastUpdated。
        val older = entry(key = "a/x", vendor = "a", modelId = "x", normId = "x", lastUpdated = "2026-01-01")
        val newer = entry(key = "b/x", vendor = "b", modelId = "x", normId = "x", lastUpdated = "2026-06-01")
        val hit = ModelCatalogMatcher.match(
            modelId = "x",
            vendorHint = null,
            byModelId = listOf(older, newer),
        )
        assertEquals("b/x", hit?.key)
        // 反过来传也不该改变结果——这一条守的就是"不依赖顺序"。
        assertEquals(
            "b/x",
            ModelCatalogMatcher.match(
                modelId = "x",
                vendorHint = null,
                byModelId = listOf(newer, older),
            )?.key,
        )
    }

    @Test
    fun `精确 modelId 唯一命中`() {
        val hit = ModelCatalogMatcher.match(
            modelId = "claude-opus-4",
            vendorHint = null,
            byModelId = listOf(entry()),
        )
        assertEquals("claude-opus-4", hit?.modelId)
    }

    @Test
    fun `多厂商同名用 vendorHint 消歧`() {
        val openai = entry(key = "openai/claude-opus-4", vendor = "openai", lastUpdated = "2026-01-01")
        val anthropic = entry(key = "anthropic/claude-opus-4", vendor = "anthropic", lastUpdated = "2026-01-01")
        val hit = ModelCatalogMatcher.match(
            modelId = "claude-opus-4",
            vendorHint = "anthropic",
            byModelId = listOf(openai, anthropic),
        )
        assertEquals("anthropic/claude-opus-4", hit?.key)
    }

    @Test
    fun `vendorHint 优先于原创条目`() {
        // 用户明说这把 Key 是某家给的，就按他说的那家显示，即使另一家才是原创。
        val openai = entry(key = "openai/x", vendor = "openai", modelId = "x", normId = "x")
        val anthropic = entry(
            key = "thirdparty/claude-x",
            vendor = "thirdparty",
            modelId = "x",
            normId = "x",
            qualifiedId = "thirdparty/x",
            canonical = true,
        )
        val hit = ModelCatalogMatcher.match(
            modelId = "x",
            vendorHint = "openai",
            byModelId = listOf(anthropic, openai),
        )
        assertEquals("openai/x", hit?.key)
    }

    @Test
    fun `多厂商同名无 hint 取 lastUpdated 最新`() {
        val older = entry(key = "a/x", vendor = "a", modelId = "x", normId = "x", canonical = false, lastUpdated = "2026-01-01")
        val newer = entry(key = "b/x", vendor = "b", modelId = "x", normId = "x", canonical = false, lastUpdated = "2026-06-01")
        val hit = ModelCatalogMatcher.match(
            modelId = "x",
            vendorHint = null,
            byModelId = listOf(older, newer),
        )
        assertEquals("b/x", hit?.key)
    }

    @Test
    fun `精确 miss 后走归一化`() {
        val hit = ModelCatalogMatcher.match(
            modelId = "claude-opus-4-latest",
            vendorHint = null,
            byNormId = listOf(entry(normId = "claude-opus-4")),
        )
        assertEquals("claude-opus-4", hit?.modelId)
    }

    @Test
    fun `第一级命中时不再回退`() {
        // 三级依次回退，但如果第一级已经有唯一候选，就不该被第三级里那条"更晚更新"的顶掉。
        val qualified = entry(key = "anthropic/claude-opus-4", qualifiedId = "anthropic/claude-opus-4")
        val other = entry(key = "zzz/claude-opus-4", vendor = "zzz", qualifiedId = "zzz/claude-opus-4", lastUpdated = "2099-01-01")
        val hit = ModelCatalogMatcher.match(
            modelId = "anthropic/claude-opus-4",
            vendorHint = null,
            byQualifiedId = listOf(qualified),
            byNormId = listOf(other),
        )
        assertEquals("anthropic/claude-opus-4", hit?.key)
    }

    @Test
    fun `三级全空返回 null`() {
        val hit = ModelCatalogMatcher.match(
            modelId = "unknown-model",
            vendorHint = null,
        )
        assertNull(hit)
    }
}

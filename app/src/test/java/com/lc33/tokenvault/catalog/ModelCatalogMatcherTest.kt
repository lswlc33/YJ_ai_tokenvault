package com.lc33.tokenvault.catalog

import com.lc33.tokenvault.catalog.ModelCatalogMatcher.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模型元数据匹配（§10）纯函数测试（测试 13）。
 *
 * 覆盖：归一化规则、三级匹配（含 `/` 主键、精确 modelId、normId）、vendorHint 消歧、
 * lastUpdated 最新兜底。
 */
class ModelCatalogMatcherTest {

    private fun entry(
        key: String = "anthropic/claude-opus-4",
        vendor: String = "anthropic",
        modelId: String = "claude-opus-4",
        normId: String = "claude-opus-4",
        lastUpdated: String? = "2026-01-01",
    ) = CatalogEntry(key, vendor, modelId, normId, lastUpdated)

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

    // ------------------------------------------------------------------ 三级匹配

    @Test
    fun `含斜杠的主键直接命中`() {
        val hit = ModelCatalogMatcher.match(
            modelId = "anthropic/claude-opus-4",
            vendorHint = null,
            byKey = entry(),
            byModelId = emptyList(),
            byNormId = emptyList(),
        )
        assertEquals("anthropic/claude-opus-4", hit?.key)
    }

    @Test
    fun `精确 modelId 唯一命中`() {
        val hit = ModelCatalogMatcher.match(
            modelId = "claude-opus-4",
            vendorHint = null,
            byKey = null,
            byModelId = listOf(entry()),
            byNormId = emptyList(),
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
            byKey = null,
            byModelId = listOf(openai, anthropic),
            byNormId = emptyList(),
        )
        assertEquals("anthropic/claude-opus-4", hit?.key)
    }

    @Test
    fun `多厂商同名无 hint 取 lastUpdated 最新`() {
        val older = entry(key = "a/x", vendor = "a", lastUpdated = "2026-01-01")
        val newer = entry(key = "b/x", vendor = "b", lastUpdated = "2026-06-01")
        val hit = ModelCatalogMatcher.match(
            modelId = "x",
            vendorHint = null,
            byKey = null,
            byModelId = listOf(older, newer),
            byNormId = emptyList(),
        )
        assertEquals("b/x", hit?.key)
    }

    @Test
    fun `精确 miss 后走归一化`() {
        val hit = ModelCatalogMatcher.match(
            modelId = "claude-opus-4-latest",
            vendorHint = null,
            byKey = null,
            byModelId = emptyList(),
            byNormId = listOf(entry(normId = "claude-opus-4")),
        )
        assertEquals("claude-opus-4", hit?.modelId)
    }

    @Test
    fun `三级全空返回 null`() {
        val hit = ModelCatalogMatcher.match(
            modelId = "unknown-model",
            vendorHint = null,
            byKey = null,
            byModelId = emptyList(),
            byNormId = emptyList(),
        )
        assertNull(hit)
    }
}

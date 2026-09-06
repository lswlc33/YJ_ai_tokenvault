package com.lc33.tokenvault.data.seed

import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.data.mapper.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置预设种子数据的完整性（计划.md §8.2 那张表）。
 *
 * 只验数据本身：8 个 builtinKey 齐全且唯一、`default` 的 UA 用占位符而不是硬编码版本号、
 * `claude_code` 带着 M0.5 实测过的特征头、`zcode` 留空位。
 * 种入的幂等逻辑在 [com.lc33.tokenvault.data.dao.ClientProfileDao.seedBuiltin]，由 Room
 * 的 `@Transaction` 默认方法真正执行，那里是"找→插/更"三条分支，不在纯 JVM 上单独测。
 */
class BuiltinPresetsTest {

    @Test
    fun `内置预设共 8 个且 builtinKey 唯一`() {
        val presets = BuiltinPresets.BUILTIN_PRESETS
        assertEquals(8, presets.size)
        val keys = presets.map { it.builtinKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `包含计划里的全部 8 个 builtinKey`() {
        val keys = BuiltinPresets.BUILTIN_PRESETS.mapNotNull { it.builtinKey }.toSet()
        assertEquals(
            setOf(
                "default",
                "claude_code",
                "codex_cli",
                "anthropic_sdk",
                "openai_sdk",
                "gemini_cli",
                "cherry_studio",
                "zcode",
            ),
            keys,
        )
    }

    @Test
    fun `default 的 UA 用占位符而不是硬编码版本号`() {
        val default = BuiltinPresets.BUILTIN_PRESETS.first { it.builtinKey == "default" }
        assertTrue(default.userAgent.contains("{app_version}"))
        assertTrue(default.userAgent.contains("{android_release}"))
        assertTrue(default.userAgent.contains("{arch}"))
    }

    @Test
    fun `claude_code 带着 M0点5 实测过的特征头`() {
        val claude = BuiltinPresets.BUILTIN_PRESETS.first { it.builtinKey == "claude_code" }
        val headers = claude.headers.toMap()
        assertEquals("cli", headers["x-app"])
        assertTrue(headers["anthropic-beta"]!!.contains("claude-code-20250219"))
        assertEquals("js", headers["x-stainless-lang"])
        assertEquals("0.60.0", headers["x-stainless-package-version"])
    }

    @Test
    fun `zcode 留空位且标记为无指纹`() {
        val zcode = BuiltinPresets.BUILTIN_PRESETS.first { it.builtinKey == "zcode" }
        assertTrue(zcode.userAgent.isEmpty())
        assertTrue(zcode.headers.isEmpty())
        assertTrue(zcode.protocols.isEmpty())
    }

    @Test
    fun `实体往返不丢字段`() {
        // headers 是 List<Pair>，走 JSON 数组编码再读回必须一致（顺序也是数据的一部分）。
        val claude = BuiltinPresets.BUILTIN_PRESETS.first { it.builtinKey == "claude_code" }
        val roundTripped = claude.toEntity().toDomain()
        assertEquals(claude.name, roundTripped.name)
        assertEquals(claude.builtinKey, roundTripped.builtinKey)
        assertEquals(claude.userAgent, roundTripped.userAgent)
        assertEquals(claude.headers, roundTripped.headers)
        assertEquals(claude.protocols, roundTripped.protocols)
        assertEquals(claude.bodyPatch, roundTripped.bodyPatch)
        assertEquals(claude.builtinRev, roundTripped.builtinRev)
    }
}

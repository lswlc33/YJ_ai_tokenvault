package com.lc33.tokenvault.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查的纯函数测试（计划.md §13.4「更新」）。
 *
 * 覆盖：GitHub Releases 响应解析（含未知字段忽略、坏 JSON 容错）、按渠道匹配
 * （正式版剥 `v` 前缀比语义化版本、nightly 固定 tag 恒有构建）、版本号比较边界。
 */
class ReleaseParserMatcherTest {

    // ------------------------------------------------------------------ 解析

    @Test
    fun `解析正式版与 nightly 两条 release`() {
        val body = """
            [
              {"tag_name":"v0.2.0","name":"0.2.0","body":"changelog","published_at":"2026-09-01T00:00:00Z","prerelease":false,"html_url":"https://github.com/lswlc33/YJ_ai_tokenvault/releases/tag/v0.2.0"},
              {"tag_name":"nightly-build","prerelease":true,"html_url":"https://github.com/lswlc33/YJ_ai_tokenvault/releases/tag/nightly-build"}
            ]
        """.trimIndent()

        val releases = ReleaseParser.parse(body)

        assertEquals(2, releases.size)
        assertEquals("v0.2.0", releases[0].tagName)
        assertFalse(releases[0].prerelease)
        assertEquals("0.2.0", releases[0].name)
        assertEquals("changelog", releases[0].body)
        assertEquals("2026-09-01T00:00:00Z", releases[0].publishedAt)
        assertTrue(releases[1].prerelease)
        assertEquals("nightly-build", releases[1].tagName)
    }

    @Test
    fun `解析忽略未知字段`() {
        val body = """[{"tag_name":"v0.1.0","assets":[],"author":{},"reactions":{}}]"""
        val releases = ReleaseParser.parse(body)
        assertEquals(1, releases.size)
        assertEquals("v0.1.0", releases[0].tagName)
    }

    @Test
    fun `坏 JSON 返回空列表而非抛异常`() {
        assertEquals(emptyList<ReleaseInfo>(), ReleaseParser.parse("not json"))
        assertEquals(emptyList<ReleaseInfo>(), ReleaseParser.parse(""))
        assertEquals(emptyList<ReleaseInfo>(), ReleaseParser.parse("""{"tag_name":"v0.1.0"}"""))
    }

    // ------------------------------------------------------------------ 匹配

    @Test
    fun `正式版渠道只看非预发布且比语义化版本`() {
        val releases = listOf(
            ReleaseInfo(tagName = "nightly-build", prerelease = true),
            ReleaseInfo(tagName = "v0.2.0", prerelease = false),
            ReleaseInfo(tagName = "v0.1.0", prerelease = false),
        )

        // 当前 0.1.0，最新正式版 v0.2.0 → 有更新
        val match = ReleaseMatcher.match(releases, "0.1.0", channel = 0)!!
        assertEquals("v0.2.0", match.latest.tagName)
        assertTrue(match.newer)
    }

    @Test
    fun `正式版渠道版本不新时 newer 为假`() {
        val releases = listOf(
            ReleaseInfo(tagName = "v0.1.0", prerelease = false),
        )
        val match = ReleaseMatcher.match(releases, "0.2.0", channel = 0)!!
        assertEquals("v0.1.0", match.latest.tagName)
        assertFalse(match.newer)
    }

    @Test
    fun `正式版渠道无任何正式 release 返回 null`() {
        val releases = listOf(
            ReleaseInfo(tagName = "nightly-build", prerelease = true),
        )
        assertNull(ReleaseMatcher.match(releases, "0.1.0", channel = 0))
    }

    @Test
    fun `nightly 渠道匹配固定 tag 且恒视为有构建`() {
        val releases = listOf(
            ReleaseInfo(tagName = "nightly-build", prerelease = true),
            ReleaseInfo(tagName = "v0.2.0", prerelease = false),
        )
        val match = ReleaseMatcher.match(releases, "0.1.0", channel = 1)!!
        assertEquals("nightly-build", match.latest.tagName)
        assertTrue(match.newer)  // nightly 固定 tag，无版本可比，恒视为有可下载构建
    }

    @Test
    fun `nightly 渠道无 nightly 返回 null`() {
        val releases = listOf(
            ReleaseInfo(tagName = "v0.2.0", prerelease = false),
        )
        assertNull(ReleaseMatcher.match(releases, "0.1.0", channel = 1))
    }

    // ------------------------------------------------------------------ 版本比较

    @Test
    fun `语义化版本比较`() {
        assertEquals(1, ReleaseMatcher.compareVersion("0.2.0", "0.1.0"))
        assertEquals(-1, ReleaseMatcher.compareVersion("0.1.0", "0.2.0"))
        assertEquals(0, ReleaseMatcher.compareVersion("0.1.0", "0.1.0"))
        assertEquals(1, ReleaseMatcher.compareVersion("1.0.0", "0.9.9"))
        assertEquals(1, ReleaseMatcher.compareVersion("0.1.1", "0.1.0"))
    }

    @Test
    fun `版本号从 tag 剥掉 v 前缀`() {
        assertEquals("0.1.0", ReleaseMatcher.versionNameFromTag("v0.1.0"))
        assertNull(ReleaseMatcher.versionNameFromTag("0.1.0"))  // 无 v 前缀，非约定格式
        assertNull(ReleaseMatcher.versionNameFromTag("nightly-build"))
    }
}

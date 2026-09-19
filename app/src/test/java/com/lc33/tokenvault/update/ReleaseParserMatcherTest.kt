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
 * （正式版剥 `v` 前缀比语义化版本、nightly 按 `nightly` 前缀 + prerelease 取最新一条）、
 * 版本号比较边界。
 */
class ReleaseParserMatcherTest {

    // ------------------------------------------------------------------ 解析

    @Test
    fun `解析正式版与 nightly 两条 release`() {
        val body = """
            [
              {"tag_name":"v0.2.0","name":"0.2.0","body":"changelog","published_at":"2026-09-01T00:00:00Z","prerelease":false,"html_url":"https://github.com/lswlc33/YJ_ai_tokenvault/releases/tag/v0.2.0"},
              {"tag_name":"nightly-20260918-030705","prerelease":true,"published_at":"2026-09-18T03:07:05Z","html_url":"https://github.com/lswlc33/YJ_ai_tokenvault/releases/tag/nightly-20260918-030705"}
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
        assertEquals("nightly-20260918-030705", releases[1].tagName)
        assertEquals("2026-09-18T03:07:05Z", releases[1].publishedAt)
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
    fun `正式版渠道取语义化最大那条而不是 API 顺序第一条`() {
        // Releases API 的顺序只是创建时间倒序：先发了 v0.2.0，再从 hotfix 分支补一个
        // v0.1.1，列表就是"小的在前"。旧写法 firstOrNull 会把它当最新，用户装着 0.2.0
        // 却看到"已经是最新"，而真正该展示的其实是 v0.3.0。
        val releases = listOf(
            ReleaseInfo(tagName = "v0.1.1", prerelease = false),
            ReleaseInfo(tagName = "v0.3.0", prerelease = false),
            ReleaseInfo(tagName = "v0.2.0", prerelease = false),
        )
        val match = ReleaseMatcher.match(releases, "0.1.0", channel = 0)!!
        assertEquals("v0.3.0", match.latest.tagName)
        assertTrue(match.newer)
    }

    @Test
    fun `正式版渠道版本号认不出的那条不参与竞争`() {
        // 无 `v` 前缀 = 非约定格式，不能因为它排在前面就遮住真正的正式版。
        val releases = listOf(
            ReleaseInfo(tagName = "release-2026", prerelease = false),
            ReleaseInfo(tagName = "v0.2.0", prerelease = false),
        )
        val match = ReleaseMatcher.match(releases, "0.1.0", channel = 0)!!
        assertEquals("v0.2.0", match.latest.tagName)
        assertTrue(match.newer)
    }

    @Test
    fun `预发布同号时正式版才算最新`() {
        // §13.4：装着 1.0.0 正式版的用户不能被提示"1.0.0-beta 就是最新"。
        val releases = listOf(
            ReleaseInfo(tagName = "v1.0.0", prerelease = false),
            ReleaseInfo(tagName = "v1.0.0-beta", prerelease = false),
        )
        val match = ReleaseMatcher.match(releases, "1.0.0", channel = 0)!!
        assertEquals("v1.0.0", match.latest.tagName)
        assertFalse(match.newer)
    }

    @Test
    fun `nightly 渠道匹配时间戳 tag 且恒视为有构建`() {
        val releases = listOf(
            ReleaseInfo(tagName = "nightly-20260918-030705", prerelease = true),
            ReleaseInfo(tagName = "v0.2.0", prerelease = false),
        )
        val match = ReleaseMatcher.match(releases, "0.1.0", channel = 1)!!
        assertEquals("nightly-20260918-030705", match.latest.tagName)
        assertTrue(match.newer)  // nightly 无版本可比，恒视为有可下载构建
    }

    @Test
    fun `nightly 渠道兼容旧的固定 tag`() {
        val releases = listOf(
            ReleaseInfo(tagName = "nightly-build", prerelease = true),
        )
        val match = ReleaseMatcher.match(releases, "0.1.0", channel = 1)!!
        assertEquals("nightly-build", match.latest.tagName)
    }

    @Test
    fun `nightly 渠道取列表里最新一条而非最后一条`() {
        // Releases API 按创建时间倒序返回，firstOrNull 命中即最新。
        val releases = listOf(
            ReleaseInfo(tagName = "nightly-20260918-030705", prerelease = true),
            ReleaseInfo(tagName = "nightly-20260917-010203", prerelease = true),
        )
        val match = ReleaseMatcher.match(releases, "0.1.0", channel = 1)!!
        assertEquals("nightly-20260918-030705", match.latest.tagName)
    }

    @Test
    fun `nightly 渠道无 nightly 返回 null`() {
        val releases = listOf(
            ReleaseInfo(tagName = "v0.2.0", prerelease = false),
            // 版本化预发布不是 nightly：只按 tag 前缀排除，prerelease 挡不住拼写巧合。
            ReleaseInfo(tagName = "v0.2.0-alpha", prerelease = true),
            // nightly 前缀但非预发布：不属于 CI 产物形态，不认。
            ReleaseInfo(tagName = "nightly-20260918-030705", prerelease = false),
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
    fun `预发布后缀使版本小于同号正式版`() {
        // 旧实现把 `-` 也当段分隔符、非数字段记 0，于是 `1.0.0-beta` 与 `1.0.0` **判等**：
        // 装着正式版的用户会看到"预发布就是最新"。
        assertEquals(-1, ReleaseMatcher.compareVersion("1.0.0-beta", "1.0.0"))
        assertEquals(1, ReleaseMatcher.compareVersion("1.0.0", "1.0.0-beta"))
        assertEquals(0, ReleaseMatcher.compareVersion("1.0.0", "1.0.0+build7"))
        assertEquals(-1, ReleaseMatcher.compareVersion("1.0.0-rc.1", "1.0.0"))
        // 两个预发布之间按 semver §11.4：数字标识符按数值比，且小于任何非数字标识符
        assertEquals(-1, ReleaseMatcher.compareVersion("1.0.0-rc.2", "1.0.0-rc.10"))
        assertEquals(-1, ReleaseMatcher.compareVersion("1.0.0-1", "1.0.0-alpha"))
        // 前面标识符全相等时，**标识符多的一方更大**（semver §11.4）：`rc` < `rc.1`
        assertEquals(-1, ReleaseMatcher.compareVersion("1.0.0-rc", "1.0.0-rc.1"))
    }

    @Test
    fun `段数少的版本号算更小且坏段不抛异常`() {
        assertEquals(-1, ReleaseMatcher.compareVersion("1.0", "1.0.1"))
        assertEquals(-1, ReleaseMatcher.compareVersion("1.0", "1.0.0"))
        // 一个坏 tag 不该让整次比较抛异常（记 0 继续比）
        assertEquals(0, ReleaseMatcher.compareVersion("x.y.z", "0.0.0"))
        assertEquals(1, ReleaseMatcher.compareVersion("1.0.0", "beta"))
    }

    @Test
    fun `版本号从 tag 剥掉 v 前缀`() {
        assertEquals("0.1.0", ReleaseMatcher.versionNameFromTag("v0.1.0"))
        assertNull(ReleaseMatcher.versionNameFromTag("0.1.0"))  // 无 v 前缀，非约定格式
        assertNull(ReleaseMatcher.versionNameFromTag("nightly-build"))
    }
}

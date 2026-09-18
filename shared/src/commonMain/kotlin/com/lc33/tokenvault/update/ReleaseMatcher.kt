package com.lc33.tokenvault.update

/**
 * 按渠道从 release 列表里挑「最新一条」并判断是否比当前新（计划.md §13.4「更新」）。
 *
 * 纯函数、零平台依赖。两个渠道的语义不同，见 [match]。
 */
object ReleaseMatcher {

    /** nightly tag 前缀。CI 实际打的是秒级时间戳 tag（每次构建唯一，见 ci.yml），
     *  旧约定的固定 `nightly-build` 也以同一前缀命中。 */
    const val NIGHTLY_TAG_PREFIX = "nightly"

    /**
     * 挑出「该渠道下应展示的那一条」，并给出与当前版本的比较结论。
     *
     * 正式版渠道：只看 `prerelease == false` 的 release，从 tag 里剥掉 `v` 前缀取出
     *   versionName（release.yml 约定 tag 与 vaultVersionName 一致），做语义化版本比较。
     *   `currentVersionName` 形如 `0.1.0`，tag 形如 `v0.1.0`。
     *
     * nightly 渠道：tag 以 [NIGHTLY_TAG_PREFIX] 开头且 `prerelease == true`，取最新一条
     *   （Releases API 按创建时间倒序返回，firstOrNull 即最新）。不随版本变化，没有
     *   「比当前新」的语义。所以 nightly 的检查结论就是「有没有可下载的 nightly」——
     *   有就展示其发布时间，让用户自行判断要不要下（nightly 本身就不是 release-tested）。
     *   仍要求 prerelease 是为排除 `v0.2.0-alpha` 这类版本化预发布：它不是 nightly，
     *   被当成 nightly 展示会把用户引向一条没有时效性的旧版。
     *
     * @param releases 已解析的 release 列表（保持 API 顺序，最新在前）。
     * @param currentVersionName 本地版本名（`BuildConfig.VERSION_NAME`）。
     * @param channel 渠道下标，与 `update_channels` 数组对齐：0=正式版、1=nightly。
     * @return 匹配结果。null 表示该渠道下没有任何 release。
     */
    fun match(
        releases: List<ReleaseInfo>,
        currentVersionName: String,
        channel: Int,
    ): UpdateMatch? {
        if (channel == NIGHTLY_CHANNEL) {
            val nightly = releases.firstOrNull {
                it.prerelease && it.tagName.startsWith(NIGHTLY_TAG_PREFIX)
            } ?: return null
            // nightly 无版本可比，恒视为「有可下载的构建」，交由 UI 展示时间。
            return UpdateMatch(latest = nightly, newer = true)
        }

        // 正式版渠道：取 prerelease=false 的最新一条。
        val stable = releases.firstOrNull { !it.prerelease } ?: return null
        val latestVersion = versionNameFromTag(stable.tagName)
        return UpdateMatch(
            latest = stable,
            newer = latestVersion != null && compareVersion(latestVersion, currentVersionName) > 0,
        )
    }

    /** 从 `v0.1.0` 剥出 `0.1.0`。不以 `v` 开头 / 空串返回 null（无法比较，视为非更新）。 */
    fun versionNameFromTag(tag: String): String? {
        val name = tag.removePrefix("v")
        return name.takeIf { it.isNotBlank() && it != tag }
    }

    /**
     * 语义化版本比较：`1` 表示 a 更新，`-1` 表示 b 更新，`0` 相同。
     *
     * 只比 `major.minor.patch` 三段数字（`0.1.0`），忽略预发布后缀（`-beta`）与构建号
     * （`+1`）——正式版 tag 不带这些。任何一段不是数字时按 0 处理，避免解析失败导致
     * 整个比较抛异常。
     */
    fun compareVersion(a: String, b: String): Int {
        val pa = numericParts(a)
        val pb = numericParts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /** 把 `0.1.0` 拆成 `[0, 1, 0]`。非数字段落 0。 */
    private fun numericParts(version: String): List<Int> =
        version.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }

    private const val NIGHTLY_CHANNEL = 1
}

/** 更新检查的匹配结论：该渠道下最新一条 release，以及它是否比当前版本新。 */
data class UpdateMatch(
    val latest: ReleaseInfo,
    val newer: Boolean,
)

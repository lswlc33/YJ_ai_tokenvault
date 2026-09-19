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
     *   versionName（release.yml 约定 tag 与 vaultVersionName 一致），做语义化版本比较，
     *   取**语义化最大**的那一条。
     *   旧写法是 `firstOrNull { !it.prerelease }`，等于信"Releases API 一定按版本倒序"——
     *   而那个顺序只是创建时间倒序：先建了 `v0.2.0` 再补一个 hotfix 分支的 `v0.1.1`，
     *   或者手工重排过 release，都会让用户看到"已经是最新"。
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

        // 正式版渠道：取 prerelease=false 里语义化版本最大的一条。
        val candidates = releases.filter { !it.prerelease }
        if (candidates.isEmpty()) return null
        val stable = candidates.reduce { best, candidate ->
            val bestVersion = versionNameFromTag(best.tagName)
            val candidateVersion = versionNameFromTag(candidate.tagName)
            when {
                // 认不出版本号（tag 不带 `v`）的不参与竞争；两边都认不出时保留 API 顺序在前的那条。
                candidateVersion == null -> best
                bestVersion == null -> candidate
                compareVersion(candidateVersion, bestVersion) > 0 -> candidate
                else -> best
            }
        }
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
     * 规则（semver 2.0 里够用的一段）：
     * 1. 先按 `+` 剥掉构建号（它不参与优先级比较）。
     * 2. 再按**第一个** `-` 分出数字段与预发布后缀（`1.0.0-beta.1` → `1.0.0` + `beta.1`）。
     * 3. 数字段按 `.` 逐段比数值；任何一段不是数字时按 0 处理，避免一个坏 tag 让整次
     *    比较抛异常。段数不足的一侧补 0 后再比，**补完仍相等时段数少的算更小**
     *    （`1.0` < `1.0.1`）。
     * 4. 数字段相等才比预发布：**带后缀的一律小于同号正式版**（`1.0.0-beta` < `1.0.0`）；
     *    两边都有后缀时逐标识符比（数字标识符按数值且小于任何非数字标识符，其余按字典序），
     *    标识符少的一方更小。
     *
     * 旧实现把 `-` 也当段分隔符、非数字段记 0，于是 `1.0.0-beta` 与 `1.0.0` **判等**：
     * 用户装着正式版时会看到"预发布就是最新"，而正式版 release 反而被判成不比它新。
     */
    fun compareVersion(a: String, b: String): Int {
        val (coreA, preA) = splitVersion(a)
        val (coreB, preB) = splitVersion(b)
        val pa = numericParts(coreA)
        val pb = numericParts(coreB)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        if (pa.size != pb.size) return if (pa.size > pb.size) 1 else -1

        return when {
            preA == null && preB == null -> 0
            preA == null -> 1 // 同号正式版大于任何带预发布后缀的
            preB == null -> -1
            else -> signOf(comparePrerelease(preA, preB))
        }
    }

    /** 拆出（数字段, 预发布后缀）。后缀为空串时按没有后缀处理。 */
    private fun splitVersion(version: String): Pair<String, String?> {
        val withoutBuild = version.substringBefore('+').trim()
        val dash = withoutBuild.indexOf('-')
        return if (dash >= 0) {
            withoutBuild.substring(0, dash) to withoutBuild.substring(dash + 1).takeIf { it.isNotEmpty() }
        } else {
            withoutBuild to null
        }
    }

    /** `0.1.0` → `[0, 1, 0]`。非数字段落 0（只喂数字段，坏段留给 0 兜底）。 */
    private fun numericParts(version: String): List<Int> =
        version.split('.').map { it.toIntOrNull() ?: 0 }

    /** semver §11.4：逐标识符比较预发布串。 */
    private fun comparePrerelease(a: String, b: String): Int {
        val ia = a.split('.')
        val ib = b.split('.')
        for (i in 0 until maxOf(ia.size, ib.size)) {
            val x = ia.getOrNull(i) ?: return -1 // 标识符少的一方更小
            val y = ib.getOrNull(i) ?: return 1
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val cmp = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1 // 数字标识符 < 非数字标识符
                ny != null -> 1
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun signOf(value: Int): Int = when {
        value > 0 -> 1
        value < 0 -> -1
        else -> 0
    }

    private const val NIGHTLY_CHANNEL = 1
}

/** 更新检查的匹配结论：该渠道下最新一条 release，以及它是否比当前版本新。 */
data class UpdateMatch(
    val latest: ReleaseInfo,
    val newer: Boolean,
)

package com.lc33.tokenvault.update

import kotlinx.serialization.json.Json

/**
 * 解析 GitHub Releases API 的响应（计划.md §13.4「更新」）。
 *
 * 只做「把 JSON 数组变成 [ReleaseInfo] 列表」这一件事，不碰网络、不碰版本比较。
 * 解析失败（响应体不是合法 JSON 数组 / 字段类型对不上）时返回空列表而不是抛异常——
 * 更新检查失败不该影响任何本地功能（§13.4 的诚实声明），上层据此走「检查失败」态。
 *
 * 字段只取 [ReleaseInfo] 里声明的那几个，`ignoreUnknownKeys` 丢掉其余（GitHub 的
 * release 对象有几十个字段，我们不搬运）。
 */
object ReleaseParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param body GitHub Releases API 的原始响应体（`/repos/{owner}/{repo}/releases`
     *   返回的 JSON 数组）。
     * @return 解析出的 release 列表（保持 API 返回顺序，GitHub 默认按创建时间倒序）。
     *   任何解析异常都返回空列表。
     */
    fun parse(body: String): List<ReleaseInfo> = try {
        json.decodeFromString<List<ReleaseInfo>>(body)
    } catch (_: Exception) {
        emptyList()
    }
}

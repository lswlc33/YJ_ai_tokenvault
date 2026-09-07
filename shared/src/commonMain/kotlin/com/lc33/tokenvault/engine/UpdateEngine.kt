package com.lc33.tokenvault.engine

import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.net.HttpEngine
import com.lc33.tokenvault.update.ReleaseInfo
import com.lc33.tokenvault.update.ReleaseMatcher
import com.lc33.tokenvault.update.ReleaseParser

/**
 * 更新检查引擎（计划.md §13.4「更新」）。
 *
 * 与 [ProbeEngine] / [BalanceEngine] 分开：它是「点一下、发一个匿名 GET、比一下版本」，
 * 没有探测的逐项进度、也没有余额的落库，生命周期最简。复用 [HttpEngine] 发请求——
 * 它的 UA 兜底、手动代理、超时配置都是这里要的，host 门闸对单次 GitHub 请求无感。
 *
 * 一条链：匿名 GET `/repos/lswlc33/YJ_ai_tokenvault/releases` → 解析 → 按渠道匹配。
 * 任何一步失败（网络 / 解析 / 匹配不到）都返回 [UpdateResult] 的失败态，**不影响任何
 * 本地功能**（§13.4 诚实声明）。
 *
 * 安全约束：这是匿名请求，不带任何身份、不带设备信息、不带金库内容（红线 32 的
 * 邻居——响应体也不落日志、不进 error 消息）。
 */
class UpdateEngine constructor(
    private val engine: HttpEngine,
    private val currentVersionName: String,
    private val repoUrl: String,
) {

    /**
     * 检查更新。
     *
     * @param channel 渠道下标：0=正式版、1=nightly（与 `update_channels` 数组对齐）。
     * @return 检查结果。成功态带 [ReleaseInfo]；失败态带可区分的错误码（无网 / 打不开）。
     */
    suspend fun check(channel: Int): UpdateResult {
        val response = engine.execute(
            ProbeRequest(
                method = "GET",
                url = repoUrl,
                headers = emptyList(),
                protocol = null,
            ),
            allowInsecure = false,
        )

        // 网络层失败：统一给「打不开」这一档。阶段2 迁 Ktor 后底层异常类型不再能稳定
        // 依赖 java.net.* 区分 DNS / 连接 / 超时，这里收敛为单一可区分的 UNREACHABLE——
        // §13.4 要的是「没网」与「上游打不开」可区分，前者靠 error != null 表达，
        // 后者靠非 2xx 表达，两者在 UI 上已经是两档文案。
        if (response.error != null) {
            return UpdateResult(error = UpdateErrorKind.NO_NETWORK)
        }

        if (response.status !in 200..299) {
            // 上游回了非 2xx（403 限流 / 404 仓库不存在 / 5xx）。GitHub 匿名 API 限流
            // 是 403 且带 "API rate limit exceeded"，这里不解析 body，统一给「打不开」。
            return UpdateResult(error = UpdateErrorKind.UNREACHABLE)
        }

        val releases = ReleaseParser.parse(response.body)
        val match = ReleaseMatcher.match(releases, currentVersionName, channel)
            ?: return UpdateResult(error = UpdateErrorKind.UNREACHABLE)

        return UpdateResult(latest = match.latest, newer = match.newer)
    }

    companion object {
        /** GitHub Releases API 端点。仓库公开，匿名可读（60 次/小时，手动检查足够）。 */
        const val RELEASES_URL = "https://api.github.com/repos/lswlc33/YJ_ai_tokenvault/releases"
    }
}

/** 更新检查结果。要么有 [latest]，要么有可区分的 [error]。 */
data class UpdateResult(
    val latest: ReleaseInfo? = null,
    val newer: Boolean = false,
    val error: UpdateErrorKind? = null,
)

/** 失败原因，UI 据此给可区分文案（§13.4：区分「没网」与「GitHub 打不开」）。 */
enum class UpdateErrorKind {
    /** 网络不可达（DNS 解析不了、连不上、超时）。 */
    NO_NETWORK,
    /** 上游打不开（非 2xx、解析失败、匹配不到）。 */
    UNREACHABLE,
}

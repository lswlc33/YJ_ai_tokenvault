package com.lc33.tokenvault.spike

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * M0.5 协议踩点（计划.md §16）。
 *
 * 用裸 OkHttp 打 `示例数据.md` 里那三家**真实**中转站，把原始状态码与响应体记下来。
 * 要回答的问题只有五个，但它们决定 §8.4 的错误分类矩阵、§9.2 的余额适配器、
 * §8.2 的客户端预设表——放到 M5–M7 才发现要返工四个里程碑：
 *
 *   1. `/v1/models` 到底鉴不鉴权？（红线 12 的"不鉴权基线检测"是否必要）
 *   2. 额度耗尽返回什么状态码、什么文案？（决定关键词要不要排在状态码之前）
 *   3. 会不会按客户端指纹拦？UA 影响多大？
 *   4. new-api 的 `quota_per_unit` 实际是多少？`/api/user/self` 的字段长什么样？
 *   5. 故意用错的 Key / 不存在的模型分别返回什么？
 *
 * **默认跳过**：它要联网、要花钱，所以同时需要
 *   - `-DvaultSpike=true`
 *   - 仓库根有 `示例数据.md`（真实凭据，永不入库）
 *
 * 原始响应写到 `.local/spike/`（`.gitignore` 写死）。提交进仓库的 fixture 必须是
 * 脱敏后的版本——上游经常把 key 前缀回显在错误消息里。
 */
class ProtocolSpike {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 本项目的默认 UA。中转站普遍拒绝空 UA，而 OkHttp 不设时会自报 okhttp/x.y。 */
    private val defaultUa = "YuanJi/0.1.0 (Android 15; arm64)"
    private val claudeCodeUa = "claude-cli/1.0.119 (external, cli)"
    private val codexCliUa = "codex_cli_rs/0.44.0 (Mac OS 15.5.0; arm64) Apple_Terminal"

    /** 明显"不是 AI 客户端"的对照组：分清上游是只拒空 UA 还是真的在做白名单。 */
    private val curlUa = "curl/8.7.1"

    private val results = mutableListOf<Probe>()
    private val secrets = mutableListOf<String>()

    /**
     * 本供应商所有请求都要叠上的头。空 = 用默认预设。
     *
     * 存在的理由是实测出来的：Agent Router 的客户端校验发生在**鉴权之前**，不带
     * §8.2 特征头时整站每条路由都回 401，于是"models 鉴不鉴权""错误 Key 回什么"
     * 这些问题一个都问不出来。所以踩点必须先过闸再问。
     */
    private var gateHeaders: Map<String, String> = emptyMap()

    /** 同 host 的请求间隔，撞 429 后自己加倍。 */
    private var paceMillis = 1200L

    /** 响应体保留上限。4000 不够：Agent Router 的 `/api/status` 光公告就超了，
     *  `quota_per_unit` 排在公告后面，会被整个截掉。 */
    private val bodyCap = 40_000

    @Test
    fun 踩点() {
        assumeTrue("需要 -DvaultSpike=true 才跑（会联网、会消耗额度）", System.getProperty("vaultSpike") == "true")
        val sample = findSampleFile()
        assumeTrue("仓库根找不到 示例数据.md", sample != null)

        val providers = parseSample(sample!!.readText())
        assumeTrue("示例数据.md 里没解析出供应商", providers.isNotEmpty())
        providers.forEach { p ->
            secrets += p.apiKey
            p.balanceToken?.let { secrets += it }
        }

        println("=".repeat(100))
        println("M0.5 协议踩点：${providers.size} 家（${providers.joinToString { it.name }}）")
        println("=".repeat(100))

        providers.forEach { probeProvider(it) }

        writeRaw()
        printSummary()
    }

    // ------------------------------------------------------------------ 探测矩阵

    private fun probeProvider(p: SampleProvider) {
        println("\n### ${p.name}  ${p.apiRoot}  协议=${p.protocols}")
        val firstIndex = results.size
        gateHeaders = emptyMap()
        paceMillis = 1200L
        // 连着撞 429 就别硬顶了（红线 29 的精神）：三次之后本供应商的可选请求全停，
        // 只保留余额查询——它走的是管理接口，限流是分开算的。
        fun tooManyRateLimits() = results.drop(firstIndex).count { it.status == 429 } >= 3

        // 1) 模型列表：正常 Key / 错误 Key / 无鉴权头 / Anthropic 风格鉴权头
        //    错误 Key 那条就是 §8.3「不鉴权基线检测」用的那次请求，值要与实现保持一致。
        val listUrl = "${p.apiRoot}/v1/models"
        request(p, "L1 models 正常Key", "GET", listUrl, bearer(p.apiKey))

        // 1b) 撞上客户端闸就换 §8.2 的预设头再来一次 —— 这正是 §8.2「自动嗅探」的动作，
        //     顺便实测它到底管不管用。之后所有请求都带着这套头，否则后面的问题问不出来。
        if (looksClientBlocked(results.lastOrNull())) {
            println("    ↑ 命中客户端校验关键词，换 §8.2 预设头重试")
            gateHeaders = clientProfileHeaders(p.protocols.firstOrNull() ?: "CHAT")
            request(p, "L1 models 过闸后", "GET", listUrl, bearer(p.apiKey))
        }

        request(p, "L1 models 错误Key", "GET", listUrl, bearer("yj-probe-invalid"))
        request(p, "L1 models 无鉴权", "GET", listUrl, emptyMap())
        request(p, "L1 models x-api-key", "GET", listUrl, mapOf("x-api-key" to p.apiKey))

        // 2) 每个协议一次极简推理调用
        for (protocol in p.protocols) {
            if (tooManyRateLimits()) break
            val model = p.models.firstOrNull { it.second == protocol }?.first ?: p.models.firstOrNull()?.first
            if (model == null) continue
            val (path, headers, body) = inferenceCall(p, protocol, model, p.apiKey)
            request(p, "L3 $protocol $model", "POST", "${p.apiRoot}$path", headers, body)
        }

        val firstProtocol = p.protocols.firstOrNull()
        val firstModel = p.models.firstOrNull()?.first

        // 3) §5.2「鉴权风格兜底」：部分中转站的 Anthropic 端点只认 Authorization: Bearer。
        //    要知道 authStyle=auto 的重试路径是不是真的必要，就得两种头各打一次。
        if ("ANTHROPIC" in p.protocols && !tooManyRateLimits()) {
            val model = p.models.firstOrNull { it.second == "ANTHROPIC" }?.first ?: firstModel
            if (model != null) {
                request(
                    p,
                    "L3 ANTHROPIC 用Bearer",
                    "POST",
                    "${p.apiRoot}/v1/messages",
                    mapOf(
                        "User-Agent" to defaultUa,
                        "Accept" to "application/json",
                        "Authorization" to "Bearer ${p.apiKey}",
                        "anthropic-version" to "2023-06-01",
                    ),
                    anthropicBody(model),
                )
            }
        }

        // 4) 故意用错的 Key —— 期望 401
        if (firstProtocol != null && firstModel != null && !tooManyRateLimits()) {
            val (path, headers, body) = inferenceCall(p, firstProtocol, firstModel, "sk-yj-probe-invalid-000000000000")
            request(p, "L2 错误Key $firstProtocol", "POST", "${p.apiRoot}$path", headers, body)
        }

        // 5) 不存在的模型 —— 期望 404 或 400
        if (firstProtocol != null && !tooManyRateLimits()) {
            val (path, headers, body) = inferenceCall(p, firstProtocol, "no-such-model-yj-0000", p.apiKey)
            request(p, "L3 不存在的模型", "POST", "${p.apiRoot}$path", headers, body)
        }

        // 6) 额度不足长什么样（问题 2）。没法真把账号刷空，但 new-api 系是**先按
        //    prompt + max_tokens 预扣额度**的：把 max_tokens 开到远超余额能覆盖的量，
        //    就能在不真的发给上游的前提下拿到那条额度文案。若余额足够，请求会转发上去、
        //    上游用 400 抱怨 max_tokens 越界——那也正好是 §8.4 第 9 行要的样本。
        //    两种结果都有用，且都只回一句 "ping" 的钱。
        if (firstProtocol != null && firstModel != null && !tooManyRateLimits()) {
            val (path, headers, body) = inferenceCall(p, firstProtocol, firstModel, p.apiKey, maxTokens = 200_000)
            request(p, "额度探针 max_tokens=200k", "POST", "${p.apiRoot}$path", headers, body)
        }

        // 7) 客户端指纹（问题 3）。分四档打，才能分清"只看 UA"还是"看特征头"：
        //    okhttp 自报 → curl（明显不是 AI 客户端）→ 只换 UA → UA + §8.2 的整套特征头。
        //    这一组**不叠 gateHeaders**：整组问的就是头本身的作用。
        if (firstProtocol != null && firstModel != null && !tooManyRateLimits()) {
            val (path, baseHeaders, body) = inferenceCall(p, firstProtocol, firstModel, p.apiKey)
            val postUrl = "${p.apiRoot}$path"
            request(p, "UA=okhttp默认", "POST", postUrl, baseHeaders - "User-Agent", body, useGate = false)
            request(p, "UA=curl", "POST", postUrl, baseHeaders + ("User-Agent" to curlUa), body, useGate = false)
            val clientUa = if (firstProtocol == "RESPONSES") codexCliUa else claudeCodeUa
            request(p, "UA=客户端", "POST", postUrl, baseHeaders + ("User-Agent" to clientUa), body, useGate = false)
            request(
                p,
                "UA+特征头=客户端",
                "POST",
                postUrl,
                baseHeaders + clientProfileHeaders(firstProtocol),
                body,
                useGate = false,
            )
        }

        // 8) DeepSeek 的 Anthropic 兼容端点在 /anthropic 下 —— 验证 §5.2 的 pathOverrides 是必需的
        if (p.apiRoot.contains("deepseek") && firstModel != null) {
            val body = anthropicBody(firstModel)
            val headers = mapOf(
                "User-Agent" to defaultUa,
                "Accept" to "application/json",
                "x-api-key" to p.apiKey,
                "anthropic-version" to "2023-06-01",
            )
            request(p, "DeepSeek /v1/messages", "POST", "${p.apiRoot}/v1/messages", headers, body)
            request(p, "DeepSeek /anthropic/v1/messages", "POST", "${p.apiRoot}/anthropic/v1/messages", headers, body)
        }

        // 9) 余额。即使协议侧撞了 429 也照样打：走的是中转站管理接口、限流是分开算的，
        //    而 quota_per_unit 是问题 4 的唯一答案来源。
        //    §8.2 规定余额查询用 default 预设、不带伪装头，所以这里也不叠 gateHeaders。
        when (p.balanceKind) {
            "newapi" -> {
                val base = p.balanceBaseUrl ?: origin(p.apiRoot)
                val headers = buildMap {
                    put("User-Agent", defaultUa)
                    put("Accept", "application/json")
                    p.balanceToken?.let { put("Authorization", "Bearer $it") }
                    p.balanceUserId?.let { put("New-Api-User", it) }
                }
                request(p, "余额 /api/user/self", "GET", "$base/api/user/self", headers, useGate = false)
                request(p, "余额 /api/status", "GET", "$base/api/status", headers, useGate = false)
            }
            "deepseek" -> {
                request(p, "余额 /user/balance", "GET", "${p.apiRoot}/user/balance", bearer(p.apiKey), useGate = false)
            }
        }
    }

    /** §8.2 内置预设里那两套特征头。只在踩点里硬编码——真正的实现里它们是数据（红线 22）。 */
    private fun clientProfileHeaders(protocol: String): Map<String, String> = when (protocol) {
        "RESPONSES" -> mapOf(
            "User-Agent" to codexCliUa,
            "originator" to "codex_cli_rs",
            "session_id" to "00000000-0000-4000-8000-000000000000",
            "openai-beta" to "responses=experimental",
        )
        else -> mapOf(
            "User-Agent" to claudeCodeUa,
            "x-app" to "cli",
            "anthropic-beta" to "claude-code-20250219,oauth-2025-04-20",
            "x-stainless-lang" to "js",
            "x-stainless-runtime" to "node",
            "x-stainless-runtime-version" to "v22.14.0",
            "x-stainless-os" to "MacOS",
            "x-stainless-arch" to "arm64",
            "x-stainless-package-version" to "0.60.0",
            "x-stainless-retry-count" to "0",
        )
    }

    private fun inferenceCall(
        p: SampleProvider,
        protocol: String,
        model: String,
        key: String,
        maxTokens: Int = 16,
    ): Triple<String, Map<String, String>, String> {
        val common = mapOf(
            "User-Agent" to defaultUa,
            "Accept" to "application/json",
        )
        return when (protocol) {
            "ANTHROPIC" -> Triple(
                "/v1/messages",
                common + mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
                anthropicBody(model, maxTokens),
            )
            "RESPONSES" -> Triple(
                "/v1/responses",
                common + mapOf("Authorization" to "Bearer $key"),
                """{"model":"$model","input":"ping","max_output_tokens":$maxTokens,"store":false}""",
            )
            else -> Triple(
                "/v1/chat/completions",
                common + mapOf("Authorization" to "Bearer $key"),
                """{"model":"$model","messages":[{"role":"user","content":"ping"}],"max_tokens":$maxTokens,"stream":false}""",
            )
        }
    }

    // max_tokens 取 16 而不是 1：思考类模型要求它大于思考预算，给 1 会稳定拿到 400。
    private fun anthropicBody(model: String, maxTokens: Int = 16) =
        """{"model":"$model","max_tokens":$maxTokens,"messages":[{"role":"user","content":"ping"}]}"""

    private fun bearer(key: String) = mapOf(
        "User-Agent" to defaultUa,
        "Accept" to "application/json",
        "Authorization" to "Bearer $key",
    )

    private fun request(
        p: SampleProvider,
        label: String,
        method: String,
        url: String,
        headers: Map<String, String>,
        jsonBody: String? = null,
        useGate: Boolean = true,
    ) {
        // gateHeaders 作基底、参数里的头覆盖它：UA 实验那一组要能真的把 UA 换掉。
        val effective = if (useGate) gateHeaders + headers else headers
        val builder = Request.Builder().url(url)
        effective.forEach { (k, v) -> builder.header(k, v) }
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json; charset=utf-8")
            builder.post(jsonBody.toRequestBody(jsonMediaType))
        }
        val started = System.nanoTime()
        val probe = try {
            client.newCall(builder.build()).execute().use { response ->
                Probe(
                    provider = p.name,
                    label = label,
                    method = method,
                    url = url,
                    sentHeaders = effective.keys.sorted(),
                    status = response.code,
                    latencyMs = (System.nanoTime() - started) / 1_000_000,
                    contentType = response.header("Content-Type"),
                    retryAfter = response.header("Retry-After"),
                    // 响应头要全记：判"是不是 WAF 拦的"靠 server / cf-ray / x-ratelimit-*，
                    // 而不是靠状态码——403 既可能来自中转站的客户端校验，也可能来自 Cloudflare。
                    responseHeaders = response.headers.names().sorted()
                        .filter { !it.equals("set-cookie", ignoreCase = true) }
                        .associateWith { response.headers.values(it).joinToString("; ") },
                    body = response.body.string().take(bodyCap),
                    error = null,
                )
            }
        } catch (t: Throwable) {
            Probe(
                provider = p.name,
                label = label,
                method = method,
                url = url,
                sentHeaders = effective.keys.sorted(),
                status = null,
                latencyMs = (System.nanoTime() - started) / 1_000_000,
                contentType = null,
                retryAfter = null,
                responseHeaders = emptyMap(),
                body = "",
                error = "${t::class.simpleName}: ${t.message}",
            )
        }
        results += probe
        println(
            "  %-32s %-4s %-5s %5dms  %s".format(
                label,
                method,
                probe.status?.toString() ?: "ERR",
                probe.latencyMs,
                scrub(probe.error ?: probe.body.replace(Regex("\\s+"), " ")).take(160),
            ),
        )
        // 中转站普遍限流，同 host 顺序发且留间隔（红线 29 的精神）。
        // 撞 429 就把间隔加倍并等完 Retry-After：这是踩点，数据比省时间重要。
        if (probe.status == 429) {
            paceMillis = minOf(paceMillis * 2, 8_000)
            val wait = probe.retryAfter?.toLongOrNull()?.times(1000)?.plus(2_000) ?: 15_000
            println("    ↑ 429，等 ${wait}ms 后继续，间隔升到 ${paceMillis}ms")
            Thread.sleep(wait)
        }
        Thread.sleep(paceMillis)
    }

    /**
     * 响应是不是被客户端校验挡住了。实测过的形态：Agent Router 用
     * **401** + `unauthorized client detected` —— 不是 403，所以只按状态码分流会
     * 把它判成"密钥无效"（§8.2 原本的关键词表里也没有 `unauthorized client`）。
     */
    private fun looksClientBlocked(probe: Probe?): Boolean {
        if (probe?.status == null) return false
        val body = probe.body.lowercase()
        return listOf(
            "unauthorized client",
            "invalid client",
            "client not allowed",
            "forbidden client",
            "unsupported client",
            "unauthorized_client",
            "客户端",
        ).any { it in body }
    }

    // ------------------------------------------------------------------ 输出

    private fun writeRaw() {
        val dir = File(repoRoot(), ".local/spike").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val raw = File(dir, "spike-$stamp.json")
        raw.writeText(dump(scrubbed = false))
        // 同时落一份脱敏版：整理 fixture 时读它，就不用去碰那份含明文密钥的原始文件。
        val clean = File(dir, "spike-$stamp-scrubbed.json")
        clean.writeText(dump(scrubbed = true))
        println("\n原始响应（未脱敏，勿入库）：${raw.absolutePath}")
        println("脱敏版（整理 fixture 用这份）：${clean.absolutePath}")
    }

    private fun dump(scrubbed: Boolean): String {
        fun s(text: String?) = quote(if (scrubbed && text != null) scrub(text) else text)
        return results.joinToString(",\n", "[\n", "\n]") { r ->
            """  {
    "provider": ${quote(r.provider)},
    "label": ${quote(r.label)},
    "method": ${quote(r.method)},
    "url": ${quote(r.url)},
    "sentHeaders": ${r.sentHeaders.joinToString(", ", "[", "]") { quote(it) }},
    "status": ${r.status ?: "null"},
    "latencyMs": ${r.latencyMs},
    "contentType": ${quote(r.contentType)},
    "retryAfter": ${quote(r.retryAfter)},
    "responseHeaders": ${r.responseHeaders.entries.joinToString(", ", "{", "}") { "${quote(it.key)}: ${s(it.value)}" }},
    "error": ${s(r.error)},
    "body": ${s(r.body)}
  }"""
        }
    }

    private fun printSummary() {
        println("\n" + "=".repeat(100))
        println("汇总")
        println("=".repeat(100))
        results.groupBy { it.provider }.forEach { (provider, rows) ->
            println("\n$provider")
            rows.forEach { r ->
                val status = r.status?.toString() ?: "ERR"
                val retry = r.retryAfter?.let { " Retry-After=$it" } ?: ""
                println("  %-28s -> %-4s%s".format(r.label, status, retry))
                val text = scrub((r.error ?: r.body).replace(Regex("\\s+"), " ")).trim()
                if (text.isNotEmpty()) println("      ${text.take(400)}")
            }
        }
    }

    /**
     * 红线 32：先按已知明文值替换，正则只作兜底。
     *
     * 后缀也要replace：DeepSeek 的 401 回的是
     * `Your api key: ****alid is invalid` —— 掩掉了前面，**回显的是后 4 位**。
     * §7.5 原来只写"≥8 字符前缀"，挡不住这一形态。
     */
    private fun scrub(text: String): String {
        var out = text
        for (secret in secrets) {
            if (secret.length < 8) continue
            out = out.replace(secret, "<已省略>")
            out = out.replace(secret.take(8), "<已省略>")
            out = out.replace(secret.takeLast(4), "<已省略>")
        }
        return out
            .replace(Regex("""sk-[A-Za-z0-9_\-]{16,}"""), "<已省略>")
            .replace(Regex("""Bearer\s+\S+"""), "Bearer <已省略>")
            // §7.5 第二道兜底的 JSON 字段形态。new-api 的 /api/user/self 会把
            // access_token 连同邮箱、用户名、第三方账号 id 一起回显，这些不能进 fixture。
            .replace(
                Regex(
                    """"(access_token|token|key|secret|api_key|password|original_password|""" +
                        """username|display_name|email|aff_code|verification_code|""" +
                        """github_id|github_user_id|wechat_id|telegram_id|discord_id|linux_do_id|oidc_id)"""" +
                        """\s*:\s*"[^"]*"""",
                ),
            ) { "\"${it.groupValues[1]}\":\"<已省略>\"" }
    }

    private fun quote(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    // ------------------------------------------------------------------ 示例数据的临时解析
    // 真正的解析器在 M4 的 importer/ 里，带预览与校验。这里只够跑通踩点。

    private data class SampleProvider(
        val name: String,
        val apiKey: String,
        val apiRoot: String,
        val protocols: List<String>,
        val models: List<Pair<String, String>>,
        val balanceKind: String,
        val balanceBaseUrl: String?,
        val balanceToken: String?,
        val balanceUserId: String?,
    )

    private data class Probe(
        val provider: String,
        val label: String,
        val method: String,
        val url: String,
        val sentHeaders: List<String>,
        val status: Int?,
        val latencyMs: Long,
        val contentType: String?,
        val retryAfter: String?,
        val responseHeaders: Map<String, String>,
        val body: String,
        val error: String?,
    )

    private fun parseSample(text: String): List<SampleProvider> =
        text.split(Regex("(?m)^---\\s*$")).mapNotNull { parseRecord(it) }

    private fun parseRecord(block: String): SampleProvider? {
        val lines = block.lines().map { it.trim() }
        var name: String? = null
        var key: String? = null
        var baseUrl: String? = null
        var balanceKind = "none"
        var balanceBase: String? = null
        var token: String? = null
        var userId: String? = null
        val protocols = mutableListOf<String>()
        val models = mutableListOf<Pair<String, String>>()
        var mode = ""
        var seenBalanceKind = false

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isEmpty() -> {}
                line.startsWith("供应商名称") -> { name = line.removePrefix("供应商名称").trim(); mode = "" }
                line.startsWith("API Key") -> { key = line.removePrefix("API Key").trim(); mode = "" }
                line.startsWith("API请求地址") -> { baseUrl = line.removePrefix("API请求地址").trim(); mode = "" }
                line.startsWith("余额查询类型") -> {
                    val v = line.removePrefix("余额查询类型").trim()
                    balanceKind = if (v.equals("NewAPI", true)) "newapi" else "deepseek"
                    seenBalanceKind = true
                    mode = ""
                }
                line.startsWith("请求地址") && seenBalanceKind -> {
                    balanceBase = line.removePrefix("请求地址").trim().split(Regex("\\s+")).firstOrNull()
                        ?.takeIf { it.startsWith("http") }
                    mode = ""
                }
                line.startsWith("访问令牌") -> {
                    token = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && !it.startsWith("用户ID") }
                    if (token != null) i++
                    mode = ""
                }
                line.startsWith("用户ID") -> { userId = line.removePrefix("用户ID").trim(); mode = "" }
                line.startsWith("支持端点类型") -> mode = "protocols"
                line.startsWith("模型列表") -> mode = "models"
                line.startsWith("备注") || line.startsWith("官网链接") -> mode = ""
                mode == "protocols" && line.startsWith("-") ->
                    protocolOf(line.removePrefix("-").trim())?.let { protocols += it }
                mode == "models" -> {
                    val idx = line.lastIndexOf(' ')
                    if (idx > 0) {
                        val proto = protocolOf(line.substring(idx + 1))
                        if (proto != null) models += line.substring(0, idx).trim() to proto
                        else models += line to (protocols.firstOrNull() ?: "CHAT")
                    } else {
                        models += line to (protocols.firstOrNull() ?: "CHAT")
                    }
                }
            }
            i++
        }
        if (name == null || key == null || baseUrl == null) return null
        if (balanceKind == "deepseek" && !baseUrl.contains("deepseek")) balanceKind = "none"
        return SampleProvider(
            name = name,
            apiKey = key,
            apiRoot = baseUrl.trimEnd('/').removeSuffix("/v1"),
            protocols = protocols.distinct().ifEmpty { listOf("CHAT") },
            models = models,
            balanceKind = balanceKind,
            balanceBaseUrl = balanceBase?.trimEnd('/'),
            balanceToken = token,
            balanceUserId = userId,
        )
    }

    private fun protocolOf(raw: String): String? {
        val v = raw.lowercase().replace(Regex("[\\s()（）]"), "")
        return when {
            v.startsWith("chat") || v == "openai" -> "CHAT"
            v.startsWith("responses") -> "RESPONSES"
            v.contains("anthropic") || v.contains("claude") || v == "messages" -> "ANTHROPIC"
            else -> null
        }
    }

    private fun origin(apiRoot: String): String {
        val withoutScheme = apiRoot.substringAfter("://")
        val hostPort = withoutScheme.substringBefore('/')
        return apiRoot.substringBefore("://") + "://" + hostPort
    }

    private fun repoRoot(): File {
        // parentFile 是 Java 的平台类型（File!），直接赋给 File 会有类型不匹配警告
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        return File(".").absoluteFile
    }

    private fun findSampleFile(): File? = File(repoRoot(), "示例数据.md").takeIf { it.isFile }
}

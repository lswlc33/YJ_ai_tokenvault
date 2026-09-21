package com.lc33.tokenvault.endpoint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 从上游的失败响应里抽出**它自己写的那句原因**。
 *
 * 为什么要有这一件：界面上能拿到的失败结论是一串机器码——余额那边是
 * [com.lc33.tokenvault.domain.model.BalanceSnapshot.error]（`http 401`、`missing_quota`），
 * 探测那边是 [com.lc33.tokenvault.probe.Classification.detail]（上游原文前 200 字符）。
 * 前者翻成"上游返回 401"就到底了，后者是一整坨带括号的 JSON。可用户真正要的那句话在
 * 响应体里：new-api 系的站点被问到过期的**安全访问令牌**时回的是
 * `{"success":false,"message":"安全访问令牌已失效"}`。只报 401 等于把唯一有用的那句
 * 丢掉，用户只能自己在"令牌错了 / 地址错了 / 站点挂了"之间猜。
 *
 * **入参必须是已脱敏的文本**，不是原始响应体：new-api 出错时会把请求上下文连访问令牌、
 * 邮箱一起回显，而抽出来的这句要显示在界面上、也要写进审计日志（红线 32）。两个调用方
 * 各自拿到的都是已过 [com.lc33.tokenvault.crypto.Redactor] 的那一份
 * （`balance_raw` 列、`health_detail` 列），所以这里不再脱敏，也**不能**有人拿原始
 * `ProbeResponse.body` 直接调它。
 *
 * 两条实测逼出来的规矩：
 * - **JSON 解析不成也要试一把**：`balance_raw` 只留头 8 KB，而网关把一整页 HTML 塞进
 *   `message` 时也截得掉闭合括号。所以 JSON 那条路走不通时退化成按字段名扫第一段字符串值
 *   ——宁可得出一句略糙的原因，也不要什么都不说。
 * - **只出一行**：结果直接进界面的一行小字，带换行的 HTML 会把那一块撑散。
 */
object UpstreamMessage {

    /**
     * @param redactedBody 已脱敏（必要时已截断）的上游响应体。
     * @return 单行、≤[MAX_MESSAGE_CHARS] 字符的原因；抽不到返回 null，由调用方回落到
     * 机器码文案（别拿 `{"error":{…}` 去糊用户）。
     */
    fun of(redactedBody: String?): String? {
        val text = redactedBody?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return jsonCandidate(text) ?: regexCandidate(text)
    }

    /**
     * JSON 那条路：按各家约定的字段顺序找第一个非空的字符串值。
     *
     * 顺序是**从最具体到最兜底**：顶层 `message` 是 new-api / one-api 及其所有衍生站的约定
     * （中文原因就写在这里），`error.message` 是 OpenAI 形状（DeepSeek 与多数兼容站），
     * `detail` 是 FastAPI / 网关类，`msg` 是另一些 Go 后端的写法，`data.message` 排最后：
     * 少数站把错误也包在 `data` 里。
     */
    private fun jsonCandidate(body: String): String? {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        val nested = (root["error"] as? JsonObject) ?: (root["data"] as? JsonObject)
        return listOfNotNull(
            root.stringOf("message"),
            (root["error"] as? JsonPrimitive)?.textOrNull(),
            root.stringOf("msg"),
            root.stringOf("detail"),
            nested?.stringOf("message"),
            nested?.stringOf("msg"),
        ).asSequence().map { clean(it) }.firstOrNull { it.isNotEmpty() }
    }

    /**
     * 只认**字符串**值。
     *
     * `contentOrNull` 对 `{"message":123}` 也返回 "123"（数字也有"内容"），而那句是要
     * 显示成"上游说：123"的——那是噪声不是原因。退化那条路（[firstStringValueFor]）
     * 本来就只认引号包起来的值，这里对齐，两条路才不会给出不一样的结论。
     */
    private fun JsonObject.stringOf(key: String): String? = (this[key] as? JsonPrimitive)?.textOrNull()

    private fun JsonPrimitive.textOrNull(): String? = if (isString) contentOrNull else null

    /**
     * 退化那条路：body 不是合法 JSON（被截断、或是 HTML 错误页）时按字段名扫值。
     *
     * 不用正则：这段小文本上手，手工找引号比转义一套正则更少意外，也不会因为一个
     * 写错的量词把整段响应体捞出来。
     */
    private fun regexCandidate(body: String): String? {
        for (key in FIELD_ORDER) {
            val extracted = firstStringValueFor(body, key) ?: continue
            if (extracted.isNotEmpty()) return extracted
        }
        return null
    }

    /** 找 `"key"` 的第一次出现里那个**字符串**值；同名键出现多次就逐个试。 */
    private fun firstStringValueFor(body: String, key: String): String? {
        val quoted = "\"$key\""
        var from = 0
        while (true) {
            val hit = body.indexOf(quoted, from, ignoreCase = true)
            if (hit < 0) return null
            val afterKey = hit + quoted.length
            val colon = body.indexOf(':', afterKey)
            // 键与冒号之间只允许一点点空白：跨了一大段 JSON 才找到的冒号属于另一个字段。
            if (colon < 0 || colon - afterKey > MAX_GAP) return null
            var i = colon + 1
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length) return null
            if (body[i] != '"') {
                // 这个键的值不是字符串（对象 / 数组 / 数字）。从下一次出现接着找，
                // `{"error":{"message":"…"}}` 就是这么被捞到的。
                from = i
                continue
            }
            val sb = StringBuilder()
            i++
            while (i < body.length) {
                val c = body[i]
                if (c == '\\') {
                    // 只还原界面上看得懂的那几个转义，其余原样留下。
                    when (val next = body.getOrNull(i + 1)) {
                        '"', '\\', '/' -> { sb.append(next); i += 2 }
                        'n', 'r', 't' -> { sb.append(' '); i += 2 }
                        null -> i++
                        else -> { sb.append(c); i++ }
                    }
                    continue
                }
                if (c == '"') return clean(sb.toString())
                sb.append(c)
                i++
            }
            // 走到这里说明值没闭合（响应体被截断了）：把手上这段当作原因——半句话
            // 也比没有强，而且界面上它和完整那句长得一样。
            return clean(sb.toString()).takeIf { it.isNotEmpty() }
        }
    }

    /** 收成一个能安全放进一行小字的串：压掉所有空白，再限长。 */
    private fun clean(value: String): String {
        val sb = StringBuilder(value.length)
        var pendingSpace = false
        for (ch in value) {
            if (ch.isWhitespace()) {
                // 连续空白压成一个，开头的空白丢掉（`pendingSpace` 只在已经写过内容之后
                // 才成立）；结尾的那个自然也不会有，它等不到后继字符。
                pendingSpace = sb.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                sb.append(' ')
                pendingSpace = false
            }
            sb.append(ch)
        }
        val collapsed = sb.toString()
        return if (collapsed.length > MAX_MESSAGE_CHARS) {
            collapsed.take(MAX_MESSAGE_CHARS) + MESSAGE_TRUNCATED_MARK
        } else {
            collapsed
        }
    }

    /** 查找顺序，与 [jsonCandidate] 一致。 */
    private val FIELD_ORDER = listOf("message", "msg", "detail", "error")

    /** 键与值之间允许隔多少个字符（超过就认定这个冒号不属于这个键）。 */
    private const val MAX_GAP = 3

    /**
     * 那句话的字符上限。
     *
     * 它要显示在余额卡的一行小字里，而网关类的错误页能把一整段 HTML 塞进 `message`。
     * 200 与 [com.lc33.tokenvault.probe.ProbeClassifier] 给 `detail` 的截断长度同口径，
     * 两处显示的是同一类东西。
     */
    private const val MAX_MESSAGE_CHARS = 200

    /** 截断标记用英文：技术性文本，界面文案才走资源（同 `BalanceEngine.RAW_TRUNCATED_MARK`）。 */
    private const val MESSAGE_TRUNCATED_MARK = "…[truncated]"
}

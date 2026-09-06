package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ModelProbeState
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.ProbeOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 测试 3：错误分类（计划.md §14.3，§8.4 的矩阵）。
 *
 * 用例**直接读** `app/src/test/resources/fixtures/probe-matrix.json`——那 16 条是 M0.5
 * 从三家真实中转站上采到的去重响应，每条带 `expectOutcome` / `expectHealth`。一条 case
 * 一个断言，所以顺序错误、关键词漏配在这里都会立刻翻出来。
 *
 * 重点覆盖（都是 M0.5 实测踩出来的坑）：
 * - `401` + `unauthorized client detected` → CLIENT_BLOCKED，**不是** UNAUTHORIZED（红线 33）。
 * - `400` + `supported API model names` → 模型 NOT_FOUND，供应商/密钥 health 不动。
 * - `403` / `404` + **空 body** → 只按状态码分流，标"上游未提供原因"（红线 35）。
 * - 纯文本 body（`error code: 1015`、`Authentication Fails (governor)`）不因解析失败升级。
 * - `200` + `status: "incomplete"` 与 `200` + `content: ""` 都判 SUCCESS（红线 34）。
 */
class ProbeClassifierTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadCases(): List<JsonElement> {
        val text = javaClass.classLoader!!.getResource("fixtures/probe-matrix.json")!!
            .readText()
        return json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray.toList()
    }

    @Test
    fun `probe-matrix 每一条都命中期望结论`() {
        val cases = loadCases()
        assertNotNull("fixture 里应该有 cases", cases)
        cases.forEach { case ->
            val obj = case.jsonObject
            val id = obj["id"]!!.jsonPrimitive.content
            val status = obj["status"]!!.jsonPrimitive.intOrNull ?: 0
            val body = obj["body"]?.jsonPrimitive?.content ?: ""
            val expectOutcome = obj["expectOutcome"]!!.jsonPrimitive.content
            val expectHealth = obj["expectHealth"]
            val expectHealthRaw = expectHealth?.jsonPrimitive?.contentOrNull

            // "模型级 NOT_FOUND" 的 case 是 L3 视角（POST 模型不存在）；其余是 L1/L2。
            val level = if (expectHealthRaw?.startsWith("模型级") == true) {
                ProbeLevel.L3_MODEL
            } else {
                ProbeLevel.L2_KEY_VALIDITY
            }

            val classification = ProbeClassifier.classify(
                status = status,
                body = body,
                error = null,
                level = level,
            )

            // 断言 outcome
            assertEquals(
                "[$id] outcome 不符",
                expectOutcome.uppercase(),
                classification.outcome.name,
            )

            // 断言 health
            assertExpectedHealth(id, expectHealth, classification)
        }
    }

    /**
     * `expectHealth` 有三种形态：JSON null（不改写）、`"CLIENT_BLOCKED"`（直接比对）、
     * 以及带说明的模型级（`模型级 NOT_FOUND；供应商与密钥的 health 不改写`）。
     */
    private fun assertExpectedHealth(
        id: String,
        expectHealth: JsonElement?,
        classification: Classification,
    ) {
        val raw = expectHealth?.jsonPrimitive?.contentOrNull
        if (raw == null) {
            // JSON null：不许改写 health（429 / 网络错误）
            assertNull("[$id] 应为 null（不改写 health），实际 ${classification.health}", classification.health)
            return
        }

        when {
            raw.startsWith("模型级") -> {
                // 模型级 NOT_FOUND，密钥 health 不动
                assertNull("[$id] 模型级结论不应改写密钥 health", classification.health)
                assertEquals("[$id] 模型应判 NOT_FOUND", ModelProbeState.NOT_FOUND, classification.modelState)
            }
            raw.startsWith("CONFIG_ERROR") -> {
                assertEquals("[$id] health", KeyHealth.CONFIG_ERROR, classification.health)
                assertEquals("[$id] reason 应为 BadPath", ClassificationReason.BadPath, classification.reason)
            }
            else -> {
                val expected = KeyHealth.entries.first { it.name.equals(raw, ignoreCase = true) }
                assertEquals("[$id] health", expected, classification.health)
            }
        }
    }

    // ------------------------------------------------------------------ 测试 3 明确点名的顺序用例

    @Test
    fun `403 加额度关键词判 INSUFFICIENT 而不是 FORBIDDEN`() {
        val r = ProbeClassifier.classify(
            403,
            "{\"error\":{\"message\":\"该令牌额度已用尽\"}}",
            null,
            ProbeLevel.L2_KEY_VALIDITY,
        )
        assertEquals(KeyHealth.INSUFFICIENT, r.health)
        assertEquals(ProbeOutcome.CONCLUSIVE_FAIL, r.outcome)
    }

    @Test
    fun `400 加 insufficient user quota 判 INSUFFICIENT`() {
        val r = ProbeClassifier.classify(
            400,
            "{\"error\":{\"message\":\"insufficient user quota\"}}",
            null,
            ProbeLevel.L2_KEY_VALIDITY,
        )
        assertEquals(KeyHealth.INSUFFICIENT, r.health)
    }

    @Test
    fun `400 在 L2 判 OK 而不是 CONFIG_ERROR`() {
        val r = ProbeClassifier.classify(
            400,
            "{\"error\":{\"message\":\"some param error\"}}",
            null,
            ProbeLevel.L2_KEY_VALIDITY,
        )
        assertEquals(ProbeOutcome.SUCCESS, r.outcome)
        assertEquals(KeyHealth.OK, r.health)
        assertEquals(ClassificationReason.ParamRejectedButKeyValid, r.reason)
    }

    @Test
    fun `400 在 L1 判 CONFIG_ERROR`() {
        val r = ProbeClassifier.classify(
            400,
            "{\"error\":{\"message\":\"bad request\"}}",
            null,
            ProbeLevel.L1_REACHABILITY,
        )
        assertEquals(ProbeOutcome.CONCLUSIVE_FAIL, r.outcome)
        assertEquals(KeyHealth.CONFIG_ERROR, r.health)
    }

    @Test
    fun `未知主机错误不改写 health`() {
        val r = ProbeClassifier.classify(
            null,
            null,
            java.net.UnknownHostException("api.example.invalid"),
            ProbeLevel.L2_KEY_VALIDITY,
        )
        assertEquals(ProbeOutcome.NETWORK_ERROR, r.outcome)
        assertNull(r.health)
    }

    @Test
    fun `429 不改写 health`() {
        val r = ProbeClassifier.classify(
            429,
            "error code: 1015",
            null,
            ProbeLevel.L2_KEY_VALIDITY,
        )
        assertEquals(ProbeOutcome.RATE_LIMITED, r.outcome)
        assertNull(r.health)
    }

    @Test
    fun `5xx 不改写 health`() {
        val r = ProbeClassifier.classify(
            503,
            "Service Unavailable",
            null,
            ProbeLevel.L2_KEY_VALIDITY,
        )
        assertEquals(ProbeOutcome.UPSTREAM_ERROR, r.outcome)
        assertNull(r.health)
    }

    @Test
    fun `429 退避时间取 body 里 retry_after`() {
        val r = ProbeClassifier.classify(
            429,
            "{\"retry_after\":30}",
            null,
            ProbeLevel.L2_KEY_VALIDITY,
        )
        assertEquals(30_000L, r.retryAfterMs)
    }

    @Test
    fun `200 且 content 为空仍判 SUCCESS`() {
        // 红线 34：不能要求文本非空（DeepSeek 推理模型 content 是空串）
        val r = ProbeClassifier.classify(
            200,
            "{\"choices\":[{\"message\":{\"content\":\"\"}}]}",
            null,
            ProbeLevel.L3_MODEL,
        )
        assertEquals(ProbeOutcome.SUCCESS, r.outcome)
        assertEquals(KeyHealth.OK, r.health)
    }

    @Test
    fun `200 且 status incomplete 仍判 SUCCESS`() {
        val r = ProbeClassifier.classify(
            200,
            "{\"status\":\"incomplete\",\"output\":[]}",
            null,
            ProbeLevel.L3_MODEL,
        )
        assertEquals(ProbeOutcome.SUCCESS, r.outcome)
    }

    @Test
    fun `2xx 但带 error 字段判 CONFIG_ERROR`() {
        val r = ProbeClassifier.classify(
            200,
            "{\"error\":{\"message\":\"something broke\"}}",
            null,
            ProbeLevel.L1_REACHABILITY,
        )
        assertEquals(ProbeOutcome.CONCLUSIVE_FAIL, r.outcome)
        assertEquals(KeyHealth.CONFIG_ERROR, r.health)
        assertEquals(ClassificationReason.UnexpectedContent, r.reason)
    }

    @Test
    fun `404 空 body 判 CONFIG_ERROR 且标 BadPath`() {
        val r = ProbeClassifier.classify(
            404,
            "",
            null,
            ProbeLevel.L1_REACHABILITY,
        )
        assertEquals(KeyHealth.CONFIG_ERROR, r.health)
        assertEquals(ClassificationReason.BadPath, r.reason)
    }
}

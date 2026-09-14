package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ModelProbeState
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 模型可达性探测的两条产品规则（§8.6 的手动快捷探测）。
 *
 * 这些断言看着琐碎，但它们定义的是用户会直接看到的那句话："这个模型到底能不能用、
 * 是试了哪条路才成的"。写成测试，是为了以后改回落顺序或改分档时有人必须**显式**
 * 改这里，而不是悄悄换掉结论。
 *
 * 触发方式本身（只能手动、自动路径永不生成 L3）由 `ProbePlanBuilderTest` 守着：
 * 计划里根本不产出 L3 任务。
 */
class ModelProbePolicyTest {

    @Test
    fun `协议顺序是 Chat 优先、失败回落 Anthropic`() {
        assertEquals(listOf(Protocol.CHAT, Protocol.ANTHROPIC), MODEL_PROBE_PROTOCOL_ORDER)
    }

    @Test
    fun `成功即可用，不看用哪条协议`() {
        val state = modelProbeStateOf(
            Classification(ProbeOutcome.SUCCESS, health = KeyHealth.OK, httpStatus = 200),
        )
        assertEquals(ModelProbeState.OK, state)
    }

    @Test
    fun `两个协议都被拒时判不可达`() {
        // Chat 与 Anthropic 都发出去、都拿到判定性失败（400 / 404 / 401 之类）：
        // 这不是"还没测"，而是**试过了、到不了**，必须落成 ERROR，
        // 停在"未探测"会让人以为还没探过。
        val bothFailed = Classification(
            ProbeOutcome.CONCLUSIVE_FAIL,
            health = KeyHealth.CONFIG_ERROR,
            httpStatus = 404,
        )
        assertEquals(ModelProbeState.ERROR, modelProbeStateOf(bothFailed))
    }

    @Test
    fun `上游说模型不存在时给 NOT_FOUND 而不是笼统的错误`() {
        val state = modelProbeStateOf(
            Classification(
                ProbeOutcome.CONCLUSIVE_FAIL,
                modelState = ModelProbeState.NOT_FOUND,
                httpStatus = 404,
            ),
        )
        assertEquals(ModelProbeState.NOT_FOUND, state)
    }

    @Test
    fun `被拒访问是 NO_ACCESS，不是"模型不存在"`() {
        val state = modelProbeStateOf(
            Classification(
                ProbeOutcome.CONCLUSIVE_FAIL,
                health = KeyHealth.FORBIDDEN,
                httpStatus = 403,
            ),
        )
        assertEquals(ModelProbeState.NO_ACCESS, state)
    }

    @Test
    fun `瞬时失败不写成不可达`() {
        // 红线 11：超时 / 429 / 5xx 只记这一轮发生了什么，不该把上一轮的好结论抹成
        // "到不了"。落 UNKNOWN 时调用方走 applyTransientOutcome，不改持久状态。
        assertEquals(
            ModelProbeState.UNKNOWN,
            modelProbeStateOf(Classification(ProbeOutcome.NETWORK_ERROR)),
        )
        assertEquals(
            ModelProbeState.UNKNOWN,
            modelProbeStateOf(Classification(ProbeOutcome.RATE_LIMITED, httpStatus = 429)),
        )
        assertEquals(
            ModelProbeState.UNKNOWN,
            modelProbeStateOf(Classification(ProbeOutcome.UPSTREAM_ERROR, httpStatus = 502)),
        )
    }
}

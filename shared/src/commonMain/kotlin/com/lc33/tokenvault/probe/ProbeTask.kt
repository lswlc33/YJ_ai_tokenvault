package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.Protocol

/**
 * 一轮探测的任务（§8.3 的 L1/L2）。
 *
 * 任务是"要发哪个请求、归谁、为什么"的纯描述，不携带结果——结果逐项推流。
 * M5 只生成 L1（供应商 × 协议）与 L2（每张 Key），不做 L3/L4（红线 36：花钱的只手动）。
 */
data class ProbeTask(
    /** 唯一标识，用于进度去重与测试断言。 */
    val id: String,
    val level: ProbeLevel,
    val providerId: Long,
    val providerName: String,
    val host: String,
    val protocol: Protocol,

    /** L2 时才非空。 */
    val keyId: Long? = null,

    /** 请求 URL。 */
    val url: String,

    /** 最终要发出去的完整头（base + 预设 UA/特征头 + 鉴权头，已由引擎用 `HeaderAssembler` 组好）。 */
    val headers: List<Pair<String, String>>,

    /** 请求体，L1 是 null，L2 是极简推理 body。 */
    val body: String? = null,

    /** 这把 Key 当前选的客户端预设。 */
    val clientProfileId: Long? = null,

    /** 这把 Key 当前固化的鉴权风格。 */
    val authStyle: AuthStyle = AuthStyle.AUTO,

    /** 这把 Key 是否显式允许 HTTP。 */
    val allowInsecure: Boolean = false,
)

/** 一个任务的结果。 */
data class ProbeItemResult(
    val taskId: String,
    val providerId: Long,

    /** 供应商名。明细页每一项要能显示"是哪一家"，光有 id 画不出来（§13.4）。 */
    val providerName: String,
    val keyId: Long?,
    val level: ProbeLevel,
    val outcome: ProbeOutcome,

    /** 改写健康结论时非空。 */
    val health: KeyHealth? = null,
    val detail: String? = null,
    val latencyMs: Long? = null,

    /** 原始响应体。只给引擎做模型列表解析，绝不直接进 UI 或日志。 */
    val body: String? = null,
)

/** 一轮探测的进度。 */
data class ProbeProgress(
    val runId: Long,
    val running: Boolean,
    val done: Int,
    val total: Int,
    val currentHost: String? = null,
    val providerDone: Int = 0,
    val providerTotal: Int = 0,
    val providerOk: Int = 0,
    val providerFail: Int = 0,
    val keyDone: Int = 0,
    val keyTotal: Int = 0,
    val keyOk: Int = 0,
    val keyFail: Int = 0,
)

/** 探测被跳过 / 取消的原因分类（测试 14 断言用）。 */
enum class SkipReason {
    /** 撞了 host 预算。 */
    HostBudgetExhausted,

    /** 总预算超时。 */
    TotalBudgetExhausted,

    /** 本轮该 host 已经 429，可选请求停发。 */
    HostRateLimited,

    /** 被取消 / 金库锁定。 */
    Cancelled,
}

/**
 * 一轮探测的编排结果。含每个任务的结果与"为什么被跳过"。
 *
 * [results] 是**逐项**的（不是一次性返回）——编排器通过 [ProbeEngine] 的流推流，
 * 这里只是落库前的聚合形态，供测试断言。
 */
data class ProbeRunOutcome(
    val results: List<ProbeItemResult>,
    val skipped: Map<String, SkipReason>,
)

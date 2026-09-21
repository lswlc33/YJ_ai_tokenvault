package com.lc33.tokenvault.ui.common

import androidx.compose.runtime.Composable
import com.lc33.tokenvault.balance.BalanceErrorReason
import com.lc33.tokenvault.engine.BalanceRefreshOutcome
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.balance_err_bad_headers
import tokenvault.shared.generated.resources.balance_err_empty_list
import tokenvault.shared.generated.resources.balance_err_http
import tokenvault.shared.generated.resources.balance_err_key_undecryptable
import tokenvault.shared.generated.resources.balance_err_missing_field
import tokenvault.shared.generated.resources.balance_err_not_a_number
import tokenvault.shared.generated.resources.balance_err_not_found
import tokenvault.shared.generated.resources.balance_err_not_json
import tokenvault.shared.generated.resources.balance_err_rate_limited
import tokenvault.shared.generated.resources.balance_err_server
import tokenvault.shared.generated.resources.balance_err_token_missing
import tokenvault.shared.generated.resources.balance_err_token_undecryptable
import tokenvault.shared.generated.resources.balance_err_unknown
import tokenvault.shared.generated.resources.balance_err_unauthorized
import tokenvault.shared.generated.resources.balance_failed_section
import tokenvault.shared.generated.resources.balance_upstream_said
import tokenvault.shared.generated.resources.feedback_balance_all_failed
import tokenvault.shared.generated.resources.feedback_balance_nothing
import tokenvault.shared.generated.resources.feedback_balance_partial
import tokenvault.shared.generated.resources.feedback_balance_refreshed

/**
 * 一条要显示的文案：资源本身 + 它的定位参数。
 *
 * 为什么要这一层壳而不是直接写两个函数：同一份"读哪条资源、带什么参数"的判断，
 * 页面那边要用 `stringResource`（组合期）渲染，而提示那边走的是 `LaunchedEffect` 里的
 * 协程（`getString`，挂起版）。两个渲染器共用下面那一个纯函数，就不会出现
 * "卡片上写的是令牌失效、弹出来的那句却是查询失败"这种两边各改一半的分叉。
 */
data class BalanceCopy(val resource: StringResource, val args: List<Any> = emptyList())

/**
 * 把余额失败的**机器码原因**翻成人话。
 *
 * `BalanceSnapshot.error` 存的从来不是文案而是码（`http 401`、`missing_quota`、
 * [BalanceErrorReason.TOKEN_UNDECRYPTABLE]），因为写它的那一层读不到资源（红线 19）。
 * `BalanceParseException` 的 KDoc 早就写着"UI 层拿到后翻译成本地化文案"——这一件就是那笔
 * 欠账：以前没有任何一处翻译过，所以界面上"查询失败"三个字就是用户能得到的全部信息，
 * 而他真正想知道的是"到底是令牌失效了还是地址写错了"。
 *
 * 翻译只按**前缀与已知码**分派，认不出的一律落到 `balance_err_unknown` 并把码原样带出来：
 * 宁可显示一句带 `http 418` 的丑话，也不要编一个"网络错误"把真实原因盖掉（红线 35）。
 */
fun balanceFailureCopyOf(reason: String?): BalanceCopy {
    val code = reason?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: return BalanceCopy(Res.string.balance_failed_section)
    if (code.startsWith("http ")) {
        val status = code.removePrefix("http ").trim().toIntOrNull()
        if (status != null) return balanceHttpStatusCopyOf(status)
    }
    return when {
        // 各适配器抛的字段类原因，见 balance/ 目录下那六个 `BalanceParseException(reason)`。
        code == "no_json" -> BalanceCopy(Res.string.balance_err_not_json)
        code == "non_finite_amount" -> BalanceCopy(Res.string.balance_err_not_a_number)
        code == "bad_total_balance" -> BalanceCopy(Res.string.balance_err_not_a_number)
        code == "empty_balance_infos" -> BalanceCopy(Res.string.balance_err_empty_list)
        code.startsWith("bad_header") -> BalanceCopy(Res.string.balance_err_bad_headers)
        code.startsWith("missing_") ->
            BalanceCopy(Res.string.balance_err_missing_field, listOf(code.removePrefix("missing_")))
        code == BalanceErrorReason.TOKEN_MISSING ->
            BalanceCopy(Res.string.balance_err_token_missing)
        code == BalanceErrorReason.TOKEN_UNDECRYPTABLE ->
            BalanceCopy(Res.string.balance_err_token_undecryptable)
        code == BalanceErrorReason.KEY_UNDECRYPTABLE ->
            BalanceCopy(Res.string.balance_err_key_undecryptable)
        else -> BalanceCopy(Res.string.balance_err_unknown, listOf(reason.orEmpty()))
    }
}

/**
 * 按状态码分档。
 *
 * 401 与 403 合用一句：这两句是用户最常撞到的，说的是"这份凭据不被认"，
 * 而他能做的事一样——去中转站重签一个访问令牌。404 单独一句是因为它多半指向余额
 * 地址配错了，那是编辑页里能自己改好的。
 */
private fun balanceHttpStatusCopyOf(status: Int): BalanceCopy = when (status) {
    401, 403 -> BalanceCopy(Res.string.balance_err_unauthorized, listOf(status))
    404 -> BalanceCopy(Res.string.balance_err_not_found, listOf(status))
    429 -> BalanceCopy(Res.string.balance_err_rate_limited, listOf(status))
    in 500..599 -> BalanceCopy(Res.string.balance_err_server, listOf(status))
    else -> BalanceCopy(Res.string.balance_err_http, listOf(status))
}

/**
 * 一句完整话：机器码翻译 + 上游自己写的那句。
 *
 * 两句都要：`http 401` 说的是"被拒了"，而"安全访问令牌已失效"说的是"为什么"。
 * 上游没写原因时只留前者——不补一句猜测（红线 35）。
 *
 * 两个入参都来自行对象（`UiKeyRow` / `UiProviderRow` 的 `balanceErrorReason` /
 * `balanceErrorHint`），那一层已经把"只有失败才给值"和"从脱敏后的原文里抽那句话"
 * 都做完了（见 `ui/shell/UiMapping.kt`）。
 */
fun balanceFailureSentenceCopyOf(reason: String?, hint: String?): BalanceCopy {
    val label = balanceFailureCopyOf(reason)
    val said = hint?.trim()?.takeIf { it.isNotEmpty() } ?: return label
    return BalanceCopy(Res.string.balance_upstream_said, listOf(label, said))
}

/**
 * 一趟余额刷新的完整结论，给提示用。
 *
 * 为什么不能是一句固定的"余额已刷新"：`refreshAll` 原来返回的是
 * `refreshKey(key) != null` 的计数，而**失败快照也是非 null**，于是十把全挂它照样报 10，
 * 首页在余额接口一个都没通的时候念"已刷新"，而卡上的数字一个都没动。那是这个应用里
 * 唯一一句"钱查过了"的话，所以说反了最伤。
 */
fun balanceRoundCopyOf(outcome: BalanceRefreshOutcome): BalanceCopy = when {
    outcome.nothingAttempted -> BalanceCopy(Res.string.feedback_balance_nothing)
    outcome.allFailed -> BalanceCopy(
        Res.string.feedback_balance_all_failed,
        // 嵌套的那一句在这里只是"资源 + 参数"，由渲染器一次铺平，
        // 所以 `balance_upstream_said` 套 `balance_err_unauthorized` 这种两层文案
        // 在两个渲染器里走的是同一条展开路径。
        listOf(balanceFailureSentenceCopyOf(outcome.failedReason, outcome.failedHint)),
    )
    outcome.partiallyFailed -> BalanceCopy(
        Res.string.feedback_balance_partial,
        listOf(outcome.succeeded, outcome.failed),
    )
    else -> BalanceCopy(Res.string.feedback_balance_refreshed)
}

/** 只在**这一趟有东西没刷到**时才有的一句，null = 什么都不说。 */
fun balanceFailureCopyOf(outcome: BalanceRefreshOutcome): BalanceCopy? =
    if (outcome.needsAttention) balanceRoundCopyOf(outcome) else null

/**
 * 一层套一层怎么写得下去：[BalanceCopy] 的参数允许本身又是一条 [BalanceCopy]
 * （"合计之下另有一说"、"余额没查到：<原因（上游说：…）>"这类嵌套句子正好卡在第二层）。
 * 两个渲染器各自递归展开，规则一样。
 */

// ---- 组合期渲染 ----

/** 余额原因的本地化句子，给页面用。 */
@Composable
fun balanceFailureLabel(reason: String?): String = balanceFailureCopyOf(reason).renderComposed()

/** 「失败原因 + 上游原话」那一句，给余额卡用。 */
@Composable
fun balanceFailureSentence(reason: String?, hint: String?): String =
    balanceFailureSentenceCopyOf(reason, hint).renderComposed()

/** 一趟刷新的提示语，给页面直接显示。 */
@Composable
fun balanceRoundMessage(outcome: BalanceRefreshOutcome): String =
    balanceRoundCopyOf(outcome).renderComposed()

@Composable
private fun BalanceCopy.renderComposed(): String {
    val parts = args.map { if (it is BalanceCopy) it.renderComposed() else it }
    return stringResource(resource, *parts.toTypedArray())
}

// ---- 协程里渲染（提示走这条：LaunchedEffect 的 collect 不是组合上下文）----

/** [balanceRoundMessage] 的挂起版，给 `LaunchedEffect { events.collect { … } }` 用。 */
suspend fun balanceRoundMessageAsync(outcome: BalanceRefreshOutcome): String =
    balanceRoundCopyOf(outcome).renderAsync()

/**
 * [balanceFailureMessage] 的挂起版：全成功时返回 null，什么都不说。
 *
 * 用在"顶栏一键探测"这类合成动作上：那一轮本来就会由探测引擎报一次结果，
 * 余额全成功时再弹一条"已刷新"是第三条提示叠在同一个按钮上。但失败必须说——
 * 探测成功和余额查不到是两件事，前者不会替后者报信。
 */
suspend fun balanceFailureMessageAsync(outcome: BalanceRefreshOutcome): String? =
    balanceFailureCopyOf(outcome)?.renderAsync()

private suspend fun BalanceCopy.renderAsync(): String {
    val parts = args.map { if (it is BalanceCopy) it.renderAsync() else it }
    return getString(resource, *parts.toTypedArray())
}

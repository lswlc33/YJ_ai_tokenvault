package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.health_stale_this_round
import tokenvault.shared.generated.resources.manage_context
import tokenvault.shared.generated.resources.detail_key_models_refresh
import tokenvault.shared.generated.resources.detail_account_password
import tokenvault.shared.generated.resources.manage_keys_ratio
import tokenvault.shared.generated.resources.manage_latency
import tokenvault.shared.generated.resources.manage_latency_time
import tokenvault.shared.generated.resources.manage_local_only
import tokenvault.shared.generated.resources.manage_models_count
import tokenvault.shared.generated.resources.manage_pinned
import tokenvault.shared.generated.resources.login_method_github
import tokenvault.shared.generated.resources.login_method_linuxdo
import tokenvault.shared.generated.resources.protocol_anthropic
import tokenvault.shared.generated.resources.protocol_chat
import tokenvault.shared.generated.resources.protocol_responses
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.screens.model.UiAccountRow
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiKeyRow
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.screens.model.UiProviderRow
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.common.labelOf
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppBasicRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppIconTint
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appOnPrimaryColor
import com.lc33.tokenvault.ui.miuix.appPrimaryColor
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTrackColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalProviderPalette

@Composable
private fun rowModifier(): Modifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = LocalAppTokens.current.screenPadding)

/** 协议 chip 的展示文案。底层的 wireName 不直接给用户看。 */
@Composable
internal fun protocolLabel(protocol: Protocol): String = stringResource(
    when (protocol) {
        Protocol.CHAT -> Res.string.protocol_chat
        Protocol.RESPONSES -> Res.string.protocol_responses
        Protocol.ANTHROPIC -> Res.string.protocol_anthropic
    },
)

@Composable
internal fun protocolLabel(wireName: String): String =
    Protocol.fromWireName(wireName)?.let { protocolLabel(it) } ?: wireName

/** 色块 + 首字母。长列表里认行靠它，不承担任何状态语义。 */
@Composable
private fun ColorBadge(name: String, colorIndex: Int) {
    val palette = LocalProviderPalette.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.swatchFor(colorIndex)),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = name.take(1).uppercase(),
            style = AppTextStyle.Subtitle,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun ProviderRow(
    row: UiProviderRow,
    selecting: Boolean,
    selected: Boolean,
    onClick: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = rowModifier(),
        onClick = { onClick(row.id) },
        onLongPress = if (selecting) null else { -> onLongPress(row.id) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 多选态：左侧是对勾；非多选态：左侧是色块。二者互斥，不叠。
            if (selecting) {
                SelectionMark(selected)
            } else {
                ColorBadge(row.name, row.colorIndex)
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // fill = false：名字长了就自己截，但**不许把角标挤掉**——
                    // 否则"置顶"会被压成"P"，标签比没标签更让人困惑
                    AppText(
                        text = row.name,
                        style = AppTextStyle.Body,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.pinned) AppChip(text = stringResource(Res.string.manage_pinned))
                }
                val note = row.note
                if (note != null) {
                    AppText(
                        text = note,
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        maxLines = 1,
                    )
                }
                AppText(
                    text = row.websiteUrl ?: row.host,
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val balance = row.balance
                if (balance != null) {
                    // 有了金额就不再画"可用"：能查出余额必然可达，两个结论并排反而像
                    // 在说两件事。延迟保留——它说的不是"通不通"而是"多快"。
                    AppText(
                        text = "${balance.currency} ${balance.amount}",
                        style = AppTextStyle.Body,
                        maxLines = 1,
                    )
                } else {
                    StatusDot(color = colorOf(row.health), label = labelOf(row.health))
                }
                row.reachabilityLatencyMs?.let { latency ->
                    AppText(
                        text = stringResource(Res.string.manage_latency, latency),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                    )
                }
                if (row.staleThisRound) {
                    // 红线 11：瞬时失败不改写健康结论，但要让用户知道"这一轮没验证成"
                    AppText(
                        text = stringResource(Res.string.health_stale_this_round),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        maxLines = 1,
                    )
                }
            }
        }
        // 协议不在这里画：它属于 Key（同一家可以有的 Key 走 Chat、有的走 Anthropic），
        // 在一张合集卡片上并成一排 chip 只能表达"这家用过这些协议"，读起来却像"这家支持这些"。
        // 要看协议就进详情页——那里按 Key 说清楚。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppText(
                text = stringResource(Res.string.manage_keys_ratio, row.okKeyCount, row.keyCount),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
            AppText(
                text = stringResource(Res.string.manage_models_count, row.modelCount),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
        }
        row.keys.take(2).forEach { key ->
            KeySummaryRow(key)
        }
        if (row.keys.size > 2) {
            AppText(
                text = "+${row.keys.size - 2}",
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
        }
    }
}

/** 多选态左侧的对勾。选中实心、未选中空心。 */
@Composable
private fun SelectionMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) appPrimaryColor
                else appTrackColor,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AppIconTint(
            icon = AppIcon.Ok,
            size = 18.dp,
            tint = if (selected) appOnPrimaryColor else appSecondaryTextColor,
        )
    }
}

/**
 * 密钥行。供应商预览与密钥预览两处共用，行里只有三样东西：
 *
 * ```
 * 备注
 * sk-xxxx…xxxx        ● 可达性 >
 * 222 毫秒 · 3 分钟前
 * ```
 *
 * 状态在右侧与箭头同列（用户点名的要求：原来挤在左边，备注一长就换行）。
 *
 * 三条边界：
 *
 * - **余额不在这里**。它是独立的一块（供应商预览有合计卡，密钥预览有单独的卡），
 *   混进这行会让"这一行说的是什么"变得说不清。
 * - **不在这里写密钥名称**：名称是卡片/页面的标题级信息，行里放不下两段标题。
 * - 时间用 [relativeLabel]，相对时间的文案与分档都在 `strings.xml` 里。
 */
@Composable
internal fun KeyRow(
    row: UiKeyRow,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val latencyText = row.latencyMs?.let { stringResource(Res.string.manage_latency, it) }
    val checkedText = row.checkedAt?.let { relativeLabel(nowMs, it) }
    val timingText = when {
        latencyText != null && checkedText != null ->
            stringResource(Res.string.manage_latency_time, latencyText, checkedText)
        latencyText != null -> latencyText
        else -> checkedText
    }

    AppBasicRow(
        modifier = modifier,
        onClick = onClick,
        endActions = {
            StatusDot(color = colorOf(row.health), label = labelOf(row.health))
            AppIconTint(icon = AppIcon.Forward, size = 18.dp, tint = appSecondaryTextColor)
        },
    ) {
        if (row.note.isNotBlank()) {
            AppText(
                text = row.note,
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                maxLines = 1,
            )
        }
        AppText(
            text = row.masked,
            style = AppTextStyle.Body,
            fontFamily = tokens.monoFontFamily,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (timingText != null) {
            AppText(
                text = timingText,
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 模型行。
 *
 * 形状由四条决定：
 *
 * 1. **探测结论挪到右侧**：结论就一个词（可用 / 未探测），单独占一行会把每个模型撑到
 *    三行高，一屏看不了几个。放到右侧与整行上下居中后，模型行稳定在两行。
 * 2. **没开模型可达探测就不画结论**（[showProbe]）：那种情况下结论恒为「未探测」，
 *    摆一排灰点是在反复说明"这里没有信息"。右侧那时是空的，也省掉了那点宽度。
 * 3. **协议可选**（[showProtocol]）：协议在一份模型列表里往往整列相同（就是一个站
 *    提供的接口形态），一屏排二十个一模一样的 chip 只是在占地方。供应商预览页不画它。
 * 4. **自动获取的列表不可点**：[onClick] 为空时不画箭头、不响应点击——列表由上游同步
 *    维护，改一个下次同步就会被覆盖的字段没有意义（红线 13）。
 */
@Composable
internal fun ModelRow(
    row: UiModelRow,
    showProbe: Boolean,
    showProtocol: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    AppBasicRow(
        modifier = modifier,
        onClick = onClick,
        onLongPress = onLongPress,
        endActions = {
            if (showProbe) {
                StatusDot(color = colorOf(row.health), label = labelOf(row.health))
            }
            // 箭头只在"点得动"时出现：它在这套界面里的含义是"还能进下一页 / 打开编辑"。
            if (onClick != null) {
                AppIconTint(icon = AppIcon.Forward, size = 18.dp, tint = appSecondaryTextColor)
            }
        },
    ) {
        AppText(
            text = row.modelId,
            style = AppTextStyle.Body,
            fontFamily = tokens.monoFontFamily,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showProtocol) {
                AppChip(text = protocolLabel(row.protocol))
            }
            row.contextLabel?.let { context ->
                AppText(
                    text = stringResource(Res.string.manage_context, context),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun loginMethodLabel(method: LoginMethod): String = when (method) {
    LoginMethod.GITHUB -> stringResource(Res.string.login_method_github)
    LoginMethod.LINUX_DO -> stringResource(Res.string.login_method_linuxdo)
}@Composable
internal fun AccountRow(
    row: UiAccountRow,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    AppBasicRow(
        modifier = modifier,
        onClick = onClick,
        endActions = {
            AppChip(text = stringResource(Res.string.manage_local_only))
            AppIconTint(icon = AppIcon.Forward, size = 18.dp, tint = appSecondaryTextColor)
        },
    ) {
        AppText(text = row.label, style = AppTextStyle.Body, maxLines = 1)
        AppText(
            text = row.maskedUsername,
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            fontFamily = tokens.monoFontFamily,
            maxLines = 1,
        )
        row.note?.let { note ->
            AppText(
                text = note,
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                maxLines = 1,
            )
        }
        if (row.loginMethods.isNotEmpty() || row.hasPassword) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.loginMethods.forEach { wire ->
                    LoginMethod.fromWireName(wire)?.let { method ->
                        AppChip(text = loginMethodLabel(method))
                    }
                }
                if (row.hasPassword) AppChip(text = stringResource(Res.string.detail_account_password))
            }
        }
    }
}

@Composable
private fun KeySummaryRow(key: UiKeyRow) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = "${key.sortOrder + 1} ${key.label.ifBlank { key.masked }}",
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        StatusDot(color = colorOf(key.health), label = labelOf(key.health))
    }
}
package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.health_stale_this_round
import tokenvault.shared.generated.resources.balance_failed_section
import tokenvault.shared.generated.resources.manage_context
import tokenvault.shared.generated.resources.detail_key_balance_value
import tokenvault.shared.generated.resources.detail_key_models_refresh
import tokenvault.shared.generated.resources.detail_account_password
import tokenvault.shared.generated.resources.manage_default_key
import tokenvault.shared.generated.resources.manage_disabled
import tokenvault.shared.generated.resources.manage_keys_ratio
import tokenvault.shared.generated.resources.manage_latency
import tokenvault.shared.generated.resources.manage_latency_time
import tokenvault.shared.generated.resources.manage_local_only
import tokenvault.shared.generated.resources.manage_models_count
import tokenvault.shared.generated.resources.manage_pinned
import tokenvault.shared.generated.resources.manage_source_discovered
import tokenvault.shared.generated.resources.login_method_github
import tokenvault.shared.generated.resources.login_method_linuxdo
import tokenvault.shared.generated.resources.manage_source_manual
import tokenvault.shared.generated.resources.detail_probe_key
import tokenvault.shared.generated.resources.detail_probe_model_cd
import tokenvault.shared.generated.resources.protocol_anthropic
import tokenvault.shared.generated.resources.protocol_chat
import tokenvault.shared.generated.resources.protocol_responses
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.screens.model.UiAccountRow
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiKeyRow
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.screens.model.UiModelSource
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
                    text = row.host,
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val balance = row.balance
                if (balance != null) {
                    AppText(
                        text = "${balance.currency} ${balance.amount}",
                        style = AppTextStyle.Body,
                        maxLines = 1,
                    )
                }
                StatusDot(color = colorOf(row.health), label = labelOf(row.health))
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            row.protocols.forEach { protocol -> AppChip(text = protocolLabel(protocol)) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
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

@Composable
internal fun KeyRow(
    row: UiKeyRow,
    nowMs: Long,
    onClick: () -> Unit,
    onProbe: () -> Unit,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppIconButton(
                    icon = AppIcon.Probe,
                    contentDescription = stringResource(Res.string.detail_probe_key),
                    onClick = onProbe,
                )
                AppIconTint(icon = AppIcon.Forward, size = 18.dp, tint = appSecondaryTextColor)
            }
        },
    ) {
        AppText(text = row.label, style = AppTextStyle.Body, maxLines = 1)
        AppText(
            text = row.masked,
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            fontFamily = tokens.monoFontFamily,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(color = colorOf(row.health), label = labelOf(row.health))
            val balance = row.balance
            when {
                balance != null -> AppText(
                    text = stringResource(
                        Res.string.detail_key_balance_value,
                        balance.currency,
                        balance.amount,
                    ),
                    style = AppTextStyle.Footnote,
                )
                row.balanceFailed -> AppText(
                    text = stringResource(Res.string.balance_failed_section),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                )
            }
        }
        if (timingText != null) {
            AppText(
                text = timingText,
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
        }
    }
}

@Composable
internal fun ModelRow(
    row: UiModelRow,
    onClick: (() -> Unit)? = null,
    onProbe: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    AppBasicRow(
        modifier = modifier,
        onClick = onClick,
        onLongPress = onLongPress,
        endActions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onProbe != null) {
                    AppIconButton(
                        icon = AppIcon.Probe,
                        contentDescription = stringResource(Res.string.detail_probe_model_cd),
                        onClick = onProbe,
                    )
                }
                AppIconTint(icon = AppIcon.Forward, size = 18.dp, tint = appSecondaryTextColor)
            }
        },
    ) {
        AppText(
            text = row.modelId,
            style = AppTextStyle.Body,
            fontFamily = tokens.monoFontFamily,
            color = if (row.enabled) Color.Unspecified else appSecondaryTextColor,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppChip(text = protocolLabel(row.protocol))
            AppChip(
                text = stringResource(
                    when (row.source) {
                        UiModelSource.Manual -> Res.string.manage_source_manual
                        UiModelSource.Discovered -> Res.string.manage_source_discovered
                    },
                ),
            )
            if (!row.enabled) AppChip(text = stringResource(Res.string.manage_disabled))
        }
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(color = colorOf(row.health), label = labelOf(row.health))
            row.contextLabel?.let { context ->
                AppText(
                    text = stringResource(Res.string.manage_context, context),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
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
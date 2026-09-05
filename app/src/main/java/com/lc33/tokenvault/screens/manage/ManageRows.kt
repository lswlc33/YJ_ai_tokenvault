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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.UiAccountRow
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiKeyRow
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.screens.model.UiModelSource
import com.lc33.tokenvault.screens.model.UiProviderRow
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.common.colorOf
import com.lc33.tokenvault.ui.common.labelOf
import com.lc33.tokenvault.ui.common.relativeBucketOf
import com.lc33.tokenvault.ui.common.relativeTimeLabel
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalProviderPalette

@Composable
private fun rowModifier(): Modifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = LocalAppTokens.current.screenPadding)

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
internal fun ProviderRow(row: UiProviderRow, onClick: (Long) -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(modifier = rowModifier(), onClick = { onClick(row.id) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            ColorBadge(row.name, row.colorIndex)
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
                    if (row.pinned) AppChip(text = stringResource(R.string.manage_pinned))
                }
                if (row.note != null) {
                    AppText(
                        text = row.note,
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
                if (row.balance != null) {
                    AppText(
                        text = "${row.balance.currency} ${row.balance.amount}",
                        style = AppTextStyle.Body,
                        maxLines = 1,
                    )
                }
                StatusDot(color = colorOf(row.health), label = labelOf(row.health))
                if (row.staleThisRound) {
                    // 红线 11：瞬时失败不改写健康结论，但要让用户知道"这一轮没验证成"
                    AppText(
                        text = stringResource(R.string.health_stale_this_round),
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
            row.protocols.forEach { protocol -> AppChip(text = protocol) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppText(
                text = stringResource(R.string.manage_keys_ratio, row.okKeyCount, row.keyCount),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
            AppText(
                text = stringResource(R.string.manage_models_count, row.modelCount),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
        }
    }
}

@Composable
internal fun KeyRow(row: UiKeyRow, nowMs: Long, onClick: () -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(modifier = rowModifier(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppText(
                        text = row.label,
                        style = AppTextStyle.Body,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.isDefault) AppChip(text = stringResource(R.string.manage_default_key))
                }
                // 只有遮蔽串。明文是借 DEK 现算的（§6.1 推论 3），UiKeyRow 里没有明文字段，
                // 所以这里连"想画明文"都做不到。
                AppText(
                    text = row.masked,
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    fontFamily = tokens.monoFontFamily,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusDot(color = colorOf(row.health), label = labelOf(row.health))
                if (row.latencyMs != null) {
                    AppText(
                        text = stringResource(R.string.manage_latency, row.latencyMs),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                    )
                }
                if (row.checkedAt != null) {
                    // 分档是纯函数、文案在资源里，所以"算"在这里而不是在 ViewModel（它拿不到资源）
                    AppText(
                        text = relativeTimeLabel(relativeBucketOf(nowMs, row.checkedAt)),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ModelRow(row: UiModelRow, onClick: () -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(modifier = rowModifier(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
                    AppChip(text = row.protocol)
                    AppChip(
                        text = stringResource(
                            when (row.source) {
                                UiModelSource.Manual -> R.string.manage_source_manual
                                UiModelSource.Discovered -> R.string.manage_source_discovered
                            },
                        ),
                    )
                    if (!row.enabled) AppChip(text = stringResource(R.string.manage_disabled))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusDot(color = colorOf(row.health), label = labelOf(row.health))
                if (row.contextLabel != null) {
                    AppText(
                        text = stringResource(R.string.manage_context, row.contextLabel),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AccountRow(row: UiAccountRow, onClick: () -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(modifier = rowModifier(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(text = row.label, style = AppTextStyle.Body, maxLines = 1)
                // 账号给遮蔽串，密码**连遮蔽串都不给**——它只在展开时现算（红线 21）
                AppText(
                    text = row.maskedUsername,
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    fontFamily = tokens.monoFontFamily,
                    maxLines = 1,
                )
            }
            AppChip(text = stringResource(R.string.manage_local_only))
        }
    }
}

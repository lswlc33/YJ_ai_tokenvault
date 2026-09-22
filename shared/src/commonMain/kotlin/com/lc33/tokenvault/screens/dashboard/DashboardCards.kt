package com.lc33.tokenvault.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.count_keys
import tokenvault.shared.generated.resources.count_models
import tokenvault.shared.generated.resources.count_providers
import tokenvault.shared.generated.resources.dashboard_balance_failed
import tokenvault.shared.generated.resources.dashboard_balance_failed_keys
import tokenvault.shared.generated.resources.dashboard_balance_low
import tokenvault.shared.generated.resources.dashboard_balance_none
import tokenvault.shared.generated.resources.dashboard_balance_refreshing
import tokenvault.shared.generated.resources.dashboard_balance_updated
import tokenvault.shared.generated.resources.dashboard_counts_title
import tokenvault.shared.generated.resources.dashboard_probe_counts
import tokenvault.shared.generated.resources.dashboard_probe_key_label
import tokenvault.shared.generated.resources.dashboard_probe_last_updated
import tokenvault.shared.generated.resources.dashboard_probe_provider_label
import tokenvault.shared.generated.resources.dashboard_probe_detail
import tokenvault.shared.generated.resources.dashboard_probe_never
import tokenvault.shared.generated.resources.dashboard_probe_no_autolock
import tokenvault.shared.generated.resources.dashboard_probe_running
import tokenvault.shared.generated.resources.dashboard_probe_title
import tokenvault.shared.generated.resources.balance_trend_title
import tokenvault.shared.generated.resources.balance_trend_summary
import tokenvault.shared.generated.resources.model_change_title
import tokenvault.shared.generated.resources.model_change_summary
import tokenvault.shared.generated.resources.refresh_cd
import com.lc33.tokenvault.screens.model.BalanceSummary
import com.lc33.tokenvault.screens.model.ContentCounts
import com.lc33.tokenvault.screens.model.DashboardUiState
import com.lc33.tokenvault.ui.common.StatTile
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppAccentCard
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appOnPrimaryColor
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/** 卡片统一的外边距。仪表盘每块卡都用它，间距只在一处定义。 */
@Composable
private fun cardModifier(): Modifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = LocalAppTokens.current.screenPadding)

@Composable
private fun CardTitle(text: String) {
    AppText(text = text, style = AppTextStyle.Subtitle)
}

@Composable
internal fun BalanceCard(
    balance: BalanceSummary,
    nowMs: Long,
    /** 这一趟查询在跑：图标置灰，同时在卡上说出为什么——只灰不给理由是让人反复按。 */
    refreshing: Boolean,
    /** 余额低于其币种阈值的供应商数（来自 `attentionItemsOf`，0 时这句不画）。 */
    lowBalanceCount: Int,
    onRefresh: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    // 实色主色底。曾经试过玻璃材质（背景模糊 + vibrancy + 动态光斑）来"彰显高级感"，
    // 结果是显示异常，已按用户要求回退——这条路上还有一个坑：被采样的背景层若包含
    // 卡片自己，渲染树每帧加深，几秒后 RenderThread 栈溢出闪退。
    AppAccentCard(modifier = cardModifier()) {
        // 余额数字就是这张卡的标题：这一页要回答的是"我还剩多少"，而「余额」两个字只是
        // 它自己的标签，占一行不增加任何信息。更新时间与两句提醒都退到数字下面。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (balance.perCurrency.isEmpty()) {
                    AppText(
                        text = stringResource(Res.string.dashboard_balance_none),
                        style = AppTextStyle.Subtitle,
                        color = appOnPrimaryColor,
                    )
                } else {
                    balance.perCurrency.forEachIndexed { index, money ->
                        AppText(
                            text = "${money.currency} ${money.amount}",
                            // 第一个币种放大，其余小一号：不做汇率换算，所以没有"总额"可以放大
                            style = if (index == 0) AppTextStyle.Title else AppTextStyle.Body,
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 2.dp),
                        )
                    }
                }
            }
            AppIconButton(
                icon = AppIcon.Refresh,
                contentDescription = stringResource(Res.string.refresh_cd),
                onClick = onRefresh,
                enabled = !refreshing,
            )
        }
        val updatedAt = balance.updatedAt
        if (updatedAt != null) {
            // 分档在纯函数里、文案在资源里（RelativeTime.kt 就是为此拆开的），
            // 所以 ViewModel 给的是时间戳而不是一句“12 分钟前”
            AppText(
                text = stringResource(
                    Res.string.dashboard_balance_updated,
                    relativeLabel(nowMs, updatedAt),
                ),
                style = AppTextStyle.Footnote,
                color = appOnPrimaryColor,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
        }
        if (refreshing) {
            // 查询要几秒钟，这一句是那段等待里唯一的解释：图标灰了而没说为什么，
            // 读起来就像按钮坏了。
            AppText(
                text = stringResource(Res.string.dashboard_balance_refreshing),
                style = AppTextStyle.Footnote,
                color = appOnPrimaryColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 两句提醒压成半透明的说明文字：它们说的是"这几家要去看一眼"，不是余额本身，
        // 与数字同色同量级时会被读成另一笔账。蓝底上没有"次要文字色"可取，用 onPrimary
        // 的半透明档，与 `ui/miuix/liquid` 那几处同一手法。
        val descColor = appOnPrimaryColor.copy(alpha = 0.72f)
        if (balance.failedProviderCount > 0) {
            AppText(
                text = stringResource(Res.string.dashboard_balance_failed, balance.failedProviderCount),
                style = AppTextStyle.Footnote,
                color = descColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 部分失败单独一句：`failedProviderCount` 只数"整家都没查到"的那些，
        // 一家三把里过期一把在它那里是 0，首页就一个字都不提。
        if (balance.failedKeyCount > 0) {
            AppText(
                text = stringResource(Res.string.dashboard_balance_failed_keys, balance.failedKeyCount),
                style = AppTextStyle.Footnote,
                color = descColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (lowBalanceCount > 0) {
            // 「探测设置 → 余额低额阈值」那一页唯一的出口。以前阈值只喂给
            // `attentionItemsOf`，算出来的 `DashboardUiState.attention` 全仓没有任何一处
            // 读取——设了 ¥30、掉到 ¥5，界面上一个字都不会变，那一页等于白填。
            // 摆在失败那一句旁边：两个数说的都是"这几家要去看一眼"。
            AppText(
                text = stringResource(Res.string.dashboard_balance_low, lowBalanceCount),
                style = AppTextStyle.Footnote,
                color = descColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    // 这里不再有「查看余额明细」入口（2026-09 决策）：明细页只是把卡上那几个数按供应商
    // 摊开，逐家的余额与失败原因在供应商详情页看得更全，多一座二级页反而像数据丢了。
}

/**
 * 趋势两页的入口：余额趋势与模型变化。
 *
 * **一个组里两行**，不做两张大卡：这两页是并列的"想看才看"的分析内容，各摊一张卡会把
 * 首页垫高两块，而缩成一行又会把第二页藏进第一页里。行标题就是页名，跳去哪一眼对得上。
 */
@Composable
internal fun TrendEntryCard(
    onOpenBalanceTrend: () -> Unit,
    onOpenModelChange: () -> Unit,
) {
    AppPreferenceGroup(modifier = cardModifier(), inset = false) {
        AppArrowRow(
            title = stringResource(Res.string.balance_trend_title),
            summary = stringResource(Res.string.balance_trend_summary),
            onClick = onOpenBalanceTrend,
        )
        AppArrowRow(
            title = stringResource(Res.string.model_change_title),
            summary = stringResource(Res.string.model_change_summary),
            onClick = onOpenModelChange,
        )
    }
}

@Composable
internal fun CountsCard(counts: ContentCounts, onOpenManage: () -> Unit) {
    val tokens = LocalAppTokens.current
    // 整张卡可点，不做"每格跳到各自的表"：管理页只有供应商一张表，
    // 四格分别跳会是假的路径指示。
    AppCard(modifier = cardModifier(), onClick = onOpenManage) {
        CardTitle(stringResource(Res.string.dashboard_counts_title))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.itemSpacing),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatTile(value = counts.providers.toString(), label = stringResource(Res.string.count_providers))
            StatTile(value = counts.keys.toString(), label = stringResource(Res.string.count_keys))
            StatTile(value = counts.models.toString(), label = stringResource(Res.string.count_models))
        }
    }
}

/**
 * 探测区。**拆成多张卡**：状态卡（上一轮 / 进行中）与两张进度卡（供应商 / 密钥）。
 *
 * 以前三块挤在一张卡里，标签、计数、细进度条一行接一行，读起来分不清哪条属于谁；
 * 拆开之后每张卡只讲一件事，各自有完整的卡片内边距。
 *
 * **这一区不放"开始探测"**：发起动作收敛在顶栏刷新那一处（探测要花钱，两个入口更贵）。
 * 只有一处例外——「查看明细」在轮次进行中也要给：「停止探测」只存在于明细页，
 * 藏起这个入口就等于没有取消这条路。
 */
@Composable
internal fun ProbeCard(
    state: DashboardUiState,
    onOpenDetail: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val progress = state.progress
    val lastRun = state.lastRun
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        AppCard(modifier = cardModifier()) {
            CardTitle(stringResource(Res.string.dashboard_probe_title))
            when {
                progress != null -> {
                    AppText(
                        text = stringResource(
                            Res.string.dashboard_probe_running,
                            progress.done,
                            progress.total,
                            progress.currentLabel,
                        ),
                        style = AppTextStyle.Secondary,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                    // §7.4：探测进行中要暂停前台空闲锁定，而这件事必须让用户看见，
                    // 否则"为什么它没锁"会被当成 bug。
                    AppText(
                        text = stringResource(Res.string.dashboard_probe_no_autolock),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                }
                lastRun == null -> {
                    AppText(
                        text = stringResource(Res.string.dashboard_probe_never),
                        style = AppTextStyle.Secondary,
                        color = appSecondaryTextColor,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                }
                else -> {
                    AppText(
                        text = stringResource(
                            Res.string.dashboard_probe_last_updated,
                            relativeLabel(state.nowMs, lastRun.finishedAtMs),
                        ),
                        style = AppTextStyle.Secondary,
                        modifier = Modifier.padding(top = tokens.itemSpacing),
                    )
                }
            }
        }

        // 从未探测过时不铺两张全 0 的进度卡——那只是噪音，用户还没有可以看的结果。
        val providerData = progress?.let {
            ProbeMetric(it.providerFraction, it.providerTotal, it.providerSucceeded, it.providerFailed, (it.providerTotal - it.providerDone).coerceAtLeast(0))
        } ?: lastRun?.let {
            ProbeMetric(
                fraction = if (it.providerTotal <= 0) 0f else it.providerSucceeded.toFloat() / it.providerTotal.toFloat(),
                total = it.providerTotal,
                succeeded = it.providerSucceeded,
                failed = it.providerFailed,
                skipped = it.providerSkipped,
            )
        }
        val keyData = progress?.let {
            ProbeMetric(it.keyFraction, it.keyTotal, it.keySucceeded, it.keyFailed, (it.keyTotal - it.keyDone).coerceAtLeast(0))
        } ?: lastRun?.let {
            ProbeMetric(
                fraction = if (it.keyTotal <= 0) 0f else it.keySucceeded.toFloat() / it.keyTotal.toFloat(),
                total = it.keyTotal,
                succeeded = it.keySucceeded,
                failed = it.keyFailed,
                skipped = it.keySkipped,
            )
        }
        providerData?.let {
            ProbeMetricCard(
                title = stringResource(Res.string.dashboard_probe_provider_label),
                metric = it,
            )
        }
        keyData?.let {
            ProbeMetricCard(
                title = stringResource(Res.string.dashboard_probe_key_label),
                metric = it,
            )
        }
        // 「查看明细」放探测区最下面：它是回看上一轮结果，摆在进度卡之前会把
        // "看进度"和"看结果"两件事的先后顺序颠倒。从未探测过时没有明细分页可看。
        //
        // 但**一轮正在跑的时候这一行必须给**（旧条件是 `lastRun != null && progress == null`，
        // 等于偏偏在 running 时把它藏起来）：「停止探测」这个按钮只存在于明细页，而顶栏那个
        // 刷新不会取消正在跑的轮次（running 时它直接不发）。于是从仪表盘发起一轮之后的一百多
        // 秒里，用户既看不到逐项进展、也没有任何地方能停——想停只能等或者杀进程。
        if (lastRun != null || progress != null) {
            // 用 AppPreferenceGroup 而不是 AppCard：卡片默认还叠一层 16dp 内边距，
            // 56dp 的行会被撑到 88dp，明显比其它块胖（见 AppPreferenceGroup 注释）。
            AppPreferenceGroup(modifier = cardModifier(), inset = false) {
                AppActionRow(
                    text = stringResource(Res.string.dashboard_probe_detail),
                    onClick = onOpenDetail,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 一张进度卡的数据。拆出来是为了让"进行中"与"上一轮结果"两条路径共用同一个渲染。 */
private data class ProbeMetric(
    val fraction: Float,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
)

@Composable
private fun ProbeMetricCard(title: String, metric: ProbeMetric) {
    val tokens = LocalAppTokens.current
    AppCard(modifier = cardModifier()) {
        CardTitle(title)
        AppText(
            text = stringResource(
                Res.string.dashboard_probe_counts,
                metric.total,
                metric.succeeded,
                metric.failed,
                metric.skipped,
            ),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = 2.dp),
        )
        AppLinearProgress(
            progress = metric.fraction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

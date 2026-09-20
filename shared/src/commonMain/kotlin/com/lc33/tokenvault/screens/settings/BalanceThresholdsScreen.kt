package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lc33.tokenvault.ui.common.LoadingState
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.probe_thresholds
import tokenvault.shared.generated.resources.probe_thresholds_cny
import tokenvault.shared.generated.resources.probe_thresholds_hint
import tokenvault.shared.generated.resources.probe_thresholds_invalid
import tokenvault.shared.generated.resources.probe_thresholds_save
import tokenvault.shared.generated.resources.probe_thresholds_usd
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.shell.BalanceThresholdsViewModel
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 余额低额阈值编辑（§9.3）。
 *
 * 只编辑 USD / CNY 两个币种：这是默认阈值里仅有的两个，也是中转站余额最常见的币种。
 * 其它币种没有阈值、也永远不判 LOW（红线 15：查不到该币种时不猜阈值）。
 *
 * 输入框初值从 [BalanceThresholdsViewModel.thresholds] 的当前值取，用 `remember(key)`
 * 绑定：一旦库里的值被别处改写（理论上只有本页会写），输入框会重置——但正常情况下一页
 * 只有一个实例，这条键只是防御。
 */
@Composable
fun BalanceThresholdsScreen(
    viewModel: BalanceThresholdsViewModel,
    onBack: () -> Unit,
) {
    val thresholds by viewModel.thresholds.collectAsStateWithLifecycle()

    // 阈值还没读到（Room 首帧是异步的）时也要把外壳画出来：以前这里是 `?: return`，
    // 于是点进这一页先是一片纯白，没有顶栏也没有返回箭头，看起来像卡死了。
    val loaded = thresholds
    if (loaded == null) {
        SettingsSubPage(titleRes = Res.string.probe_thresholds, onBack = onBack) {
            item { LoadingState() }
        }
    } else {
        ThresholdsEditor(loaded = loaded, viewModel = viewModel, onBack = onBack)
    }
}

/**
 * 读到阈值之后的编辑区。拆出来只为了 [loaded] 非空——两个输入框的初值只在首次组合
 * 取一次，必须在真实值到手之后才建立。
 */
@Composable
private fun ThresholdsEditor(
    loaded: Map<String, Double>,
    viewModel: BalanceThresholdsViewModel,
    onBack: () -> Unit,
) {
    val tokens = LocalAppTokens.current

    // 初值只在首次组合时取一次：之后用户自己改的内容不能被库里推上来的旧值覆盖。
    val usdState = rememberAppTextFieldState(trimZero(loaded["USD"] ?: BalanceSnapshot.DEFAULT_THRESHOLDS["USD"]!!))
    val cnyState = rememberAppTextFieldState(trimZero(loaded["CNY"] ?: BalanceSnapshot.DEFAULT_THRESHOLDS["CNY"]!!))
    var showError by remember { mutableStateOf(false) }

    // 动过任何一个框就把错误收回。以前 `showError` 只会被置 true、永远不会回 false，
    // 于是一次点错之后两个格子一路标红到退出这一页——包括那个本来就填对的。
    LaunchedEffect(usdState, cnyState) {
        snapshotFlow { usdState.text to cnyState.text }
            .drop(1)
            .collect { showError = false }
    }

    // 红只给真正填错的那一格：两个框一起红是在说"这一页有错"，而不是"哪一格有错"。
    val usdInvalid = showError && viewModel.parse(usdState.text) == null
    val cnyInvalid = showError && viewModel.parse(cnyState.text) == null

    // 退出这一页等的是"落库成功"事件，而不是 save() 的返回值：
    // 先退再写会让写失败发生在用户已经离开之后。
    LaunchedEffect(viewModel) {
        viewModel.saved.collect { onBack() }
    }

    SettingsSubPage(titleRes = Res.string.probe_thresholds, onBack = onBack) {
        item {
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
            ) {
                AppText(
                    text = stringResource(Res.string.probe_thresholds_hint),
                    style = AppTextStyle.Secondary,
                    color = appSecondaryTextColor,
                )
            }
        }

        item {
            AppTextField(
                state = usdState,
                label = stringResource(Res.string.probe_thresholds_usd),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
                errorText = if (usdInvalid) stringResource(Res.string.probe_thresholds_invalid) else null,
            )
        }

        item {
            AppTextField(
                state = cnyState,
                label = stringResource(Res.string.probe_thresholds_cny),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
                errorText = if (cnyInvalid) stringResource(Res.string.probe_thresholds_invalid) else null,
            )
        }

        item {
            AppActionRow(
                text = stringResource(Res.string.probe_thresholds_save),
                onClick = {
                    when (viewModel.save(usdState.text, cnyState.text)) {
                        BalanceThresholdsViewModel.SaveResult.Invalid -> showError = true
                        // Accepted 只代表写入已发起，退回由 [saved] 事件驱动。
                        BalanceThresholdsViewModel.SaveResult.Accepted -> Unit
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
            )
        }
    }
}

/** `5.0` → `5`，`30.0` → `30`：整数不带小数点，输入框里看起来干净。 */
private fun trimZero(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

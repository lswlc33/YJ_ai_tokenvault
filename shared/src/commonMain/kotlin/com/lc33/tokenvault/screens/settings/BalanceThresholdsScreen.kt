package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    // 阈值还没读到（Room 首帧是异步的）时不建输入框：`remember` 的初值只在首次组合算一次，
    // 拿默认 5/30 建框之后真实值到了也不会更新，等于把已保存的值悄悄吞掉。
    val loaded = thresholds ?: return

    val tokens = LocalAppTokens.current

    // 初值只在首次组合时取一次：之后用户自己改的内容不能被库里推上来的旧值覆盖。
    val usdState = rememberAppTextFieldState(trimZero(loaded["USD"] ?: BalanceSnapshot.DEFAULT_THRESHOLDS["USD"]!!))
    val cnyState = rememberAppTextFieldState(trimZero(loaded["CNY"] ?: BalanceSnapshot.DEFAULT_THRESHOLDS["CNY"]!!))
    var showError by remember { mutableStateOf(false) }

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
                errorText = if (showError) stringResource(Res.string.probe_thresholds_invalid) else null,
            )
        }

        item {
            AppTextField(
                state = cnyState,
                label = stringResource(Res.string.probe_thresholds_cny),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
                errorText = if (showError) stringResource(Res.string.probe_thresholds_invalid) else null,
            )
        }

        item {
            AppActionRow(
                text = stringResource(Res.string.probe_thresholds_save),
                onClick = {
                    when (viewModel.save(usdState.text, cnyState.text)) {
                        BalanceThresholdsViewModel.SaveResult.Invalid -> showError = true
                        BalanceThresholdsViewModel.SaveResult.Saved -> onBack()
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

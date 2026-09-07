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
import tokenvault.shared.generated.resources.probe_proxy
import tokenvault.shared.generated.resources.probe_proxy_hint
import tokenvault.shared.generated.resources.probe_proxy_invalid
import tokenvault.shared.generated.resources.probe_thresholds_save
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.shell.ProxyViewModel
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 手动 HTTP 代理编辑（§7.5）。
 *
 * 单个 `host:port` 输入框，空 = 走系统代理。保存后立即生效（`ProxyProvider` 订阅了
 * 设置流，下次请求就用新代理，不必重建 client）。
 */
@Composable
fun ProxyScreen(
    viewModel: ProxyViewModel,
    onBack: () -> Unit,
) {
    val loaded by viewModel.proxy.collectAsStateWithLifecycle()

    // Room 首帧异步，没读到前不建输入框（同 BalanceThresholdsScreen）。
    val initial = loaded ?: return

    val input = rememberAppTextFieldState(initial)
    var showError by remember { mutableStateOf(false) }
    val tokens = LocalAppTokens.current

    SettingsSubPage(titleRes = Res.string.probe_proxy, onBack = onBack) {
        item {
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
            ) {
                AppText(
                    text = stringResource(Res.string.probe_proxy_hint),
                    style = AppTextStyle.Secondary,
                    color = appSecondaryTextColor,
                )
            }
        }

        item {
            AppTextField(
                state = input,
                label = stringResource(Res.string.probe_proxy),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
                errorText = if (showError) stringResource(Res.string.probe_proxy_invalid) else null,
            )
        }

        item {
            AppTextButton(
                text = stringResource(Res.string.probe_thresholds_save),
                onClick = {
                    when (viewModel.save(input.text)) {
                        ProxyViewModel.SaveResult.Invalid -> showError = true
                        ProxyViewModel.SaveResult.Saved -> onBack()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
            )
        }
    }
}

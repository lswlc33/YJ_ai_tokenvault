package com.lc33.tokenvault.screens.settings

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.appearance_blur
import tokenvault.shared.generated.resources.appearance_blur_summary
import tokenvault.shared.generated.resources.appearance_color_scheme
import tokenvault.shared.generated.resources.appearance_language
import tokenvault.shared.generated.resources.appearance_language_summary
import tokenvault.shared.generated.resources.appearance_section_language
import tokenvault.shared.generated.resources.appearance_section_theme
import tokenvault.shared.generated.resources.appearance_title
import tokenvault.shared.generated.resources.predictive_back_exit_direction
import tokenvault.shared.generated.resources.predictive_back_exit_directions
import tokenvault.shared.generated.resources.predictive_back_style
import tokenvault.shared.generated.resources.predictive_back_styles
import tokenvault.shared.generated.resources.color_scheme_modes
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.domain.model.PredictiveBackExitDirection
import com.lc33.tokenvault.domain.model.PredictiveBackStyle
import com.lc33.tokenvault.ui.theme.AppColorSchemeMode

/**
 * 外观（计划.md §13.4）。
 *
 * 语言那一行故意是 `AppArrowRow` 而不是应用内的下拉：Android 13+ 有系统级的
 * 「应用语言」页，自己再做一份就有两个权威（红线 31 的精神），所以这里只负责
 * 把用户送去系统设置。
 *
 * 这一页两项都有各自的权威存储：配色是 `boot.themeMode`、底栏模糊是
 * `app_settings.blurNavBar`（红线 31），都由 `AppearanceViewModel` 读写。配色下拉是
 * 按下标选的，所以枚举声明顺序必须与 `Res.array.color_scheme_modes` 一致——
 * `ArchitectureRulesTest` 会比这两个数量。
 */
@Composable
fun AppearanceScreen(
    colorScheme: AppColorSchemeMode,
    blurNavBar: Boolean,
    predictiveBackStyle: PredictiveBackStyle,
    predictiveBackExitDirection: PredictiveBackExitDirection,
    onColorSchemeChange: (AppColorSchemeMode) -> Unit,
    onBlurNavBarChange: (Boolean) -> Unit,
    onPredictiveBackStyleChange: (PredictiveBackStyle) -> Unit,
    onPredictiveBackExitDirectionChange: (PredictiveBackExitDirection) -> Unit,
    onBack: () -> Unit,
    onOpenSystemLocaleSettings: () -> Unit,
) {
    SettingsSubPage(titleRes = Res.string.appearance_title, onBack = onBack) {
        item { SectionTitle(text = stringResource(Res.string.appearance_section_theme)) }
        item {
            AppPreferenceGroup {
                AppDropdownRow(
                    title = stringResource(Res.string.appearance_color_scheme),
                    items = stringArrayResource(Res.array.color_scheme_modes).toList(),
                    selectedIndex = colorScheme.ordinal,
                    onSelect = { onColorSchemeChange(AppColorSchemeMode.entries[it]) },
                )
                AppSwitchRow(
                    title = stringResource(Res.string.appearance_blur),
                    summary = stringResource(Res.string.appearance_blur_summary),
                    checked = blurNavBar,
                    onCheckedChange = onBlurNavBarChange,
                )
                AppDropdownRow(
                    title = stringResource(Res.string.predictive_back_style),
                    items = stringArrayResource(Res.array.predictive_back_styles).toList(),
                    selectedIndex = predictiveBackStyle.ordinal,
                    onSelect = { onPredictiveBackStyleChange(PredictiveBackStyle.entries[it]) },
                )
                if (predictiveBackStyle == PredictiveBackStyle.Scale) {
                    AppDropdownRow(
                        title = stringResource(Res.string.predictive_back_exit_direction),
                        items = stringArrayResource(Res.array.predictive_back_exit_directions).toList(),
                        selectedIndex = predictiveBackExitDirection.ordinal,
                        onSelect = {
                            onPredictiveBackExitDirectionChange(PredictiveBackExitDirection.entries[it])
                        },
                    )
                }
            }
        }

        item { SectionTitle(text = stringResource(Res.string.appearance_section_language)) }
        item {
            AppPreferenceGroup {
                AppArrowRow(
                    title = stringResource(Res.string.appearance_language),
                    summary = stringResource(Res.string.appearance_language_summary),
                    onClick = onOpenSystemLocaleSettings,
                )
            }
        }
    }
}

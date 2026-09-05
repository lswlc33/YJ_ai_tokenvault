package com.lc33.tokenvault.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.SettingsDraft
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.theme.AppColorSchemeMode

/**
 * 外观（计划.md §13.4）。
 *
 * 语言那一行故意是 `AppArrowRow` 而不是应用内的下拉：Android 13+ 有系统级的
 * 「应用语言」页，自己再做一份就有两个权威（红线 31 的精神），所以这里只负责
 * 把用户送去系统设置。
 *
 * 配色那一行**不吃 [SettingsDraft]**：它的权威存储是 `boot.themeMode`（红线 31），
 * 由 `AppearanceViewModel` 读写。下拉是按下标选的，所以枚举声明顺序必须与
 * `R.array.color_scheme_modes` 一致——`ArchitectureRulesTest` 会比这两个数量。
 */
@Composable
fun AppearanceScreen(
    draft: SettingsDraft,
    colorScheme: AppColorSchemeMode,
    onChange: (SettingsDraft) -> Unit,
    onColorSchemeChange: (AppColorSchemeMode) -> Unit,
    onBack: () -> Unit,
    onOpenSystemLocaleSettings: () -> Unit,
) {
    SettingsSubPage(titleRes = R.string.appearance_title, onBack = onBack) {
        item { SectionTitle(text = stringResource(R.string.appearance_section_theme)) }
        item {
            AppDropdownRow(
                title = stringResource(R.string.appearance_color_scheme),
                items = stringArrayResource(R.array.color_scheme_modes).toList(),
                selectedIndex = colorScheme.ordinal,
                onSelect = { onColorSchemeChange(AppColorSchemeMode.entries[it]) },
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.appearance_squircle),
                summary = stringResource(R.string.appearance_squircle_summary),
                checked = draft.squircle,
                onCheckedChange = { onChange(draft.copy(squircle = it)) },
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.appearance_blur),
                summary = stringResource(R.string.appearance_blur_summary),
                checked = draft.blurNavBar,
                onCheckedChange = { onChange(draft.copy(blurNavBar = it)) },
            )
        }

        item { SectionTitle(text = stringResource(R.string.appearance_section_language)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.appearance_language),
                summary = stringResource(R.string.appearance_language_summary),
                onClick = onOpenSystemLocaleSettings,
            )
        }
    }
}

package com.lc33.tokenvault.screens.settings

import com.lc33.tokenvault.platform.APP_VERSION_NAME
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.settings_about_row
import tokenvault.shared.generated.resources.settings_about_row_summary
import tokenvault.shared.generated.resources.settings_appearance
import tokenvault.shared.generated.resources.settings_appearance_summary
import tokenvault.shared.generated.resources.settings_data
import tokenvault.shared.generated.resources.settings_data_summary
import tokenvault.shared.generated.resources.settings_group_about
import tokenvault.shared.generated.resources.settings_group_settings
import tokenvault.shared.generated.resources.settings_group_sync
import tokenvault.shared.generated.resources.settings_group_update
import tokenvault.shared.generated.resources.settings_probe
import tokenvault.shared.generated.resources.settings_probe_summary
import tokenvault.shared.generated.resources.settings_profiles
import tokenvault.shared.generated.resources.settings_profiles_summary
import tokenvault.shared.generated.resources.settings_security
import tokenvault.shared.generated.resources.settings_security_summary
import tokenvault.shared.generated.resources.settings_sync_row
import tokenvault.shared.generated.resources.settings_sync_row_summary
import tokenvault.shared.generated.resources.settings_title
import tokenvault.shared.generated.resources.settings_update_row
import tokenvault.shared.generated.resources.settings_update_row_summary
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState

/**
 * 设置 —— 软件自身的配置（计划.md §13.4）。
 *
 * 这一页是**导航面板**而不是巨型表单：原来七组挤在一页要滚四五屏，而其中大半
 * 是一年碰一次的东西。四块分别是设置 / 同步 / 关于 / 更新，具体项都在二级页。
 */
@Composable
fun SettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenProbeSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenData: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.settings_title),
                scrollState = scrollState,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
        ) {
            item { SectionTitle(text = stringResource(Res.string.settings_group_settings)) }
            item {
                AppPreferenceGroup {
                    AppArrowRow(
                        title = stringResource(Res.string.settings_appearance),
                        summary = stringResource(Res.string.settings_appearance_summary),
                        onClick = onOpenAppearance,
                    )
                    AppArrowRow(
                        title = stringResource(Res.string.settings_security),
                        summary = stringResource(Res.string.settings_security_summary),
                        onClick = onOpenSecurity,
                    )
                    AppArrowRow(
                        title = stringResource(Res.string.settings_probe),
                        summary = stringResource(Res.string.settings_probe_summary),
                        onClick = onOpenProbeSettings,
                    )
                    AppArrowRow(
                        title = stringResource(Res.string.settings_profiles),
                        summary = stringResource(Res.string.settings_profiles_summary),
                        onClick = onOpenProfiles,
                    )
                    AppArrowRow(
                        title = stringResource(Res.string.settings_data),
                        summary = stringResource(Res.string.settings_data_summary),
                        onClick = onOpenData,
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.settings_group_sync)) }
            item {
                AppPreferenceGroup {
                    AppArrowRow(
                        title = stringResource(Res.string.settings_sync_row),
                        summary = stringResource(Res.string.settings_sync_row_summary),
                        onClick = onOpenSync,
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.settings_group_about)) }
            item {
                AppPreferenceGroup {
                    AppArrowRow(
                        title = stringResource(Res.string.settings_about_row),
                        summary = stringResource(Res.string.settings_about_row_summary),
                        onClick = onOpenAbout,
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.settings_group_update)) }
            item {
                AppPreferenceGroup {
                    AppArrowRow(
                        title = stringResource(Res.string.settings_update_row),
                        summary = stringResource(
                            Res.string.settings_update_row_summary,
                            APP_VERSION_NAME,
                        ),
                        onClick = onOpenUpdate,
                    )
                }
            }
        }
    }
}

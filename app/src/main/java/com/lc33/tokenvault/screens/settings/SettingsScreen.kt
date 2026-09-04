package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.BuildConfig
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppArrowRow
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
                title = stringResource(R.string.settings_title),
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
            item { SectionTitle(text = stringResource(R.string.settings_group_settings)) }
            item {
                AppArrowRow(
                    title = stringResource(R.string.settings_appearance),
                    summary = stringResource(R.string.settings_appearance_summary),
                    onClick = onOpenAppearance,
                )
            }
            item {
                AppArrowRow(
                    title = stringResource(R.string.settings_security),
                    summary = stringResource(R.string.settings_security_summary),
                    onClick = onOpenSecurity,
                )
            }
            item {
                AppArrowRow(
                    title = stringResource(R.string.settings_probe),
                    summary = stringResource(R.string.settings_probe_summary),
                    onClick = onOpenProbeSettings,
                )
            }
            item {
                AppArrowRow(
                    title = stringResource(R.string.settings_profiles),
                    summary = stringResource(R.string.settings_profiles_summary),
                    onClick = onOpenProfiles,
                )
            }
            item {
                AppArrowRow(
                    title = stringResource(R.string.settings_data),
                    summary = stringResource(R.string.settings_data_summary),
                    onClick = onOpenData,
                )
            }

            item { SectionTitle(text = stringResource(R.string.settings_group_sync)) }
            item {
                AppArrowRow(
                    title = stringResource(R.string.settings_sync_row),
                    summary = stringResource(R.string.settings_sync_row_summary),
                    onClick = onOpenSync,
                )
            }

            item { SectionTitle(text = stringResource(R.string.settings_group_about)) }
            item {
                AppArrowRow(
                    title = stringResource(R.string.settings_about_row),
                    summary = stringResource(R.string.settings_about_row_summary),
                    onClick = onOpenAbout,
                )
            }

            item { SectionTitle(text = stringResource(R.string.settings_group_update)) }
            item {
                AppArrowRow(
                    title = stringResource(R.string.settings_update_row),
                    summary = stringResource(
                        R.string.settings_update_row_summary,
                        BuildConfig.VERSION_NAME,
                    ),
                    onClick = onOpenUpdate,
                )
            }
        }
    }
}

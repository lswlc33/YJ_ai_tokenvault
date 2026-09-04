package com.lc33.tokenvault.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.SectionTitle

/**
 * 数据（计划.md §13.4）。
 *
 * 三个"清空"都走二次确认，而且确认文案里要写清连带删掉什么——这一页是全应用
 * 最容易误触出不可挽回后果的地方。
 */
@Composable
fun DataScreen(
    onBack: () -> Unit,
    onSyncCatalog: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenLog: () -> Unit,
    onClearProbeResults: () -> Unit,
    onClearLog: () -> Unit,
) {
    SettingsSubPage(titleRes = R.string.data_title, onBack = onBack) {
        item { SectionTitle(text = stringResource(R.string.data_section_catalog)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.data_catalog),
                summary = stringResource(R.string.data_catalog_summary),
                onClick = onSyncCatalog,
            )
        }

        item { SectionTitle(text = stringResource(R.string.data_section_organize)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.groups_title),
                summary = stringResource(R.string.data_groups_summary),
                onClick = onOpenGroups,
            )
        }

        item { SectionTitle(text = stringResource(R.string.data_section_log)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.data_log),
                summary = stringResource(R.string.data_log_summary),
                onClick = onOpenLog,
            )
        }

        item { SectionTitle(text = stringResource(R.string.data_section_danger)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.data_clear_probe),
                summary = stringResource(R.string.data_clear_probe_summary),
                onClick = onClearProbeResults,
            )
        }
        item {
            AppArrowRow(
                title = stringResource(R.string.data_clear_log),
                summary = stringResource(R.string.data_clear_log_summary),
                onClick = onClearLog,
            )
        }
    }
}

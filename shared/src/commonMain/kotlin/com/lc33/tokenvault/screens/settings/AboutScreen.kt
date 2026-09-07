package com.lc33.tokenvault.screens.settings

import com.lc33.tokenvault.platform.APP_VERSION_CODE
import com.lc33.tokenvault.platform.APP_VERSION_NAME
import com.lc33.tokenvault.platform.PlatformBackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.about_encryption_dialog_summary
import tokenvault.shared.generated.resources.about_encryption_row
import tokenvault.shared.generated.resources.about_encryption_row_summary
import tokenvault.shared.generated.resources.about_not_password_manager_row
import tokenvault.shared.generated.resources.about_not_password_manager_summary
import tokenvault.shared.generated.resources.about_title
import tokenvault.shared.generated.resources.about_version
import tokenvault.shared.generated.resources.app_name
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.dialog_ok
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 关于。
 *
 * 也是 M0 验收分层 Scaffold 的地方：这是一个二级页（自带 topBar 与返回），
 * 页面里能弹出 `OverlayDialog`，证明"每页一个 Scaffold"下 MIUIX 的弹层真的工作
 * ——原计划以为全局只能有一个 Scaffold，那个推论是错的（计划.md §0、§13.1）。
 *
 * 版本号来自 `BuildConfig`，不在代码里再维护一份（计划.md §14.1）。
 * 加密边界的措辞是刻意说准的：只有密钥/账号密码/令牌/WebDAV 凭据是加密的，
 * 元数据在应用私有目录里是明文（计划.md §4.4、红线 23）。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    var showEncryptionDialog by remember { mutableStateOf(false) }

    PlatformBackHandler(enabled = showEncryptionDialog) { showEncryptionDialog = false }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.about_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = onBack,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
        ) {
            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
                ) {
                    AppText(text = stringResource(Res.string.app_name), style = AppTextStyle.Title)
                    AppText(
                        text = stringResource(
                            Res.string.about_version,
                            APP_VERSION_NAME,
                            APP_VERSION_CODE,
                        ),
                        style = AppTextStyle.Secondary,
                    )
                }
            }
            item {
                AppPreferenceGroup {
                    AppArrowRow(
                        title = stringResource(Res.string.about_encryption_row),
                        summary = stringResource(Res.string.about_encryption_row_summary),
                        onClick = { showEncryptionDialog = true },
                    )
                    AppArrowRow(
                        title = stringResource(Res.string.about_not_password_manager_row),
                        summary = stringResource(Res.string.about_not_password_manager_summary),
                    )
                }
            }
        }
    }

    AppDialog(
        show = showEncryptionDialog,
        onDismissRequest = { showEncryptionDialog = false },
        title = stringResource(Res.string.about_encryption_row),
        summary = stringResource(Res.string.about_encryption_dialog_summary),
        confirmText = stringResource(Res.string.dialog_ok),
    )
}

package com.lc33.tokenvault.ui.common

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import com.lc33.tokenvault.domain.LoginMethod
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.login_method_github
import tokenvault.shared.generated.resources.login_method_linuxdo

/**
 * 登录方式的展示名。
 *
 * 以前 `ManageRows` 与 `ProviderDetailScreen` 各写了一份 `when`：同一种登录方式在
 * 列表行与详情弹层里两处各有文案表，改一档很容易漏掉另一处（红线 17：同一状态全应用
 * 一套文案）。这里只留一份。
 */
@Composable
fun loginMethodLabel(method: LoginMethod): String = when (method) {
    LoginMethod.GITHUB -> stringResource(Res.string.login_method_github)
    LoginMethod.LINUX_DO -> stringResource(Res.string.login_method_linuxdo)
}

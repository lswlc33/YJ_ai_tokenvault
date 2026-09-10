package com.lc33.tokenvault.domain.model

/**
 * WebDAV 的非秘密配置。
 *
 * 用户名与密码不在这里：它们是凭据，走 `app_settings.valueBlob` 字段级加密。
 * UI 只需要知道“凭据是否已经配齐”，编辑时重新输入，避免把已存凭据解出来给界面展示。
 */
data class WebDavConfig(
    val url: String = "",
    val remoteDirectory: String = DEFAULT_REMOTE_DIRECTORY,
    val allowInsecure: Boolean = false,
    val hasCredentials: Boolean = false,
) {
    val isReady: Boolean get() = url.isNotBlank() && hasCredentials

    companion object {
        const val DEFAULT_REMOTE_DIRECTORY = "/YuanJi"
    }
}

/** 运行时才解出的 WebDAV 凭据。调用方用完必须擦 [username] 与 [password]。 */
class WebDavCredentials(
    val username: CharArray,
    val password: CharArray,
) {
    fun zeroize() {
        username.fill('\u0000')
        password.fill('\u0000')
    }
}
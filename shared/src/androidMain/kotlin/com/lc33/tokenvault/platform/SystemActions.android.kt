package com.lc33.tokenvault.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** 需要 Context 才能跳系统设置，所以 actual 要能拿到 context。 */

internal lateinit var appContext: Context

actual fun openAppLocaleSettings() {
    val appUri = Uri.fromParts("package", appContext.packageName, null)
    val locale = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, appUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // Application Context 启动 Activity 必须带 NEW_TASK；两层跳转都包住，
    // 避免 ROM 禁掉某个设置页时把「应用语言」入口变成闪退。
    val opened = runCatching { appContext.startActivity(locale) }.isSuccess
    if (!opened) runCatching { appContext.startActivity(details) }
}

actual fun openExternalUrl(url: String) {
    runCatching {
        appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

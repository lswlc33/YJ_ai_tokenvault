package com.lc33.tokenvault.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** 需要 Context 才能跳系统设置，所以 actual 要能拿到 context。 */

internal lateinit var appContext: Context

actual fun openAppLocaleSettings() {
    val locale = Intent(
        Settings.ACTION_APP_LOCALE_SETTINGS,
        Uri.fromParts("package", appContext.packageName, null),
    )
    val details = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", appContext.packageName, null),
    )
    runCatching { appContext.startActivity(locale) }
        .onFailure { appContext.startActivity(details) }
}

actual fun openExternalUrl(url: String) {
    runCatching {
        appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

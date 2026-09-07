package com.lc33.tokenvault.platform

/**
 * 打开系统的「应用语言」页（红线 20：跳系统设置是平台能力）。
 *
 * Android 13+ 有系统级 per-app locale，应用内不重复做语言选择器（红线 31）。
 * iOS 没有 per-app locale 设置页，空实现（语言跟随系统，或后续接 Settings.app 深链）。
 */
expect fun openAppLocaleSettings()

/**
 * 用系统默认浏览器打开一个外部链接（更新下载页等）。
 */
expect fun openExternalUrl(url: String)

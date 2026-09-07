package com.lc33.tokenvault.platform

import platform.UIKit.UIApplication

/** iOS：无 per-app locale 设置页，空实现（语言跟随系统）。 */
actual fun openAppLocaleSettings() = Unit

/** iOS：用 UIApplication.openURL 打开外部链接。 */
actual fun openExternalUrl(url: String) {
    val nsUrl = platform.Foundation.NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
}

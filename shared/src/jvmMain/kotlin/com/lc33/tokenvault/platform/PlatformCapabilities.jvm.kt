package com.lc33.tokenvault.platform

// JVM target 只为在本机跑单测而存在，界面与 Android 一致，所以能力位与 Android 同值：
// 设置页与 AutoLocker 的测试路径因此覆盖得到，而"这个平台到底做不做得到"由 Android 的 actual 说了算。
actual val supportsIdleLock: Boolean = true
actual val supportsLockOnScreenOff: Boolean = true
actual val supportsInAppLanguageSwitch: Boolean = true

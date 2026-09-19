package com.lc33.tokenvault.platform

// Android 三项都做得到：空闲计时有 Activity.onUserInteraction、屏关有 ACTION_SCREEN_OFF 广播、
// 应用语言有 Android 13+ 的系统级 per-app 设置页（最低支持版本就是 13）。
actual val supportsIdleLock: Boolean = true
actual val supportsLockOnScreenOff: Boolean = true
actual val supportsInAppLanguageSwitch: Boolean = true

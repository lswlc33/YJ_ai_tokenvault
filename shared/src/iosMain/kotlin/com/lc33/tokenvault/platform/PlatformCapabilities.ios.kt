package com.lc33.tokenvault.platform

// 三条都是"iOS 上做不到 / 没实现"，所以对应的设置行不进界面（理由见 commonMain 的 expect 声明）。
// 刻意不为 iOS 造一个"看起来能用其实不管事"的替代实现：那是假承诺，而这几项是安全设置。
actual val supportsIdleLock: Boolean = false
actual val supportsLockOnScreenOff: Boolean = false
actual val supportsInAppLanguageSwitch: Boolean = false

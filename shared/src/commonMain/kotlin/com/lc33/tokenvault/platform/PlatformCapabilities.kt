package com.lc33.tokenvault.platform

/**
 * 平台能力位（红线 19 的"文案与能力对齐"那一面）。
 *
 * 为什么需要它而不是"在界面上都画出来、点了没反应就算了"：这几项都是**安全设置**。
 * 画一个在 iOS 上根本不生效的"前台空闲时锁定"开关，用户会以为手机在兜里 30 秒就锁了，
 * 而实际上 DEK 一直在内存里——这比没有这一项更糟（§7.6 不假装能防）。
 *
 * 所以规则是：**做不到的能力不进界面**，由这些编译期常量决定，而不是运行时猜设备型号。
 * 值随 target 定，不做反射探测——探测出来的"看起来支持"仍然不等于"我们实现了"。
 */

/**
 * 前台空闲锁定（§7.4：30 秒不摸屏幕就锁）。
 *
 * Android 靠 `Activity.onUserInteraction` 重置计时，是公开且可靠的生命周期钩子。
 * iOS **没有**等价钩子（要 swizzle `UIApplication.sendEvent`，那是被审核与系统版本
 * 双向绑定的做法），而且一旦没有重置方，这个计时器会变成"进应用 30 秒必锁"——
 * 比不实现更糟，所以 iOS 上这一项直接不进设置页。
 */
expect val supportsIdleLock: Boolean

/**
 * 屏幕关闭即锁定（§7.4）。Android 收 `ACTION_SCREEN_OFF` 广播即可。
 * iOS 没有对应的公开通知，`lockOnScreenOff` 在 iOS 上没有任何调用方。
 */
expect val supportsLockOnScreenOff: Boolean

/**
 * 应用内 / 系统级"应用语言"入口。
 *
 * Android 13+ 有 `Settings.ACTION_APP_LOCALE_SETTINGS`，设置页那一行是跳过去。
 * iOS 的 per-app 语言由 `CFBundleLocalizations` + 系统设置提供，但**切换之后要重启应用
 * 才生效**，而本应用当前在 iOS 上还没有把 Compose 侧资源跟着 bundle 语言走通，
 * 所以那一行入口在 iOS 上先不进界面（`Info.plist` 已声明本地化，留着后面的口子）。
 */
expect val supportsInAppLanguageSwitch: Boolean

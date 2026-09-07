package com.lc33.tokenvault

/**
 * iOS 桥接入口（阶段4 占位）。
 *
 * 目的：让 iosApp 的 Swift 代码能 `import Shared` 并调用真实代码，
 * 从而在 CI 里真实验证 shared.framework 的编译 + 链接链路。
 * 否则 Swift 端不引用 framework 的话，framework 链接失败也不会让构建报错。
 *
 * 等阶段4 真正迁移 UI 后，这个占位函数会被真实的跨平台入口取代。
 */
object IosBridge {
    /** 返回共享模块的版本标识，供 Swift 端校验 framework 已成功链接。 */
    fun sharedGreeting(): String = "Shared framework linked (TokenVault)"
}

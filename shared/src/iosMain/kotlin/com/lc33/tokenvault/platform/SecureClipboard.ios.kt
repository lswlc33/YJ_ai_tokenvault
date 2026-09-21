package com.lc33.tokenvault.platform

import platform.Foundation.NSNumber
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionLocalOnly

/**
 * 系统剪贴板实现（iOS 端，阶段4）。接口语义与 Android 端的 AndroidSecureClipboard 逐条对齐。
 *
 * 只做两件事：
 *
 * 1. 写：`localOnly`——不把剪贴板同步到 Handoff / 通用剪贴板（对应 Android 端的
 *    `EXTRA_IS_SENSITIVE`；HTTP 跨设备同步不该带上密钥）。
 * 2. 读：粘贴导入用的「从剪贴板填充」。
 *
 * 不再自动清除（决策见 none.md §7.5），与 Android 端实现保持一致。
 */
class IosSecureClipboard : SecureClipboard {

    override fun copy(label: String, value: CharArray) {
        val text = value.concatToString()
        UIPasteboard.generalPasteboard.setItems(
            // setItems 是唯一能挂 localOnly 的入口；类型键用 UTF-8 文本的 UTI。
            listOf(mapOf("public.utf8-plain-text" to text)),
            // ObjC 类方法 numberWithBool 在 Kotlin 侧不可见（NSNumber 类型映射特殊），
            // 用构造器 NSNumber(bool = true) 造布尔值。
            mapOf<Any?, Any?>(UIPasteboardOptionLocalOnly to NSNumber(bool = true)),
        )
    }

    override fun read(): String? = UIPasteboard.generalPasteboard.string
}
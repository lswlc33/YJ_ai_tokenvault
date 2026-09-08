package com.lc33.tokenvault.platform

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UISelectionFeedbackGenerator

/**
 * iOS：用 UIKit 的触觉反馈生成器。
 *
 * - [tap] → `UISelectionFeedbackGenerator`（系统选择器反馈，最轻）。
 * - [impact] → `UIImpactFeedbackGenerator(style = Light)`（轻冲击）。
 */
actual object Haptics {
    actual fun tap() {
        UISelectionFeedbackGenerator().selectionChanged()
    }

    actual fun impact() {
        UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).impactOccurred()
    }
}

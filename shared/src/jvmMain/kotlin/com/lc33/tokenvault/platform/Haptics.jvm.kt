package com.lc33.tokenvault.platform

/** JVM（单测/桌面）无触觉设备，空实现。 */
actual object Haptics {
    actual fun tap() = Unit
    actual fun impact() = Unit
}

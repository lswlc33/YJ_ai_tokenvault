package com.lc33.tokenvault.platform

import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/** iOS：用 `NSProcessInfo.systemUptime`（单调时钟，秒）转纳秒。 */
actual fun monotonicNanoTime(): Long =
    (NSProcessInfo.processInfo.systemUptime * 1_000_000_000.0).toLong()

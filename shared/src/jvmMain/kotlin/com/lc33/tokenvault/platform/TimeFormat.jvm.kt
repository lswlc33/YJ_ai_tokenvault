package com.lc33.tokenvault.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** JVM 单测与 Android 一致（本地化 MEDIUM 短日期，系统默认时区）。 */
actual fun absoluteDateLabel(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

package com.lc33.tokenvault.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** 与阶段2 迁移前行为一致：本地化 MEDIUM 短日期，设备当前时区。 */
actual fun absoluteDateLabel(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

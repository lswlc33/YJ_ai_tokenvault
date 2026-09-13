package com.lc33.tokenvault.domain.model

/** 日志保留期。[FOREVER] 不按时间清理。 */
enum class LogRetention(val days: Int?) {
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    NINETY_DAYS(90),
    FOREVER(null),
    ;

    companion object {
        /**
         * 天数 → 选项。**`null` 表示"没读懂"，不是"永久"**：永久在存储里是显式的
         * `forever` 字样，由仓库先判掉再进来。所以坏数据只能落回默认 7 天，
         * 不能让一段读不懂的值把日志变成永不清理。
         */
        fun fromDays(value: Int?): LogRetention {
            if (value == null) return SEVEN_DAYS
            return entries.firstOrNull { it.days == value } ?: SEVEN_DAYS
        }
    }
}

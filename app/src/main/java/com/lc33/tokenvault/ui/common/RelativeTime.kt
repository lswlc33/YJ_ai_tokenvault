package com.lc33.tokenvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 相对时间的分档。
 *
 * 分档是**纯函数**、不读当前时间——`now` 由调用方传进来（红线 20：当前时间也算平台
 * 能力）。这样它能在 JVM 单测里覆盖，而不是变成一个时间敏感的测试。
 *
 * 只做到"天"，更久就给绝对日期：三个月前的探测结果说成"92 天前"没有意义。
 */
sealed interface RelativeBucket {
    data object JustNow : RelativeBucket
    data class Minutes(val value: Int) : RelativeBucket
    data class Hours(val value: Int) : RelativeBucket
    data class Days(val value: Int) : RelativeBucket

    /** 超过 [DAY_LIMIT] 天：交给调用方用绝对日期渲染。 */
    data object Absolute : RelativeBucket

    /** 未来时间。设备时钟被改过，或者数据来自另一台时钟更快的机器。 */
    data object Future : RelativeBucket

    companion object {
        const val DAY_LIMIT = 30
    }
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/**
 * 把两个时间戳的差分档。
 *
 * "未来"单独一档而不是钳到 `JustNow`：恢复一个来自另一台设备的备份、或者用户改过
 * 系统时钟，都会让 `checkedAt` 落在未来。显示"0 分钟前"会让人以为刚探测过，
 * 而真相是这个时间不可信。
 */
fun relativeBucketOf(nowMillis: Long, thenMillis: Long): RelativeBucket {
    val diff = nowMillis - thenMillis
    return when {
        diff < -MINUTE -> RelativeBucket.Future
        diff < MINUTE -> RelativeBucket.JustNow
        diff < HOUR -> RelativeBucket.Minutes((diff / MINUTE).toInt())
        diff < DAY -> RelativeBucket.Hours((diff / HOUR).toInt())
        diff < RelativeBucket.DAY_LIMIT * DAY -> RelativeBucket.Days((diff / DAY).toInt())
        else -> RelativeBucket.Absolute
    }
}

/**
 * 分档 → 文案。文案来自资源，所以这一半必须是 `@Composable`；分档那一半是纯的。
 *
 * [absoluteLabel] 由调用方给（它才知道该用什么日期格式与时区）。
 */
@Composable
@ReadOnlyComposable
fun relativeTimeLabel(bucket: RelativeBucket, absoluteLabel: String = ""): String = when (bucket) {
    RelativeBucket.JustNow -> stringResource(R.string.time_just_now)
    is RelativeBucket.Minutes -> stringResource(R.string.time_minutes_ago, bucket.value)
    is RelativeBucket.Hours -> stringResource(R.string.time_hours_ago, bucket.value)
    is RelativeBucket.Days -> stringResource(R.string.time_days_ago, bucket.value)
    RelativeBucket.Absolute -> absoluteLabel
    RelativeBucket.Future -> stringResource(R.string.time_future)
}

/**
 * 两个时间戳 → 一句现成的话。**每一个要显示相对时间的地方都该调这一个。**
 *
 * 它存在的理由是 `Absolute` 那一档：[relativeTimeLabel] 的 `absoluteLabel` 默认是空串，
 * 于是“超过 30 天”的时间会静静地渲染成**什么都没有**——那一行看起来就像从未探测过。
 * 日期格式交给 `ofLocalizedDate`：“用什么格式写日期”是语言与地区的事，不该写成字面量。
 */
@Composable
fun relativeLabel(nowMillis: Long, thenMillis: Long): String {
    val bucket = relativeBucketOf(nowMillis, thenMillis)
    val absolute = if (bucket == RelativeBucket.Absolute) absoluteDateLabel(thenMillis) else ""
    return relativeTimeLabel(bucket, absolute)
}

/** 本地化的短日期。时区用设备当前的：这一串是给人看的，不参与任何计算。 */
private fun absoluteDateLabel(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/**
 * 一段耗时（毫秒）→ 向上取整到秒的整数。
 *
 * 探测一轮的"耗时"就是这么算的：`finishedAt - startedAt`。向下取整会让一场
 * 0.4 秒的探测显示成"0 秒"，而向上取整至少给"1 秒"，这更诚实——那场探测确实花了时间。
 * 文案（`time_duration_seconds`）由调用方用 `stringResource` 取，这里只给秒数（红线 19）。
 */
fun durationSeconds(durationMs: Long): Long = (durationMs + 999) / 1000

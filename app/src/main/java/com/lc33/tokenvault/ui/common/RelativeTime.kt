package com.lc33.tokenvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R

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

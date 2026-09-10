package com.lc33.tokenvault.domain.model

/**
 * 预见式返回样式。
 *
 * 存储值必须保持稳定：用户设置落在 app_settings 后，枚举顺序变化不能改变它的含义。
 */
enum class PredictiveBackStyle(val storageValue: String) {
    None("none"),
    Aosp("aosp"),
    Miuix("miuix"),
    Scale("scale"),
    Classic("classic"),
    ;

    companion object {
        fun fromStorage(value: String?): PredictiveBackStyle =
            entries.firstOrNull { it.storageValue == value } ?: Miuix
    }
}

/**
 * Scale 样式的页面退出方向。其他样式要么由系统手势决定，要么没有横向退出语义。
 */
enum class PredictiveBackExitDirection(val storageValue: String) {
    FollowGesture("follow_gesture"),
    AlwaysRight("always_right"),
    AlwaysLeft("always_left"),
    ;

    companion object {
        fun fromStorage(value: String?): PredictiveBackExitDirection =
            entries.firstOrNull { it.storageValue == value } ?: AlwaysRight
    }
}

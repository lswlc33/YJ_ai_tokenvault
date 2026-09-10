package com.lc33.tokenvault.domain.model

/**
 * 预见式返回样式。
 *
 * 存储值必须保持稳定：用户设置落在 app_settings 后，枚举顺序变化不能改变它的含义。
 */
enum class PredictiveBackStyle(val storageValue: String) {
    None("none"),
    Miuix("miuix"),
    Scale("scale"),
    ;

    companion object {
        private const val LegacyAospStorageValue = "aosp"
        private const val LegacyClassicStorageValue = "classic"

        fun fromStorage(value: String?): PredictiveBackStyle = when (value) {
            None.storageValue -> None
            Miuix.storageValue -> Miuix
            Scale.storageValue -> Scale
            // 旧版 AOSP / 经典都是缩放系返回；样式收窄后映射到最接近的缩放动画。
            LegacyAospStorageValue, LegacyClassicStorageValue -> Scale
            else -> Miuix
        }
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

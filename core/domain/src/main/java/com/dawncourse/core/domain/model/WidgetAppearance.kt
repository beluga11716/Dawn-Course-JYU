package com.dawncourse.core.domain.model

/** 桌面小组件独立的外观设置，不受应用壁纸或课程卡片透明度影响。 */
data class WidgetAppearance(
    val backgroundOpacity: Int = 100,
    val textTheme: WidgetTextTheme = WidgetTextTheme.FOLLOW_SYSTEM,
) {
    val backgroundAlpha: Float get() = backgroundOpacity.coerceIn(0, 100) / 100f
}

enum class WidgetTextTheme {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}

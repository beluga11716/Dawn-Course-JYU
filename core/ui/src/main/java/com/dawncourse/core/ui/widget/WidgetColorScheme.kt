package com.dawncourse.core.ui.widget

import androidx.compose.ui.graphics.Color
import com.dawncourse.core.domain.model.WidgetAppearance
import com.dawncourse.core.domain.model.WidgetTextTheme

/** Compose 预览和 Glance 桌面共用配色；透明模式不留下卡片或日期底色。 */
class WidgetColorScheme(appearance: WidgetAppearance, systemDark: Boolean) {
    private val lightText = when (appearance.textTheme) {
        WidgetTextTheme.FOLLOW_SYSTEM -> systemDark
        WidgetTextTheme.LIGHT -> true
        WidgetTextTheme.DARK -> false
    }
    private val alpha = appearance.backgroundAlpha
    val background = (if (lightText) Color(0xFF1C1B1F) else Color.White).copy(alpha = alpha)
    val primary = if (lightText) Color(0xFFD0BCFF) else Color(0xFF6750A4)
    val text = if (lightText) Color(0xFFF5F2F7) else Color(0xFF1C1B1F)
    val secondaryText = if (lightText) Color(0xFFDED9E5) else Color(0xFF49454F)
    val divider = (if (lightText) Color.White else Color(0xFF49454F)).copy(alpha = .28f)
    val dateBackground = (if (lightText) Color(0xFF3C4043) else Color(0xFFF1F3F4)).copy(alpha = alpha)
    val courseBackground = (if (lightText) Color(0xFF3A2828) else Color(0xFFFFF0F0)).copy(alpha = alpha)
    val showEdgeFade = alpha == 1f
}

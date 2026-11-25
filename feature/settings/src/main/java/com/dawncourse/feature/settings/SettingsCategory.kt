package com.dawncourse.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/** 首页仅展示分类；颜色选择是外观分类内的子页面。 */
internal enum class SettingsCategory(val title: String, val description: String, val icon: ImageVector) {
    APPEARANCE("外观与视觉", "主题颜色、深浅模式与壁纸", Icons.Default.Palette),
    DATA_SYNC("数据与同步", "教务登录、日历同步与备份", Icons.Default.Sync),
    TIMETABLE("课表管理", "课表、学期与节次时间", Icons.Default.CalendarMonth),
    WIDGET("桌面小组件", "添加到桌面、背景透明度与文字颜色", Icons.Default.Widgets),
    LAYOUT("显示与布局", "周末、侧边栏与课表显示", Icons.Default.ViewQuilt),
    GRID("网格线设置", "线条样式、颜色与透明度", Icons.Default.GridOn),
    NOTIFICATIONS("通知与提醒", "上课提醒、常驻通知与自动静音", Icons.Default.Notifications),
    ABOUT("关于", "检查更新、项目与许可", Icons.Default.Info),
    THEME_COLOR("主题颜色", "", Icons.Default.Palette),
}

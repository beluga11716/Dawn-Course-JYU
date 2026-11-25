package com.dawncourse.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.ThemeColor
import com.dawncourse.core.ui.theme.seedColorScheme

internal val ThemeColor.displayName: String
    get() = when (this) {
        ThemeColor.DEFAULT_GREEN -> "默认绿"
        ThemeColor.PINK -> "粉红色"
        ThemeColor.RED -> "红色"
        ThemeColor.ORANGE -> "橙色"
        ThemeColor.AMBER -> "琥珀色"
        ThemeColor.YELLOW -> "黄色"
        ThemeColor.LIME -> "酸橙色"
        ThemeColor.LIGHT_GREEN -> "浅绿色"
        ThemeColor.GREEN -> "绿色"
        ThemeColor.TEAL -> "青色"
        ThemeColor.CYAN -> "蓝绿色"
        ThemeColor.LIGHT_BLUE -> "浅蓝色"
        ThemeColor.BLUE -> "蓝色"
        ThemeColor.INDIGO -> "靛蓝色"
        ThemeColor.PURPLE -> "紫色"
        ThemeColor.DEEP_PURPLE -> "深紫色"
        ThemeColor.BLUE_GREY -> "蓝灰色"
        ThemeColor.BROWN -> "棕色"
        ThemeColor.GREY -> "灰色"
    }

/** 多色圆形色板参考 PiliPlus；选择立即应用，手动选色会退出动态取色。 */
@Composable
internal fun ThemeColorPicker(
    settings: AppSettings,
    onSelect: (ThemeColor) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    PreferenceCategory("取色方式") {
        SwitchSetting(
            title = "动态取色",
            description = "优先使用应用壁纸，未设置时使用系统取色；不可用时使用所选主题色",
            icon = { Icon(Icons.Default.Palette, contentDescription = null) },
            checked = settings.dynamicColor,
            onCheckedChange = onDynamicColorChange,
        )
    }
    PreferenceCategory("选择主题色") {
        Text(
            if (settings.dynamicColor) "点击色板即可切换为指定颜色" else "当前：${settings.themeColor.displayName} · 点击立即应用",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThemeColor.entries.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { color ->
                    val scheme = remember(color, dark) { seedColorScheme(Color(color.argb), dark) }
                    val selected = !settings.dynamicColor && settings.themeColor == color
                    Column(
                        modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                            .selectable(selected, role = Role.RadioButton, onClick = { onSelect(color) })
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(52.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(scheme.primary, size = Size(size.width, size.height / 2))
                                drawRect(scheme.tertiary, Offset(0f, size.height / 2), Size(size.width / 2, size.height / 2))
                                drawRect(scheme.primaryContainer, Offset(size.width / 2, size.height / 2), Size(size.width / 2, size.height / 2))
                            }
                            if (selected) {
                                Icon(Icons.Default.Check, "已选择", tint = scheme.primary,
                                    modifier = Modifier.background(scheme.surfaceContainer, CircleShape).padding(4.dp).size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(color.displayName, style = MaterialTheme.typography.labelMedium)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    PreferenceCategory("效果预览") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("示例课程", style = MaterialTheme.typography.titleMedium)
                    Text("08:00–09:35 · 图书馆", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("当前主题", color = MaterialTheme.colorScheme.primary)
                Button(onClick = {}, enabled = true) { Text("按钮预览") }
            }
        }
    }
}

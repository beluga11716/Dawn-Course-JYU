package com.dawncourse.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.dawncourse.core.domain.model.WidgetAppearance
import com.dawncourse.core.domain.model.WidgetTextTheme
import com.dawncourse.core.ui.widget.WidgetColorScheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

enum class WidgetPinResult { REQUESTED, UNSUPPORTED, FAILED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(
    onBackClick: () -> Unit,
    onRequestPinWidget: () -> WidgetPinResult,
    viewModel: WidgetSettingsViewModel = hiltViewModel(),
) {
    val savedAppearance by viewModel.appearance.collectAsState()
    val error by viewModel.error.collectAsState()
    val appearance = savedAppearance ?: WidgetAppearance()
    var opacity by remember(appearance.backgroundOpacity) {
        mutableFloatStateOf(appearance.backgroundOpacity.toFloat())
    }
    var pinResult by remember { mutableStateOf<WidgetPinResult?>(null) }
    var darkWallpaper by rememberSaveable { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); viewModel.dismissError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("桌面小组件") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("效果预览", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("示例课程", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WidgetAppearancePreview(appearance.copy(backgroundOpacity = opacity.roundToInt()), darkWallpaper)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = darkWallpaper, onClick = { darkWallpaper = true }, label = { Text("深色壁纸") })
                FilterChip(selected = !darkWallpaper, onClick = { darkWallpaper = false }, label = { Text("浅色壁纸") })
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("背景不透明度", modifier = Modifier.weight(1f))
                        Text(if (opacity.roundToInt() == 0) "完全透明" else "${opacity.roundToInt()}%", color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it },
                        onValueChangeFinished = { viewModel.setBackgroundOpacity(opacity.roundToInt()) },
                        valueRange = 0f..100f,
                        enabled = savedAppearance != null,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text("透明", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("实色", style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider()
                    Text("文字颜色", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetTextTheme.entries.forEach { theme ->
                            FilterChip(
                                selected = appearance.textTheme == theme,
                                enabled = savedAppearance != null,
                                onClick = { viewModel.setTextTheme(theme) },
                                label = {
                                    Text(when (theme) {
                                        WidgetTextTheme.FOLLOW_SYSTEM -> "跟随系统"
                                        WidgetTextTheme.LIGHT -> "浅色"
                                        WidgetTextTheme.DARK -> "深色"
                                    })
                                },
                            )
                        }
                    }
                    Text("深色壁纸建议选浅色文字，浅色壁纸建议选深色文字。设置会应用到所有桌面小组件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(
                onClick = {
                    val result = onRequestPinWidget()
                    if (result == WidgetPinResult.REQUESTED) {
                        scope.launch { snackbar.showSnackbar("请在系统窗口中确认添加；若未弹窗，请从桌面小组件列表手动添加") }
                    } else {
                        pinResult = result
                    }
                },
                enabled = savedAppearance != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(Icons.Outlined.AddToHomeScreen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加到桌面")
            }
            Text("添加后会显示当前课表的今日课程；点击小组件可打开课表。也可以在桌面编辑模式的小组件列表里查找「课程表」。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    pinResult?.let { result ->
        AlertDialog(
            onDismissRequest = { pinResult = null },
            title = { Text(when (result) {
                WidgetPinResult.REQUESTED -> "请确认添加"
                WidgetPinResult.UNSUPPORTED -> "请从桌面手动添加"
                WidgetPinResult.FAILED -> "暂时无法请求添加"
            }) },
            text = { Text(when (result) {
                WidgetPinResult.REQUESTED -> "请在桌面弹出的系统窗口中确认。如果没有弹窗，可以长按桌面空白处，进入小组件列表，找到「课程表」后添加。"
                WidgetPinResult.UNSUPPORTED -> "当前桌面不支持应用内添加。请长按桌面空白处，进入小组件列表，查找「课程表」。小米手机可在桌面编辑模式中查找安卓小组件；具体位置因桌面版本而异。"
                WidgetPinResult.FAILED -> "请重试，或长按桌面空白处，在小组件列表中查找「课程表」。"
            }) },
            confirmButton = { TextButton(onClick = { pinResult = null }) { Text("知道了") } },
        )
    }
}

/** 用示例课程展示真实小组件的配色和排版，不读取或改写用户课程。 */
@Composable
private fun WidgetAppearancePreview(appearance: WidgetAppearance, darkWallpaper: Boolean) {
    val colors = WidgetColorScheme(appearance, isSystemInDarkTheme())
    val wallpaperColors = if (darkWallpaper) {
        listOf(Color(0xFF172333), Color(0xFF4A5066), Color(0xFF9B8494))
    } else {
        listOf(Color(0xFFD7E5F2), Color(0xFFE3DDEB), Color(0xFFF4DCD2))
    }
    Box(
        Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(wallpaperColors)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val hill = Path().apply {
                moveTo(0f, size.height * .82f)
                cubicTo(size.width * .25f, size.height * .28f, size.width * .60f, size.height * 1.15f, size.width, size.height * .52f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(hill, if (darkWallpaper) Color(0xFF182C3C).copy(alpha = .45f) else Color.White.copy(alpha = .3f))
        }
        Column(
            Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(colors.background).padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("第12周", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.text)
                Spacer(Modifier.width(8.dp))
                Text("周三", fontSize = 13.sp, color = colors.secondaryText, modifier = Modifier.weight(1f).padding(bottom = 2.dp))
                Text("11月25日", fontSize = 11.sp, color = colors.secondaryText, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.dateBackground).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("08:00", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text)
                    Text("09:35", fontSize = 10.sp, color = colors.secondaryText)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(colors.courseBackground).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("高等数学", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.text)
                    Spacer(Modifier.height(6.dp))
                    Text("第一教学楼 A203", fontSize = 11.sp, color = colors.secondaryText)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("10:00", fontSize = 13.sp, color = colors.secondaryText, modifier = Modifier.width(46.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(2.dp).height(12.dp).background(colors.divider))
                Spacer(Modifier.width(10.dp))
                Text("大学英语", fontSize = 13.sp, color = colors.text)
                Spacer(Modifier.width(8.dp))
                Text("@B405", fontSize = 11.sp, color = colors.secondaryText)
            }
        }
    }
}

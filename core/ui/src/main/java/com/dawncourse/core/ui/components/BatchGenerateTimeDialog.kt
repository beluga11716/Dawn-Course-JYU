package com.dawncourse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dawncourse.core.domain.model.SectionTime
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.time.LocalTime
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * 批量生成时间表对话框
 *
 * 允许用户通过设置每节课时长、课间休息时间以及早中晚的起始时间，
 * 快速生成全天的时间表数据。
 *
 * @param maxDailySections 每天最大节数
 * @param onDismissRequest 取消回调
 * @param onConfirm 确认回调，返回生成的 [SectionTime] 列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchGenerateTimeDialog(
    maxDailySections: Int,
    initialDuration: Int = 45,
    initialBreakDuration: Int = 10,
    initialAmStart: LocalTime = LocalTime.of(8, 0),
    initialPmStartSec: Int = 5,
    initialPmStart: LocalTime = LocalTime.of(14, 0),
    initialEveStartSec: Int = 9,
    initialEveStart: LocalTime = LocalTime.of(19, 0),
    initialSectionTimes: List<SectionTime> = emptyList(),
    onDismissRequest: () -> Unit,
    onConfirm: (List<SectionTime>) -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            BatchGenerateTimeContent(
                maxDailySections = maxDailySections,
                initialDuration = initialDuration,
                initialBreakDuration = initialBreakDuration,
                initialAmStart = initialAmStart,
                initialPmStartSec = initialPmStartSec,
                initialPmStart = initialPmStart,
                initialEveStartSec = initialEveStartSec,
                initialEveStart = initialEveStart,
                initialSectionTimes = initialSectionTimes,
                onDismissRequest = onDismissRequest,
                onConfirm = onConfirm
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchGenerateTimeContent(
    maxDailySections: Int,
    initialDuration: Int = 45,
    initialBreakDuration: Int = 10,
    initialAmStart: LocalTime = LocalTime.of(8, 0),
    initialPmStartSec: Int = 5,
    initialPmStart: LocalTime = LocalTime.of(14, 0),
    initialEveStartSec: Int = 9,
    initialEveStart: LocalTime = LocalTime.of(19, 0),
    initialSectionTimes: List<SectionTime> = emptyList(),
    onDismissRequest: () -> Unit,
    onConfirm: (List<SectionTime>) -> Unit,
    editor: SectionTimeEditorViewModel = hiltViewModel(),
) {
    // 快捷参数从已保存时间推导，避免上方显示默认值、下方显示另一份时间表。
    fun savedStart(index: Int, fallback: LocalTime): LocalTime =
        runCatching { LocalTime.parse(initialSectionTimes[index].startTime) }.getOrDefault(fallback)
    val savedDuration = runCatching {
        initialSectionTimes.first().let { Duration.between(LocalTime.parse(it.startTime), LocalTime.parse(it.endTime)).toMinutes().toInt() }
    }.getOrDefault(initialDuration).takeIf { it in 1..240 } ?: initialDuration
    val savedBreak = runCatching {
        Duration.between(LocalTime.parse(initialSectionTimes[0].endTime), LocalTime.parse(initialSectionTimes[1].startTime)).toMinutes().toInt()
    }.getOrDefault(initialBreakDuration).takeIf { it in 0..180 } ?: initialBreakDuration
    var sectionDuration by remember { mutableStateOf(savedDuration.toString()) }
    var breakDuration by remember { mutableStateOf(savedBreak.toString()) }
    
    var morningStartTime by remember { mutableStateOf(savedStart(0, initialAmStart)) }
    
    var afternoonStartSection by remember { mutableStateOf(initialPmStartSec.toString()) }
    var afternoonStartTime by remember { mutableStateOf(savedStart(initialPmStartSec - 1, initialPmStart)) }
    
    var eveningStartSection by remember { mutableStateOf(initialEveStartSec.toString()) }
    var eveningStartTime by remember { mutableStateOf(savedStart(initialEveStartSec - 1, initialEveStart)) }
    
    var showTimePickerFor by remember { mutableStateOf<String?>(null) } // "morning", "afternoon", "evening"
    var editedTimes by remember(maxDailySections, initialSectionTimes) {
        mutableStateOf(initialSectionTimes.takeIf { it.isNotEmpty() })
    }
    var editingSection by remember { mutableStateOf<Int?>(null) }
    val preview = remember(editedTimes, maxDailySections, sectionDuration, breakDuration, morningStartTime,
        afternoonStartSection, afternoonStartTime, eveningStartSection, eveningStartTime) {
        editor.preview(editedTimes, maxDailySections, sectionDuration, breakDuration, morningStartTime,
            afternoonStartSection, afternoonStartTime, eveningStartSection, eveningStartTime)
    }

    Column(
        modifier = Modifier.heightIn(max = 650.dp).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "快捷节次设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "上方快捷生成，下方逐节调整；保存后生效",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. 基础设置 (Card Style)
                SettingsGroupCard(title = "基础设置", icon = Icons.Outlined.Timer) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompactNumericInput(
                            value = sectionDuration,
                            onValueChange = { sectionDuration = it; editedTimes = null },
                            label = "每节时长",
                            suffix = "分",
                            modifier = Modifier.weight(1f)
                        )
                        CompactNumericInput(
                            value = breakDuration,
                            onValueChange = { breakDuration = it; editedTimes = null },
                            label = "课间休息",
                            suffix = "分",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 2. 时间节点 (Card Style)
                SettingsGroupCard(title = "时间节点", icon = Icons.Filled.AccessTime) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Morning
                        TimeNodeRow(
                            icon = Icons.Filled.WbSunny,
                            label = "上午 (第1节)",
                            time = morningStartTime,
                            onClick = { showTimePickerFor = "morning" }
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Afternoon
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactNumericInput(
                                value = afternoonStartSection,
                                onValueChange = { afternoonStartSection = it; editedTimes = null },
                                label = "下午起始",
                                prefix = "第",
                                suffix = "节",
                                modifier = Modifier.width(80.dp)
                            )
                            TimeNodeRow(
                                icon = Icons.Filled.WbTwilight,
                                label = "开始",
                                time = afternoonStartTime,
                                onClick = { showTimePickerFor = "afternoon" },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Evening
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CompactNumericInput(
                                value = eveningStartSection,
                                onValueChange = { eveningStartSection = it; editedTimes = null },
                                label = "晚上起始",
                                prefix = "第",
                                suffix = "节",
                                modifier = Modifier.width(80.dp)
                            )
                            TimeNodeRow(
                                icon = Icons.Filled.Bedtime,
                                label = "开始",
                                time = eveningStartTime,
                                onClick = { showTimePickerFor = "evening" },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            SettingsGroupCard(title = "节次时间设置", icon = Icons.Filled.AccessTime) {
                Text("点击某一节修改，后续所有节次自动平移。修改上方快捷参数会重新生成整张时间表。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                preview.getOrNull()?.forEachIndexed { index, time ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { editingSection = index }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("第 ${index + 1} 节", style = MaterialTheme.typography.bodyLarge)
                        Text("${time.startTime} - ${time.endTime}", color = MaterialTheme.colorScheme.primary)
                    }
                    if (index < maxDailySections - 1) HorizontalDivider()
                }
                preview.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = preview.isSuccess,
                onClick = {
                    preview.getOrNull()?.let(onConfirm)
                }
            ) {
                Text("保存时间表")
            }
        }
    }
    
    // Time Picker Dialog
    if (showTimePickerFor != null) {
        val initialTime = when(showTimePickerFor) {
            "morning" -> morningStartTime
            "afternoon" -> afternoonStartTime
            "evening" -> eveningStartTime
            else -> LocalTime.now()
        }
        
        OptimizedTimePickerDialog(
            initialTime = initialTime,
            onDismiss = { showTimePickerFor = null },
            onConfirm = { selectedTime ->
                editedTimes = null
                when(showTimePickerFor) {
                    "morning" -> morningStartTime = selectedTime
                    "afternoon" -> afternoonStartTime = selectedTime
                    "evening" -> eveningStartTime = selectedTime
                }
                showTimePickerFor = null
            }
        )
    }
    editingSection?.let { index ->
        preview.getOrNull()?.let { times ->
            SectionTimeEditDialog(times = times, index = index,
                onDismissRequest = { editingSection = null },
                onConfirm = { editedTimes = it; editingSection = null })
        }
    }
}
@Composable
private fun SettingsGroupCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
            content()
        }
    }
}

@Composable
private fun CompactNumericInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    prefix: String = "",
    suffix: String = "",
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.all { c -> c.isDigit() }) onValueChange(it) },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        prefix = if (prefix.isNotEmpty()) { { Text(prefix) } } else null,
        suffix = if (suffix.isNotEmpty()) { { Text(suffix) } } else null,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    )
}

@Composable
private fun TimeNodeRow(
    icon: ImageVector,
    label: String,
    time: LocalTime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        
        Text(
            text = time.format(DateTimeFormatter.ofPattern("HH:mm")),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

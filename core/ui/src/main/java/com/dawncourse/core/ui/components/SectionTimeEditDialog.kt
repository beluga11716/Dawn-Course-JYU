package com.dawncourse.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dawncourse.core.domain.model.SectionTime
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 所有节次编辑入口共用相同的平移规则，确认前不修改已保存的设置。 */
@Composable
fun SectionTimeEditDialog(
    times: List<SectionTime>,
    index: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (List<SectionTime>) -> Unit,
    editor: SectionTimeEditorViewModel = hiltViewModel(),
) {
    val original = times.getOrNull(index) ?: return
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    fun minutes(value: String): Int = LocalTime.parse(value).let { it.hour * 60 + it.minute }
    fun display(value: Int): String = if (value in 0 until 1440) LocalTime.of(value / 60, value % 60).format(formatter) else "超出当天"
    var start by remember(original, index) { mutableIntStateOf(runCatching { minutes(original.startTime) }.getOrDefault(480)) }
    var end by remember(original, index) { mutableIntStateOf(runCatching { minutes(original.endTime) }.getOrDefault(525)) }
    var editingStart by remember { mutableStateOf<Boolean?>(null) }
    val adjusted = remember(times, index, start, end) {
        if (start in 0 until 1440 && end in 0 until 1440) editor.adjust(times, index, display(start), display(end))
        else Result.failure(IllegalArgumentException("调整后本节超出当天，请缩小调整幅度"))
    }
    val offset = runCatching { end - minutes(original.endTime) }.getOrDefault(0)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("编辑第 ${index + 1} 节时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { editingStart = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("上课时间  ${display(start)}")
                }
                OutlinedButton(onClick = { editingStart = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("下课时间  ${display(end)}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { start -= 10; end -= 10 }) { Text("提前 10 分钟") }
                    TextButton(onClick = { start += 10; end += 10 }) { Text("延后 10 分钟") }
                }
                Text("改上课时间会保留本节时长；改下课时间会调整本节时长。后续所有节次一起平移，课间间隔不变。",
                    style = MaterialTheme.typography.bodySmall)
                if (index < times.lastIndex && offset != 0 && adjusted.isSuccess) {
                    Text("第 ${index + 2}—${times.size} 节将${if (offset > 0) "延后" else "提前"} ${kotlin.math.abs(offset)} 分钟（含下午、晚上）",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                adjusted.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = adjusted.isSuccess, onClick = { adjusted.getOrNull()?.let(onConfirm) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text("取消") } },
    )
    editingStart?.let { isStart ->
        key(isStart) {
            OptimizedTimePickerDialog(
                initialTime = (if (isStart) start else end).coerceIn(0, 1439).let { LocalTime.of(it / 60, it % 60) },
                onDismiss = { editingStart = null },
                onConfirm = { selected ->
                    if (isStart) {
                        val selectedMinutes = selected.hour * 60 + selected.minute
                        end += selectedMinutes - start
                        start = selectedMinutes
                    } else end = selected.hour * 60 + selected.minute
                    editingStart = null
                },
            )
        }
    }
}

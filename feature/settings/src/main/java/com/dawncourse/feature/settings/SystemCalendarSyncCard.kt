package com.dawncourse.feature.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dawncourse.core.domain.model.CALENDAR_REMINDER_OPTIONS
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemCalendarSyncScreen(onBackClick: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("系统日历同步") }, navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            SystemCalendarSyncCard()
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SystemCalendarSyncCard(viewModel: SystemCalendarSyncViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    var minutes by rememberSaveable(status.reminderMinutes) { mutableIntStateOf(status.reminderMinutes) }
    var pendingMinutes by rememberSaveable { mutableIntStateOf(10) }
    var requestingPermission by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestingPermission = false
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) viewModel.sync(pendingMinutes)
        else viewModel.permissionDenied()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshStatus() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("系统日历同步", style = MaterialTheme.typography.titleMedium)
            Text("将当前学期课程写入手机日历，由日历在课前提醒。首次使用需允许读取和写入日历。",
                style = MaterialTheme.typography.bodySmall)
            if (status.semesterName.isNotBlank()) Text("当前学期：${status.semesterName}", style = MaterialTheme.typography.bodySmall)
            if (status.enabled) {
                Text("已开启 · ${status.eventCount} 次课程 · 提前 ${status.reminderMinutes} 分钟提醒", style = MaterialTheme.typography.bodyMedium)
                if (status.lastSyncAt > 0) Text("上次写入：${Instant.ofEpochMilli(status.lastSyncAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("课前提醒时间", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CALENDAR_REMINDER_OPTIONS.forEach { value ->
                    FilterChip(selected = minutes == value, onClick = { minutes = value }, enabled = !busy && !requestingPermission,
                        label = { Text("$value 分钟") })
                }
            }
            Button(onClick = {
                pendingMinutes = minutes
                if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) viewModel.sync(minutes)
                else {
                    requestingPermission = true
                    launcher.launch(permissions)
                }
            }, enabled = !busy && !requestingPermission && status.semesterName.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (status.enabled) "更新日历日程" else "同步到系统日历")
            }
            if (status.enabled || status.eventCount > 0) {
                Row {
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "未找到日历应用，请安装或启用手机日历", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("打开系统日历") }
                    TextButton(onClick = { confirmRemove = true }, enabled = !busy) { Text("停止并移除日程") }
                }
            }
            Text("开启后，本课表的课程、学期或节次时间变化会自动更新到日历。再次同步不会重复添加。",
                style = MaterialTheme.typography.bodySmall)
            Text("请在系统日历中勾选「课程表」日历，并开启日历通知；声音和弹窗由手机的通知、勿扰设置决定。",
                style = MaterialTheme.typography.bodySmall)
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
            if (status.problem.isNotBlank() && status.problem != message) Text(status.problem,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text("停止日历同步？") },
        text = { Text("停止当前课表的日历同步，并移除本应用为它写入的课程日程。应用内课程和其他日程保留。") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; viewModel.remove() }) { Text("停止并移除") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } })
}

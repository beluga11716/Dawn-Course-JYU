package com.dawncourse.feature.import_module

import android.Manifest
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.dawncourse.core.domain.model.ZhengFangAcademicTerm
import com.dawncourse.core.domain.model.ZhengFangLoginSnapshot
import com.dawncourse.core.domain.model.ZhengFangWebVpn
import com.dawncourse.core.ui.components.DawnDatePickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 嘉应学院统一入口；旧网页同步向导不在此页面初始化或加载。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZhengFangSyncScreen(onBackClick: () -> Unit, openLoginOnEntry: Boolean = false) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("嘉应学院登录与同步") }, navigationIcon = {
            IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            ZhengFangSessionCard(openLoginOnEntry = openLoginOnEntry)
        }
    }
}

/** 会话读操作由父页面即时执行，Cookie 不进入 Compose 持久化状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZhengFangSessionCard(
    openLoginOnEntry: Boolean = false,
    viewModel: ZhengFangSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val semester by viewModel.semesterName.collectAsState()
    val loginAccount by viewModel.loginAccount.collectAsState()
    val remoteTerm by viewModel.remoteTerm.collectAsState()
    val initialized by viewModel.initialized.collectAsState()
    val canLogin by viewModel.canLogin.collectAsState()
    val hasSession by viewModel.hasSession.collectAsState()
    val startDateRequired by viewModel.startDateRequired.collectAsState()
    var showLogin by remember { mutableStateOf(false) }
    var automaticImport by remember { mutableStateOf(true) }
    var entryLoginHandled by rememberSaveable { mutableStateOf(false) }
    var confirmUnbind by remember { mutableStateOf(false) }
    LaunchedEffect(initialized, canLogin) {
        if (initialized && canLogin && openLoginOnEntry && !entryLoginHandled) {
            showLogin = true
            entryLoginHandled = true
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* 被拒仍可在页内查看状态。 */ }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("嘉应学院后台同步", style = MaterialTheme.typography.titleMedium)
            Text(if (semester.isBlank()) "无需先建学期：网页登录后自动识别学期并导入课表"
                else "当前学期：$semester", style = MaterialTheme.typography.bodySmall)
            if (hasSession) {
                val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (darkTheme) Color(0xFF153C28) else Color(0xFFE5F5E9),
                    contentColor = if (darkTheme) Color(0xFFA0E5B4) else Color(0xFF1B6335),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("已保存教务登录会话", style = MaterialTheme.typography.bodySmall)
                            if (loginAccount.isNotBlank()) {
                                Text("已识别学号：$loginAccount", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Row {
                    TextButton(onClick = { automaticImport = false; showLogin = true }, enabled = !busy) { Text("更换网页账号") }
                    TextButton(onClick = { confirmUnbind = true }, enabled = !busy) { Text("移除保存的会话") }
                }
            } else {
                Text("账号密码只在网页填写。WebVPN 和教务系统使用同一学号、各自的密码。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { automaticImport = true; showLogin = true }, enabled = initialized && canLogin && !busy,
                    modifier = Modifier.fillMaxWidth()) { Text("网页登录并自动导入") }
                Text("登录后自动打开个人课表；读取不到开学日期时，在日历中选择一次即可。", style = MaterialTheme.typography.bodySmall)
            }
            remoteTerm?.let { (year, term) ->
                if (ZhengFangWebVpn.validTerm(year, term)) Text("教务学期：${ZhengFangAcademicTerm(year, term).name}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = viewModel::syncNow, enabled = !busy && state != null,
                modifier = Modifier.fillMaxWidth()) { Text("立即同步") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每 12 小时自动同步", Modifier.weight(1f))
                Switch(state?.autoSyncEnabled == true, onCheckedChange = { enabled ->
                    viewModel.setAutoSync(enabled)
                    if (enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }, enabled = !busy && state != null)
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            state?.let { value ->
                Text(value.message, style = MaterialTheme.typography.bodySmall)
                if (value.lastSuccessAt > 0) {
                    val formatted = Instant.ofEpochMilli(value.lastSuccessAt).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                    Text("上次同步成功：$formatted", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("会话过期后需手动登录一次；系统会根据网络和省电状态安排后台执行。", style = MaterialTheme.typography.bodySmall)
            Text("通知权限仅用于会话过期提醒；拒绝通知不影响开启自动同步。", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (confirmUnbind) {
        AlertDialog(onDismissRequest = { confirmUnbind = false }, title = { Text("移除保存的会话？") },
            text = { Text("停止后台更新并移除已保存的会话和旧版密码，已有课程保留。网页中的登录状态可在网页内退出。") },
            confirmButton = { TextButton(onClick = { confirmUnbind = false; viewModel.clearAccount() }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { confirmUnbind = false }) { Text("取消") } })
    }
    if (showLogin) {
        ZhengFangLoginDialog(automaticImport = automaticImport, onDismiss = { showLogin = false }, onSave = { snapshot ->
            viewModel.importFromLogin(snapshot)
            showLogin = false
        })
    }
    startDateRequired?.let { remote ->
        val displayMonth = when (remote.code) { "12" -> 2; "16" -> 7; else -> 9 }
        val displayYear = remote.year.toInt() + if (remote.code == "3") 0 else 1
        val dateState = rememberDatePickerState(yearRange = remote.year.toInt()..remote.year.toInt() + 1,
            initialDisplayedMonthMillis = LocalDate.of(displayYear, displayMonth, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DawnDatePickerDialog(dateState, onDismissRequest = { if (!busy) viewModel.cancelPendingImport() },
            onConfirm = {
                if (!busy) dateState.selectedDateMillis?.let {
                    viewModel.confirmImportStartDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                }
            }, title = "选择实际开学日（所在周为第 1 周）", confirmEnabled = !busy && dateState.selectedDateMillis != null)
    }
}

/** 可见 WebView 完成 WebVPN、统一认证和验证码；后台任务不创建 WebView。 */
@Composable
private fun ZhengFangLoginDialog(
    automaticImport: Boolean,
    onDismiss: () -> Unit,
    onSave: (ZhengFangLoginSnapshot) -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf(if (automaticImport) "请完成网页登录，进入个人课表后自动导入"
        else "请先在网页退出原账号，登录新账号后点「导入当前账号」") }
    var progress by remember { mutableIntStateOf(0) }
    var metadata by remember { mutableStateOf<ZhengFangLoginMetadata?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var delivered by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf("") }
    val currentOnSave by rememberUpdatedState(onSave)
    // 资源读取移到 IO；不在首次创建 WebView 的主线程上读 assets。
    LaunchedEffect(context) {
        script = withContext(Dispatchers.IO) {
            context.assets.open("jyu_login_metadata.js").bufferedReader().use { it.readText() }
        }
    }
    fun capture(view: WebView, found: ZhengFangLoginMetadata) {
        if (delivered || !ZhengFangWebVpn.isTimetableOrigin(view.url)) return
        val cookies = CookieManager.getInstance()
        val portalToken = cookies.getCookie(ZhengFangWebVpn.PORTAL_URL)?.split(';')
            ?.filter { it.trim().startsWith("webvpn-token=") }?.joinToString(";")
        val header = ZhengFangWebVpn.cookieHeader(
            cookies.getCookie(ZhengFangWebVpn.BASE_URL + ZhengFangWebVpn.TIMETABLE_PATH), portalToken)
        if (header != null) {
            delivered = true
            // Cookie 已在内存中可读，由仓库加密保存；不在 UI 线程同步 flush 磁盘。
            currentOnSave(ZhengFangLoginSnapshot(found.term, header, view.settings.userAgentString.orEmpty(),
                found.startDate, found.username))
        } else status = "尚未读到完整会话，请完成 WebVPN 和教务登录"
    }
    LaunchedEffect(webView, script) {
        var previous: ZhengFangLoginMetadata? = null
        var previousGeneration = -1
        while (!delivered) {
            val view = webView
            val url = view?.url
            val capturedGeneration = generation
            if (view != null && script.isNotBlank() && ZhengFangWebVpn.isTimetableOrigin(url)) {
                // 直接读取已经可用的 DOM，不等待 onPageFinished 和慢图片等资源。
                view.evaluateJavascript(script) { raw ->
                    if (!delivered && capturedGeneration == generation && view.url == url &&
                        ZhengFangWebVpn.isTimetableOrigin(view.url)) {
                        val found = ZhengFangLoginMetadata.parse(raw)
                        metadata = found
                        if (found != null && found == previous && previousGeneration == capturedGeneration && automaticImport) capture(view, found)
                        previous = found
                        previousGeneration = capturedGeneration
                    } else previous = null
                }
            } else previous = null
            delay(500)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            delivered = true
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("嘉应学院教务登录与导入", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                metadata?.let { Text("已识别：${it.term.name}", style = MaterialTheme.typography.bodySmall) }
                if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { webContext ->
                    WebView(webContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                                request.url.scheme !in setOf("http", "https")
                            override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) {
                                metadata = null
                                generation++
                            }
                            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                                if (request.isForMainFrame) status = "页面加载失败，请检查网络后刷新"
                            }
                            override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest, response: android.webkit.WebResourceResponse) {
                                if (request.isForMainFrame) status = "网页返回 HTTP ${response.statusCode}，可返回或刷新重试"
                            }
                        }
                        webView = this
                        loadUrl(ZhengFangWebVpn.BASE_URL)
                    }
                })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { webView?.let { if (it.canGoBack()) it.goBack() else it.reload() } }) { Text("网页返回") }
                    OutlinedButton(onClick = { webView?.reload() }) { Text("刷新") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                if (!automaticImport) Button(onClick = {
                    // 手动切换账号同样重新读 DOM，并复核导航代次，避免捕获旧账号页面。
                    val view = webView ?: return@Button
                    val url = view.url
                    val capturedGeneration = generation
                    view.evaluateJavascript(script) { raw ->
                        if (capturedGeneration == generation && url == view.url) {
                            ZhengFangLoginMetadata.parse(raw)?.let { capture(view, it) }
                        }
                    }
                }, enabled = metadata != null && !delivered, modifier = Modifier.fillMaxWidth()) { Text("导入当前账号") }
            }
        }
    }
}

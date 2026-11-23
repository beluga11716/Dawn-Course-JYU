package com.dawncourse.feature.import_module

import android.Manifest
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.TimetableSyncResult
import com.dawncourse.core.domain.model.ZhengFangSession
import com.dawncourse.core.domain.model.ZhengFangSyncState
import com.dawncourse.core.domain.model.ZhengFangSyncTarget
import com.dawncourse.core.domain.model.ZhengFangWebVpn
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.SyncSourceBindingRepository
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import com.dawncourse.core.domain.repository.ZhengFangSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 不保留 WebView 或 Context，只接收 UI 主线程读取的 Cookie 快照。 */
@HiltViewModel
class ZhengFangSessionViewModel @Inject constructor(
    private val profiles: TimetableProfileRepository,
    private val credentials: CredentialsRepository,
    private val bindings: SyncSourceBindingRepository,
    private val repository: ZhengFangSyncRepository,
) : ViewModel() {
    private var target: ZhengFangSyncTarget? = null
    private var expectedCredentials: SyncCredentials? = null
    private val _state = MutableStateFlow<ZhengFangSyncState?>(null)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow("嘉应学院 WebVPN：登录后保存会话，即可在后台更新当前学期")
    val message = _message.asStateFlow()
    private val _semesterName = MutableStateFlow("")
    val semesterName = _semesterName.asStateFlow()
    private val _loginAccount = MutableStateFlow("")
    val loginAccount = _loginAccount.asStateFlow()
    private val _remoteTerm = MutableStateFlow<Pair<String, String>?>(null)
    val remoteTerm = _remoteTerm.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val context = profiles.observeActiveContext().first() ?: return@launch
                val semester = context.semester ?: return@launch
                _semesterName.value = semester.name
                val saved = credentials.getCredentials(context.profile.id)
                if (saved?.provider != SyncProviderType.ZF || !ZhengFangWebVpn.isSupportedEntry(saved.endpointUrl)) {
                    _message.value = "请先在设置中绑定嘉应学院正方 WebVPN 入口与账号，再重新打开此页"
                    return@launch
                }
                val binding = bindings.ensureIfStillActive(context.profile.id, semester.id, SyncProviderType.ZF)
                    ?: return@launch
                target = ZhengFangSyncTarget(context.profile.id, semester.id, binding)
                expectedCredentials = saved
                _loginAccount.value = saved.username.orEmpty()
                _remoteTerm.value = saved.zhengFangSession?.let { it.academicYear to it.termCode }
                repository.states.collect { states ->
                    _state.value = states.firstOrNull { it.target == target }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "读取同步配置失败，请重新打开此页"
            }
        }
    }

    fun saveSession(cookie: String?, userAgent: String, year: String, term: String) = runAction {
        val captured = target
        val saved = expectedCredentials
        if (captured == null || saved == null) {
            _message.value = "请先在设置中绑定正方入口与账号，并选择本地学期，再重新打开此页"
            return@runAction
        }
        if (cookie == null) {
            _message.value = "尚未读到完整会话，请在 WebVPN 中登录并打开正方课表页后再保存"
            return@runAction
        }
        if (!ZhengFangWebVpn.validTerm(year, term)) {
            _message.value = "请输入有效学年和学期代码（3=第一学期，12=第二学期，16=第三学期）"
            return@runAction
        }
        val session = ZhengFangSession(cookie, year, term, captured.semesterId, userAgent, System.currentTimeMillis())
        val result = repository.captureSession(captured, saved, session)
        _message.value = result.description()
        if (result is TimetableSyncResult.Success) {
            val replacement = repository.states.first().firstOrNull {
                it.target.profileId == captured.profileId && it.target.semesterId == captured.semesterId
            }
            target = replacement?.target
            expectedCredentials = credentials.getCredentials(captured.profileId)
            _state.value = replacement
        }
    }

    fun syncNow() = runAction {
        val captured = target ?: return@runAction
        _message.value = repository.sync(captured).description()
    }

    /** 密码仅注入显式保存的同源页面，跨域 SSO 由用户完成。 */
    internal fun autofillScript(url: String?): String? {
        val saved = expectedCredentials ?: return null
        if (saved.secret.isBlank() || !WebViewCredentialAutofillPolicy.canAutoFill(saved.endpointUrl, url)) return null
        val script = """
            const username = document.querySelector('#yhm, input[name="username"], input[name="userName"]');
            const password = document.querySelector('#mm, input[type="password"]');
            if (username && password) {
              username.value = ${JSONObject.quote(saved.username.orEmpty())};
              password.value = ${JSONObject.quote(saved.secret)};
              username.dispatchEvent(new Event('input', {bubbles:true}));
              password.dispatchEvent(new Event('input', {bubbles:true}));
            }
        """.trimIndent()
        return WebViewCredentialAutofillPolicy.wrapCredentialScriptForVerifiedOrigin(saved.endpointUrl, script)
    }

    fun setAutoSync(enabled: Boolean) = runAction {
        val captured = target ?: return@runAction
        repository.setAutoSyncEnabled(captured, enabled)
    }

    private fun runAction(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "操作未完成，请重新打开同步页面后重试"
            } finally {
                _busy.value = false
            }
        }
    }

    private fun TimetableSyncResult.description(): String = when (this) {
        is TimetableSyncResult.Success -> message
        is TimetableSyncResult.Failure -> message
    }
}

/** 会话读操作由父页面即时执行，Cookie 不进入 Compose 持久化状态。 */
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
    val today = remember { LocalDate.now() }
    var year by remember { mutableStateOf((if (today.monthValue >= 8) today.year else today.year - 1).toString()) }
    var term by remember { mutableStateOf(if (today.monthValue in 2..7) "12" else "3") }
    LaunchedEffect(remoteTerm) {
        remoteTerm?.let { (savedYear, savedTerm) -> year = savedYear; term = savedTerm }
    }
    var showLogin by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(openLoginOnEntry) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* 被拒仍可在页内查看状态。 */ }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("嘉应学院后台同步", style = MaterialTheme.typography.titleMedium)
            Text("同步到本地学期：${semester.ifBlank { "请先选择学期" }}", style = MaterialTheme.typography.bodySmall)
            Text("请确认远端学年学期与本地学期一致；保存会话后，后台仅更新此学期。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(year, { year = it }, Modifier.weight(1f), label = { Text("起始学年") }, singleLine = true, enabled = !busy)
                OutlinedTextField(term, { term = it }, Modifier.weight(1f), label = { Text("学期代码 3 / 12 / 16") }, singleLine = true, enabled = !busy)
            }
            OutlinedButton(onClick = { showLogin = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("登录并记住会话 / 重新登录") }
            Button(onClick = viewModel::syncNow, enabled = !busy && state != null, modifier = Modifier.fillMaxWidth()) { Text("立即同步") }
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
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (showLogin) {
        ZhengFangLoginDialog(
            loginAccount = loginAccount,
            onDismiss = { showLogin = false },
            autofill = viewModel::autofillScript,
            onSave = { cookie, agent ->
                viewModel.saveSession(cookie, agent, year.trim(), term.trim())
                showLogin = false
            },
        )
    }
}

/** 可见 WebView 完成 WebVPN、统一认证和验证码；后台任务不创建 WebView。 */
@Composable
private fun ZhengFangLoginDialog(
    loginAccount: String,
    onDismiss: () -> Unit,
    autofill: (String?) -> String?,
    onSave: (String?, String) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("请完成 WebVPN 和教务登录，然后点保存会话") }
    var accountConfirmed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("嘉应学院教务登录", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text("绑定账号：${loginAccount.ifBlank { "未填写，请先在设置中绑定" }}；如网页显示其他账号，请先退出后重新登录。", style = MaterialTheme.typography.bodySmall)
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                                    request.url.scheme !in setOf("http", "https")

                                override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) {
                                    pageReady = false
                                    accountConfirmed = false
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    pageReady = ZhengFangWebVpn.isTimetableOrigin(view.url)
                                    autofill(view.url)?.let { view.evaluateJavascript(it, null) }
                                }

                                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                                    if (request.isForMainFrame) status = "页面加载失败，请检查网络后刷新"
                                }
                            }
                            webView = this
                            loadUrl(ZhengFangWebVpn.BASE_URL)
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(accountConfirmed, onCheckedChange = { accountConfirmed = it })
                    Text("已核对网页学号与绑定账号一致", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { webView?.let { if (it.canGoBack()) it.goBack() else it.reload() } }) { Text("返回/刷新") }
                    OutlinedButton(onClick = onDismiss) { Text("关闭") }
                    Button(modifier = Modifier.weight(1f), enabled = pageReady && accountConfirmed && loginAccount.isNotBlank(), onClick = {
                        val view = webView
                        if (!ZhengFangWebVpn.isTimetableOrigin(view?.url)) return@Button
                        val cookies = CookieManager.getInstance()
                        val portalToken = cookies.getCookie(ZhengFangWebVpn.PORTAL_URL)?.split(';')
                            ?.filter { it.trim().startsWith("webvpn-token=") }?.joinToString(";")
                        val header = ZhengFangWebVpn.cookieHeader(
                            cookies.getCookie(ZhengFangWebVpn.BASE_URL + ZhengFangWebVpn.TIMETABLE_PATH), portalToken,
                        )
                        if (header == null) {
                            status = "尚未读到完整会话，请完成登录并进入正方教务后重试"
                        } else {
                            cookies.flush()
                            onSave(header, view?.settings?.userAgentString.orEmpty())
                        }
                    }) { Text("保存会话") }
                }
            }
        }
    }
}

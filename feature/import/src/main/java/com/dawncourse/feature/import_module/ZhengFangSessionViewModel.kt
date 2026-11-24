package com.dawncourse.feature.import_module

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.*
import com.dawncourse.core.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 不保留 WebView 或 Context，只接收 UI 主线程读取的 Cookie 快照。 */
@HiltViewModel
class ZhengFangSessionViewModel @Inject constructor(
    private val profiles: TimetableProfileRepository,
    private val credentials: CredentialsRepository,
    private val bindings: SyncSourceBindingRepository,
    private val repository: ZhengFangSyncRepository,
) : ViewModel() {
    private var initialContext: ActiveTimetableContext? = null
    private var expectedCredentials: SyncCredentials? = null
    private var target: ZhengFangSyncTarget? = null
    private val _state = MutableStateFlow<ZhengFangSyncState?>(null)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow("直接在网页登录，识别学期后自动导入，无需在客户端填写账号密码")
    val message = _message.asStateFlow()
    private val _semesterName = MutableStateFlow("")
    val semesterName = _semesterName.asStateFlow()
    private val _loginAccount = MutableStateFlow("")
    val loginAccount = _loginAccount.asStateFlow()
    private val _remoteTerm = MutableStateFlow<Pair<String, String>?>(null)
    val remoteTerm = _remoteTerm.asStateFlow()
    private val _initialized = MutableStateFlow(false)
    val initialized = _initialized.asStateFlow()
    private val _canLogin = MutableStateFlow(false)
    val canLogin = _canLogin.asStateFlow()
    private val _hasSession = MutableStateFlow(false)
    val hasSession = _hasSession.asStateFlow()
    private var pendingImport: ZhengFangLoginSnapshot? = null
    private val _startDateRequired = MutableStateFlow<ZhengFangAcademicTerm?>(null)
    val startDateRequired = _startDateRequired.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                // 数据库选择流可能先发出 null；等待真实课表，避免永久锁住登录。
                val context = profiles.observeActiveContext().filterNotNull().first()
                initialContext = context
                _semesterName.value = context.semester?.name.orEmpty()
                val saved = credentials.getCredentials(context.profile.id)
                expectedCredentials = saved
                _canLogin.value = true
                if (saved?.provider == SyncProviderType.ZF && ZhengFangWebVpn.isSupportedEntry(saved.endpointUrl)) {
                    _loginAccount.value = saved.username.orEmpty()
                    _remoteTerm.value = saved.zhengFangSession?.let { it.academicYear to it.termCode }
                    _hasSession.value = saved.zhengFangSession != null
                    context.semester?.let { semester ->
                        val binding = bindings.ensureIfStillActive(context.profile.id, semester.id, SyncProviderType.ZF)
                        if (binding != null) {
                            target = ZhengFangSyncTarget(context.profile.id, semester.id, binding)
                            _state.value = repository.states.first().firstOrNull { it.target == target }
                        }
                    }
                } else if (saved != null) {
                    _message.value = "当前课表已有其他同步来源；成功导入教务课表后会替换该来源"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "读取同步配置失败，请重新打开此页"
            } finally {
                _initialized.value = true
            }
        }
        viewModelScope.launch {
            repository.states.catch {
                _state.value = null
                _message.value = "读取同步状态失败，请重新打开此页"
            }.collect { states -> _state.value = states.firstOrNull { it.target == target } }
        }
    }

    /** 首次登录不依赖本地账号或学期；HTTP 验证后才写入。 */
    fun importFromLogin(snapshot: ZhengFangLoginSnapshot) = runAction {
        cancelPendingImport()
        importSnapshot(snapshot)
    }

    private suspend fun importSnapshot(snapshot: ZhengFangLoginSnapshot, confirmingStartDate: Boolean = false) {
        val context = initialContext ?: return
        _message.value = "正在读取教务课表…"
        when (val result = repository.importFromLogin(context, expectedCredentials, snapshot, confirmingStartDate)) {
            is ZhengFangLoginImportResult.Success -> {
                initialContext = result.context
                target = result.target
                expectedCredentials = credentials.getCredentials(result.target.profileId)
                _hasSession.value = true
                _loginAccount.value = expectedCredentials?.username.orEmpty()
                _semesterName.value = result.context.semester?.name.orEmpty()
                _remoteTerm.value = snapshot.term.year to snapshot.term.code
                _state.value = repository.states.first().firstOrNull { it.target == result.target }
                _message.value = "已识别${snapshot.term.name}，导入 ${result.courseCount} 条课程安排" +
                    if (_state.value == null) "；同步状态保存失败，请重新登录后开启自动同步" else ""
                cancelPendingImport()
            }
            is ZhengFangLoginImportResult.NeedsStartDate -> {
                pendingImport = snapshot
                _startDateRequired.value = result.term
                _message.value = "学期和课表已读取，请在日历中选择实际开学日期"
            }
            is ZhengFangLoginImportResult.Failure -> {
                cancelPendingImport()
                _message.value = result.message
            }
        }
    }

    fun confirmImportStartDate(value: String) = runAction {
        val snapshot = pendingImport ?: return@runAction
        val date = runCatching { LocalDate.parse(value) }.getOrNull()
        if (date == null || date.year !in snapshot.term.year.toInt()..snapshot.term.year.toInt() + 1) {
            _message.value = "请选择该学年的开学日期"
            return@runAction
        }
        // 允许任意开学日，课程和日历仍按该周周一作为第一周起点。
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        importSnapshot(snapshot.copy(startDate = monday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()),
            confirmingStartDate = true)
    }

    fun cancelPendingImport() {
        pendingImport = null
        _startDateRequired.value = null
    }

    fun clearAccount() = runAction {
        val context = initialContext ?: return@runAction
        when (bindings.clearCredentialsAndUnbindIfActive(context.profile.id,
            CredentialBindingExpectation(context.semester?.id, expectedCredentials))) {
            is CredentialBindingMutationResult.Success -> {
                expectedCredentials = null
                target = null
                _state.value = null
                _hasSession.value = false
                _loginAccount.value = ""
                _remoteTerm.value = null
                cancelPendingImport()
                _message.value = "已移除保存的会话，已有课程保留；更换账号请在网页中退出后重新登录"
            }
            is CredentialBindingMutationResult.Rejected -> _message.value = "账号配置已变化，请重新打开同步页"
            is CredentialBindingMutationResult.Inconsistent -> {
                _canLogin.value = false
                target = null
                _state.value = null
                _message.value = "解绑与恢复未完成，请重新打开本页核对账号，暂勿同步"
            }
        }
    }

    fun syncNow() = runAction {
        val captured = target ?: return@runAction
        _message.value = when (val result = repository.sync(captured)) {
            is TimetableSyncResult.Success -> result.message
            is TimetableSyncResult.Failure -> result.message
        }
    }

    fun setAutoSync(enabled: Boolean) = runAction {
        target?.let { repository.setAutoSyncEnabled(it, enabled) }
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
}

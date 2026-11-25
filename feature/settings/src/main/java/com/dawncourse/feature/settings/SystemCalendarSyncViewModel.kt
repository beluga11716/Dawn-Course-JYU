package com.dawncourse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.SystemCalendarSyncStatus
import com.dawncourse.core.domain.repository.SystemCalendarSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SystemCalendarSyncViewModel @Inject constructor(
    private val repository: SystemCalendarSyncRepository,
) : ViewModel() {
    private val _status = MutableStateFlow(SystemCalendarSyncStatus())
    val status = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow("")
    val message = _message.asStateFlow()
    private val refresh = MutableStateFlow(0L)

    init {
        viewModelScope.launch {
            refresh.flatMapLatest {
                repository.observeStatus().catch { _message.value = "读取系统日历状态失败，请稍后重试" }
            }.collect { _status.value = it }
        }
    }

    fun refreshStatus() { refresh.value += 1 }
    fun permissionDenied() { _message.value = "未获得日历权限，尚未写入日程。可在系统设置中允许读取和写入日历后重试。" }
    fun sync(minutes: Int) = runAction { repository.syncCurrent(minutes).message }
    fun remove() = runAction { repository.removeCurrent().message }

    private fun runAction(action: suspend () -> String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                _message.value = action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "系统日历操作未完成，请稍后重试"
            } finally {
                _busy.value = false
                refreshStatus()
            }
        }
    }
}

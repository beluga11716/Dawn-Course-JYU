package com.dawncourse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.WidgetTextTheme
import com.dawncourse.core.domain.usecase.UpdateWidgetAppearanceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WidgetSettingsViewModel @Inject constructor(
    private val updateAppearance: UpdateWidgetAppearanceUseCase,
) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val appearance = updateAppearance.appearance
        .catch { _error.value = "小组件设置读取失败，请返回后重试" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setBackgroundOpacity(opacity: Int) = save {
        updateAppearance.setBackgroundOpacity(opacity)
    }

    fun setTextTheme(theme: WidgetTextTheme) = save {
        updateAppearance.setTextTheme(theme)
    }

    fun dismissError() { _error.value = null }

    private fun save(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _error.value = "设置未保存，请重试"
            }
        }
    }
}

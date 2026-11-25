package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.WidgetTextTheme
import com.dawncourse.core.domain.repository.WidgetAppearanceRepository
import com.dawncourse.core.domain.repository.WidgetUpdateRepository
import javax.inject.Inject

/** 保存完成后刷新已添加的全部小组件，避免桌面仍显示旧样式。 */
class UpdateWidgetAppearanceUseCase @Inject constructor(
    private val repository: WidgetAppearanceRepository,
    private val widgetUpdateRepository: WidgetUpdateRepository,
) {
    val appearance = repository.appearance

    suspend fun setBackgroundOpacity(opacity: Int) {
        repository.setBackgroundOpacity(opacity.coerceIn(0, 100))
        widgetUpdateRepository.triggerUpdate()
    }

    suspend fun setTextTheme(theme: WidgetTextTheme) {
        repository.setTextTheme(theme)
        widgetUpdateRepository.triggerUpdate()
    }
}

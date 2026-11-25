package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.WidgetAppearance
import com.dawncourse.core.domain.model.WidgetTextTheme
import kotlinx.coroutines.flow.Flow

interface WidgetAppearanceRepository {
    val appearance: Flow<WidgetAppearance>
    suspend fun setBackgroundOpacity(opacity: Int)
    suspend fun setTextTheme(theme: WidgetTextTheme)
}

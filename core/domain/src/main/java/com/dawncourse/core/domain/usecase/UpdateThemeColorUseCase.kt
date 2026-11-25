package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.ThemeColor
import com.dawncourse.core.domain.repository.SettingsRepository
import javax.inject.Inject

class UpdateThemeColorUseCase @Inject constructor(private val repository: SettingsRepository) {
    suspend operator fun invoke(color: ThemeColor) = repository.setThemeColor(color)
}

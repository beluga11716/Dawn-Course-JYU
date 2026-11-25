package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.WidgetAppearance
import com.dawncourse.core.domain.model.WidgetTextTheme
import com.dawncourse.core.domain.repository.WidgetAppearanceRepository
import com.dawncourse.core.domain.repository.WidgetUpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateWidgetAppearanceUseCaseTest {
    @Test
    fun widgetsRefreshAfterNewAppearanceIsSaved() = runBlocking {
        val repository = FakeAppearanceRepository()
        val rendered = mutableListOf<WidgetAppearance>()
        val updater = object : WidgetUpdateRepository {
            override fun triggerUpdate() { rendered += repository.appearance.value }
        }
        val useCase = UpdateWidgetAppearanceUseCase(repository, updater)
        useCase.setBackgroundOpacity(-10)
        useCase.setTextTheme(WidgetTextTheme.LIGHT)
        assertEquals(listOf(WidgetAppearance(0), WidgetAppearance(0, WidgetTextTheme.LIGHT)), rendered)
    }

    @Test
    fun failedSaveDoesNotAnnounceWidgetRefresh() {
        val repository = FakeAppearanceRepository(failWrites = true)
        var updates = 0
        val updater = object : WidgetUpdateRepository {
            override fun triggerUpdate() { updates++ }
        }
        val useCase = UpdateWidgetAppearanceUseCase(repository, updater)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { useCase.setBackgroundOpacity(0) }
        }
        assertEquals(0, updates)
    }

    private class FakeAppearanceRepository(val failWrites: Boolean = false) : WidgetAppearanceRepository {
        override val appearance = MutableStateFlow(WidgetAppearance())
        override suspend fun setBackgroundOpacity(opacity: Int) {
            check(!failWrites)
            appearance.value = appearance.value.copy(backgroundOpacity = opacity)
        }
        override suspend fun setTextTheme(theme: WidgetTextTheme) {
            check(!failWrites)
            appearance.value = appearance.value.copy(textTheme = theme)
        }
    }
}

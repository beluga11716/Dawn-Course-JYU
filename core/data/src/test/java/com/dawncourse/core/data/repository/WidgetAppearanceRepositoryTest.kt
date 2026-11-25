package com.dawncourse.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dawncourse.core.domain.model.WidgetAppearance
import com.dawncourse.core.domain.model.WidgetTextTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAppearanceRepositoryTest {
    private val store = InMemoryPreferencesDataStore()

    @Test
    fun oldInstallKeepsOpaqueBackgroundAndSystemText() = runBlocking {
        withStore { repository, _ ->
            assertEquals(WidgetAppearance(), repository.appearance.first())
        }
    }

    @Test
    fun transparentAppearanceSurvivesRepositoryRecreationWithoutChangingOtherSettings() = runBlocking {
        withStore { repository, store ->
            store.edit { it[stringPreferencesKey("wallpaper_uri")] = "existing-wallpaper" }
            repository.setBackgroundOpacity(0)
            repository.setTextTheme(WidgetTextTheme.LIGHT)
        }
        withStore { repository, store ->
            assertEquals(WidgetAppearance(0, WidgetTextTheme.LIGHT), repository.appearance.first())
            assertEquals("existing-wallpaper", store.data.first()[stringPreferencesKey("wallpaper_uri")])
        }
    }

    @Test
    fun invalidStoredValuesFallBackToSafeAppearance() = runBlocking {
        withStore { repository, store ->
            store.edit {
                it[intPreferencesKey("widget_background_opacity")] = -200
                it[stringPreferencesKey("widget_text_theme")] = "future-theme"
            }
            assertEquals(WidgetAppearance(0, WidgetTextTheme.FOLLOW_SYSTEM), repository.appearance.first())
            repository.setBackgroundOpacity(120)
            assertEquals(1f, repository.appearance.first().backgroundAlpha)
        }
    }

    private suspend fun withStore(
        action: suspend (WidgetAppearanceRepositoryImpl, DataStore<Preferences>) -> Unit,
    ) {
        action(WidgetAppearanceRepositoryImpl(store), store)
    }

    // Windows JVM 的 FileStorage 无法覆盖 rename 已存在文件；领域键值契约使用相同的
    // DataStore 测试替身，Android 上的落盘与进程重启另由模拟器验证。
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}

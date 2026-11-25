package com.dawncourse.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dawncourse.core.domain.model.WidgetAppearance
import com.dawncourse.core.domain.model.WidgetTextTheme
import com.dawncourse.core.domain.repository.WidgetAppearanceRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Singleton
class WidgetAppearanceRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : WidgetAppearanceRepository {
    override val appearance = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { preferences ->
        WidgetAppearance(
            backgroundOpacity = (preferences[BACKGROUND_OPACITY] ?: 100).coerceIn(0, 100),
            textTheme = WidgetTextTheme.entries.firstOrNull {
                it.name == preferences[TEXT_THEME]
            } ?: WidgetTextTheme.FOLLOW_SYSTEM,
        )
    }

    override suspend fun setBackgroundOpacity(opacity: Int) {
        dataStore.edit { it[BACKGROUND_OPACITY] = opacity.coerceIn(0, 100) }
    }

    override suspend fun setTextTheme(theme: WidgetTextTheme) {
        dataStore.edit { it[TEXT_THEME] = theme.name }
    }

    private companion object {
        val BACKGROUND_OPACITY = intPreferencesKey("widget_background_opacity")
        val TEXT_THEME = stringPreferencesKey("widget_text_theme")
    }
}

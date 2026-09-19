package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.engine.SupportedEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_core_preferences")

/**
 * DataStore repository for persisting on-device AI settings, including the preferred LlmEngine.
 */
class SettingsPreferencesRepository(private val context: Context) {

    companion object {
        val KEY_LLM_ENGINE = stringPreferencesKey("key_llm_engine_type")
        val KEY_DARK_THEME = stringPreferencesKey("key_dark_theme")
        val KEY_FOREGROUND_SERVICE = stringPreferencesKey("key_foreground_service")
    }

    val selectedEngineFlow: Flow<SupportedEngine> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val engineName = preferences[KEY_LLM_ENGINE] ?: SupportedEngine.LLAMA_CPP.name
            try {
                SupportedEngine.valueOf(engineName)
            } catch (e: IllegalArgumentException) {
                SupportedEngine.LLAMA_CPP
            }
        }

    suspend fun saveEnginePreference(engine: SupportedEngine) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LLM_ENGINE] = engine.name
        }
    }
}

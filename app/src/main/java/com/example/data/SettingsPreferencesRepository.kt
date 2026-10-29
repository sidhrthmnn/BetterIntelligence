package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.engine.SupportedEngine
import com.example.engine.GenerationParams
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
        private val KEY_APPEARANCE = stringPreferencesKey("appearance")
        private val KEY_WEB_ALLOWED = stringPreferencesKey("web_allowed")
        private val KEY_WEB_MODE = stringPreferencesKey("web_default_mode")
        private val KEY_WEB_RESEARCH_CONSENT = stringPreferencesKey("web_research_consent")
        private val KEY_CONVERSATION = stringPreferencesKey("active_conversation")
        private val KEY_MEMORY = stringPreferencesKey("chat_memory_enabled")
        private val KEY_CONTEXT = stringPreferencesKey("chat_context")
        private val KEY_TEMPERATURE = stringPreferencesKey("chat_temperature")
        private val KEY_OUTPUT = stringPreferencesKey("chat_output_tokens")
        private val KEY_THREADS = stringPreferencesKey("chat_cpu_threads")
        private val KEY_PRIMARY_MODEL_ID = stringPreferencesKey("primary_model_id")
        private val KEY_SECONDARY_MODEL_ID = stringPreferencesKey("secondary_model_id")
        private val KEY_AUTO_RAM_FALLBACK = stringPreferencesKey("auto_ram_fallback")
        internal fun webConsentAccepted(allowed: String?, consent: String?) = allowed == "true" && consent == "5"
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

    val darkThemeFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_DARK_THEME]?.toBooleanStrictOrNull() ?: true }

    suspend fun saveDarkTheme(dark: Boolean) {
        saveAppearance(if (dark) "DARK" else "LIGHT")
    }

    private val preferences = context.dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    val appearance = preferences.map { it[KEY_APPEARANCE] ?: if (it[KEY_DARK_THEME] == "false") "LIGHT" else "DARK" }
    // A new search destination needs a new disclosure; never expand an old consent silently.
    val webAllowed = preferences.map { webConsentAccepted(it[KEY_WEB_ALLOWED], it[KEY_WEB_RESEARCH_CONSENT]) }
    val webMode = preferences.map { it[KEY_WEB_MODE] ?: "OFF" }
    val activeConversation = preferences.map { it[KEY_CONVERSATION]?.toLongOrNull() ?: 0L }
    val memoryEnabled = preferences.map { it[KEY_MEMORY] == "true" }
    val foregroundEnabled = preferences.map { it[KEY_FOREGROUND_SERVICE]?.toBooleanStrictOrNull() ?: true }
    val primaryModelId: Flow<Long?> = preferences.map { it[KEY_PRIMARY_MODEL_ID]?.toLongOrNull() }
    val secondaryModelId: Flow<Long?> = preferences.map { it[KEY_SECONDARY_MODEL_ID]?.toLongOrNull() }
    val autoRamFallback: Flow<Boolean> = preferences.map { it[KEY_AUTO_RAM_FALLBACK]?.toBooleanStrictOrNull() ?: true }
    val generationPreferences = preferences.map { values ->
        val context = (values[KEY_CONTEXT]?.toIntOrNull() ?: 2048).coerceIn(512, 2048)
        GenerationParams(contextSize = context, maxTokens = (values[KEY_OUTPUT]?.toIntOrNull() ?: 256).coerceIn(64, context / 2),
            temperature = (values[KEY_TEMPERATURE]?.toFloatOrNull() ?: .7f).coerceIn(0f, 1.5f),
            threads = (values[KEY_THREADS]?.toIntOrNull() ?: 0).coerceIn(0, 8))
    }
    suspend fun saveGenerationPreferences(params: GenerationParams) { context.dataStore.edit {
        it[KEY_CONTEXT] = params.contextSize.toString(); it[KEY_OUTPUT] = params.maxTokens.toString(); it[KEY_TEMPERATURE] = params.temperature.toString()
        it[KEY_THREADS] = params.threads.coerceIn(0, 8).toString()
    } }
    suspend fun saveForegroundEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_FOREGROUND_SERVICE] = enabled.toString() } }
    suspend fun saveAppearance(value: String) {
        require(value in setOf("LIGHT", "DARK", "AMOLED"))
        context.dataStore.edit { it[KEY_APPEARANCE] = value; it[KEY_DARK_THEME] = (value != "LIGHT").toString() }
    }
    suspend fun saveWebAllowed(value: Boolean) { context.dataStore.edit { it[KEY_WEB_ALLOWED] = value.toString(); it[KEY_WEB_RESEARCH_CONSENT] = "5" } }
    suspend fun saveWebMode(value: String) { require(value in setOf("OFF", "AUTO", "ON")); context.dataStore.edit { it[KEY_WEB_MODE] = value } }
    suspend fun saveActiveConversation(id: Long) { context.dataStore.edit { it[KEY_CONVERSATION] = id.toString() } }
    suspend fun saveMemoryEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_MEMORY] = enabled.toString() } }
    suspend fun savePrimaryModelId(id: Long?) {
        context.dataStore.edit {
            if (id == null) it.remove(KEY_PRIMARY_MODEL_ID) else it[KEY_PRIMARY_MODEL_ID] = id.toString()
        }
    }
    suspend fun saveSecondaryModelId(id: Long?) {
        context.dataStore.edit {
            if (id == null) it.remove(KEY_SECONDARY_MODEL_ID) else it[KEY_SECONDARY_MODEL_ID] = id.toString()
        }
    }
    suspend fun saveAutoRamFallback(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_RAM_FALLBACK] = enabled.toString() }
    }
}

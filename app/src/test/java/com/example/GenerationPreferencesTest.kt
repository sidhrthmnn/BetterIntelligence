package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.SettingsPreferencesRepository
import com.example.engine.GenerationParams
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationPreferencesTest {
    @Test fun selectedThreadsSurviveOtherPreferenceWritesAndAreBounded() = runBlocking {
        val repository = SettingsPreferencesRepository(ApplicationProvider.getApplicationContext())
        try {
            repository.saveGenerationPreferences(GenerationParams(threads = 4))
            repository.saveActiveConversation(123)
            repository.saveAppearance("DARK")
            assertEquals(4, repository.generationPreferences.first().threads)
            repository.saveGenerationPreferences(GenerationParams(threads = 99))
            assertEquals(8, repository.generationPreferences.first().threads)
            repository.saveGenerationPreferences(GenerationParams(threads = -2))
            assertEquals(0, repository.generationPreferences.first().threads)
        } finally { repository.saveGenerationPreferences(GenerationParams()) }
    }
}

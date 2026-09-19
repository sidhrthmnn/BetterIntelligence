package com.example

import com.example.engine.GenerationConfig
import com.example.engine.HardwareValidator
import com.example.engine.SupportedEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineManagerTest {

    @Test
    fun testSupportedEngineAttributes() {
        assertEquals("llama.cpp (Native NDK)", SupportedEngine.LLAMA_CPP.displayName)
        assertEquals(".GGUF", SupportedEngine.LLAMA_CPP.formatBadge)

        assertEquals("LiteRT (Google GenAI)", SupportedEngine.LITERT.displayName)
        assertEquals(".TFLITE / .BIN", SupportedEngine.LITERT.formatBadge)
    }

    @Test
    fun testGenerationConfigDefaults() {
        val config = GenerationConfig(
            temperature = 0.7f,
            topK = 40,
            topP = 0.9f,
            maxTokens = 512,
            systemPrompt = "You are a test assistant"
        )

        assertEquals(0.7f, config.temperature, 0.001f)
        assertEquals(40, config.topK)
        assertEquals(0.9f, config.topP, 0.001f)
        assertEquals(512, config.maxTokens)
        assertEquals("You are a test assistant", config.systemPrompt)
    }

    @Test
    fun testHardwareValidationThresholds() {
        assertEquals(6000L, HardwareValidator.MIN_RECOMMENDED_RAM_MB)
        assertEquals(1500L, HardwareValidator.MIN_FREE_RAM_MB)

        val result = HardwareValidator.ValidationResult(
            isSupported = true,
            isArm64 = true,
            availableRamMb = 4000,
            totalRamMb = 8000,
            isLowMemory = false
        )

        assertTrue(result.isCompatible)
        assertTrue(result.hasSufficientRam)
        assertEquals("arm64-v8a", result.abi)
    }
}

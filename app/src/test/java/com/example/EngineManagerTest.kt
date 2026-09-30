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

    @Test
    fun testParseSmolLm2BaseModelGguf() {
        val file = java.io.File("../models/smollm2-135m-instruct-q4_k_m.gguf")
        assertTrue("Base model GGUF must exist in assets", file.exists())
        val parser = com.example.engine.GgufParser()
        val result = parser.parseFromFile(file)
        assertTrue("Parsing should succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val meta = result.getOrNull()
        assertNotNull(meta)
        assertEquals("llama", meta!!.architecture)
        assertEquals(com.example.engine.QuantizationType.Q4_K_M, meta.primaryQuantization)
        assertTrue("Tensor count should be 272", meta.tensorCount >= 270)
        assertEquals(49152, meta.vocabSize)
    }
}

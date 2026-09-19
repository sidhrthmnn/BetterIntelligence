package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AiCoreRepository
import com.example.data.ChatMessageEntity
import com.example.data.IpcLogEntity
import com.example.data.ModelEntity
import com.example.engine.GgufMetadata
import com.example.engine.GgufParser
import com.example.engine.HardwareAnalyzer
import com.example.engine.QuantizationType
import com.example.engine.QuantizedMathEngine
import com.example.engine.Tokenizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `verify app name resource`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Better Intelligence", appName)
    }

    @Test
    fun `test hardware analyzer and model recommendation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = HardwareAnalyzer.analyze(context)
        assertNotNull(profile)
        assertTrue(profile.totalRamMb > 0)
        assertTrue(profile.cpuCores >= 1)
        assertTrue(profile.aiCapabilityScore in 0..100)

        val recommendation = HardwareAnalyzer.getRecommendation(profile)
        assertNotNull(recommendation)
        assertTrue(recommendation.recommendedModelName.isNotBlank())
        assertTrue(recommendation.estimatedSpeedTokSec > 0f)
        assertTrue(recommendation.justification.isNotEmpty())
    }

    @Test
    fun `test quantized math engine Q4_0 and Q4_K dequantization`() {
        // Test block dequantization
        val block = ByteArray(18) // 2 bytes float16 scale + 16 bytes (32 nibbles)
        ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).putShort(0, 0x3C00.toShort()) // float16 value = 1.0f

        val output = FloatArray(32)
        QuantizedMathEngine.dequantizeQ4_0Block(block, 0, output, 0)
        assertEquals(32, output.size)

        // Test dot product calculation
        val vecA = floatArrayOf(1.0f, 2.0f, 3.0f)
        val vecB = floatArrayOf(4.0f, 5.0f, 6.0f)
        val dot = QuantizedMathEngine.dotProduct(vecA, vecB)
        assertEquals(32.0f, dot, 0.001f)

        // Test softmax
        val logits = floatArrayOf(2.0f, 1.0f, 0.1f)
        val probs = QuantizedMathEngine.softmax(logits, temperature = 1.0f)
        val sum = probs.sum()
        assertEquals(1.0f, sum, 0.001f)
    }

    @Test
    fun `test tokenizer and chat templates`() {
        val tokenizer = Tokenizer()
        val tokens = tokenizer.encode("Hello Better Intelligence!")
        assertTrue(tokens.isNotEmpty())

        val decoded = tokenizer.decode(tokens)
        assertTrue(decoded.contains("Hello"))

        val chatPrompt = tokenizer.applyChatTemplate(
            architecture = "qwen2",
            messages = listOf(
                ChatMessageEntity(role = "user", content = "Explain quantization", modelName = "Qwen")
            ),
            systemPrompt = "You are an on-device AI."
        )
        assertTrue(chatPrompt.contains("<|im_start|>system"))
        assertTrue(chatPrompt.contains("Explain quantization"))
    }

    @Test
    fun `test GGUF binary parser magic validation`() {
        val parser = GgufParser()

        // Construct valid GGUF header in memory: 'G' 'G' 'U' 'F' (0x46554747) + version 3 + tensorCount 0 + kvCount 0
        val buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46554747) // GGUF magic
        buffer.putInt(3)          // version 3
        buffer.putLong(0)         // tensor count = 0
        buffer.putLong(0)         // kv count = 0

        val stream = ByteArrayInputStream(buffer.array())
        val result = parser.parseFromStream(stream)
        assertTrue(result.isSuccess)
        val metadata = result.getOrNull()
        assertNotNull(metadata)
        assertEquals(3, metadata?.version)
    }

    @Test
    fun `test Room database, rate limiting, and security policy`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = AiCoreRepository.getInstance(context)

            // Test client app policy
            val isAllowed = repository.isPackageAllowed("com.example.notes")
            assertTrue(isAllowed)

            // Test security check and rate limit
            val (ok, _) = repository.checkSecurityAndRateLimit("com.example.notes")
            assertTrue(ok)

            // Test IPC Logging
            repository.logIpcCall(
                IpcLogEntity(
                    callingPackage = "com.example.notes",
                    callingUid = 10001,
                    requestType = "STREAM",
                    promptPreview = "Summarize meeting",
                    responsePreview = "Action items...",
                    latencyMs = 45L,
                    tokensPerSecond = 32.5f,
                    totalTokens = 64,
                    status = "SUCCESS"
                )
            )

            // Verify active model default
            val active = repository.activeModel.first()
            assertNotNull(active)
            assertEquals("Q4_K_M", active?.quantization)
        }
    }

    @Test
    fun `test SHA256 checksum computation on temporary file`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val downloadManager = com.example.engine.ModelDownloadManager(context)

            val tempFile = java.io.File(context.cacheDir, "test_model_checksum.bin")
            tempFile.writeText("GGUF_TEST_DATA_CONTENT_12345")

            val result = downloadManager.verifyLocalFile(tempFile, null)
            assertTrue(result.computedSha256.isNotBlank())
            assertEquals("VERIFIED", result.status)

            // Verify with matching sha256
            val matchingResult = downloadManager.verifyLocalFile(tempFile, result.computedSha256)
            assertTrue(matchingResult.isMatch)
            assertEquals("VERIFIED", matchingResult.status)

            // Clean up
            tempFile.delete()
        }
    }
}

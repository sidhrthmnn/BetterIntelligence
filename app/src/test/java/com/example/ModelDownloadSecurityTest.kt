package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloadSecurityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun certificateFailureKeepsPartialAndClosesConnectionWithActionableError() = runBlocking {
        val failure = SSLHandshakeException("Handshake failed").apply {
            initCause(CertPathValidatorException("Trust anchor for certification path not found"))
        }
        val conn = Connection(URL("https://huggingface.co/test"), failure = failure)
        val manager = ModelDownloadManager(context) { conn }
        val model = ModelLibrary.read(context).first().copy(id = 919,
            filename = "tls-failure-test.gguf", downloadUrl = conn.url.toString())
        val part = File(manager.modelsDirectory, "${model.filename}.part")
        part.writeBytes(byteArrayOf(1, 2, 3))
        try {
            val errors = mutableListOf<String>()
            assertFalse(manager.downloadAndVerifyModel(model) {
                if (it is DownloadStateUpdate.Failure) errors += it.error
            })
            assertTrue(conn.closed)
            assertArrayEquals(byteArrayOf(1, 2, 3), part.readBytes())
            assertTrue(errors.single().contains("secure connection"))
            assertTrue(errors.single().contains("automatic date/time"))
            assertFalse(errors.single().contains("java.security"))
            assertFalse(File(manager.modelsDirectory, model.filename).exists())
        } finally { part.delete() }
    }

    @Test fun redirectsRetainResumeAndIdentityEncodingAndClosePreviousHop() {
        val calls = mutableListOf<Connection>()
        val manager = ModelDownloadManager(context) { url ->
            Connection(url, code = if (calls.isEmpty()) 302 else 206,
                location = "https://cas-bridge.xethub.hf.co/model").also { calls += it }
        }
        val final = manager.openHttps("https://huggingface.co/model", 123)
        assertEquals("cas-bridge.xethub.hf.co", final.url.host)
        assertTrue(calls.first().closed)
        assertFalse(calls.last().closed)
        assertTrue(calls.all { it.getRequestProperty("Range") == "bytes=123-" })
        assertTrue(calls.all { it.getRequestProperty("Accept-Encoding") == "identity" })
        final.disconnect()
    }

    @Test fun redirectDowngradeAndLoopFailClosed() {
        for (location in listOf("http://huggingface.co/insecure", "https://huggingface.co/loop")) {
            val calls = mutableListOf<Connection>()
            val manager = ModelDownloadManager(context) { url ->
                Connection(url, code = 302, location = location).also { calls += it }
            }
            assertTrue(runCatching { manager.openHttps("https://huggingface.co/model") }.isFailure)
            assertTrue(calls.all { it.closed })
            assertEquals(if (location.startsWith("http:")) 1 else 9, calls.size)
        }
    }

    @Test fun metadataUsesSameConnectionPolicyAndEnforcesByteLimit() {
        val connections = mutableListOf<Connection>()
        val manager = ModelDownloadManager(context) { url ->
            Connection(url, body = "x".repeat(33).toByteArray()).also { connections += it }
        }
        assertNull(manager.fetchHttpString("https://huggingface.co/metadata", maxBytes = 32))
        assertEquals("x".repeat(33), manager.fetchHttpString("https://huggingface.co/metadata", maxBytes = 33))
        assertTrue(connections.all { it.closed })
    }

    private class Connection(url: URL, private val code: Int = 200,
        private val location: String? = null, private val failure: Exception? = null,
        private val body: ByteArray = byteArrayOf()) : HttpURLConnection(url) {
        var closed = false
        override fun getResponseCode(): Int { failure?.let { throw it }; return code }
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
    }
}

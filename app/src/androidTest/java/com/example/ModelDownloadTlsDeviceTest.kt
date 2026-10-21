package com.example

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: -e biNetworkTests true. Uses public servers, at most 105 MB plus headers.
 * Run in the isolated validation package so existing user models are untouched.
 */
class ModelDownloadTlsDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("biNetworkTests") == "true")
        check(context.packageName.endsWith(".validation")) { "Use the isolated validation package" }
    }

    @Test fun publicRootWorksWithoutSystemRootsAndInvalidCertificatesRemainRejected() {
        optIn()
        val root = context.resources.openRawResource(R.raw.amazon_root_ca_1).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        assertEquals("8ecde6884f3d87b1125ba31ac3fcb13d7016de7f57cc904fe1cb97c6ae98196e",
            MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString("") { "%02x".format(it) })
        root.checkValidity(); root.verify(root.publicKey)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setCertificateEntry("official-amazon-root-ca-1", root)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        // No Android/OEM roots here: proves the bundled root can build the live chain.
        for (host in listOf("huggingface.co", "cas-bridge.xethub.hf.co", "us.aws.cdn.hf.co")) {
            val conn = URL("https://$host/").openConnection() as HttpsURLConnection
            try {
                conn.sslSocketFactory = tls.socketFactory
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                conn.instanceFollowRedirects = false
                assertTrue("No HTTPS response from $host", conn.responseCode >= 100)
                Log.i("ModelDownloadTLS", "Bundled-root-only chain verified: $host")
            } finally { conn.disconnect() }
        }
        for (host in listOf("self-signed.badssl.com", "wrong.host.badssl.com", "expired.badssl.com")) {
            val conn = URL("https://$host/").openConnection() as HttpsURLConnection
            try {
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                val failure = runCatching { conn.responseCode }.exceptionOrNull()
                assertTrue("Invalid certificate was accepted: $host ($failure)", failure is javax.net.ssl.SSLException)
            } finally { conn.disconnect() }
        }
    }

    @Test fun everyCatalogModelCanStartSecureRangedDownload() {
        optIn()
        val failures = mutableListOf<String>()
        for (model in ModelLibrary.read(context)) {
            val manager = ModelDownloadManager(context) { url ->
                (url.openConnection() as java.net.HttpURLConnection).apply {
                    setRequestProperty("Range", "bytes=0-4095")
                }
            }
            runCatching {
                val conn = manager.openHttps(requireNotNull(model.downloadUrl))
                try {
                    check(conn.responseCode == 206) { "HTTP ${conn.responseCode}" }
                    check(conn.getHeaderField("Content-Range").startsWith("bytes 0-4095/"))
                    val bytes = conn.inputStream.use { it.readNBytes(4096) }
                    check(bytes.size == 4096)
                    if (model.filename.endsWith(".gguf")) check(bytes.take(4).toByteArray().decodeToString() == "GGUF")
                    Log.i("ModelDownloadTLS", "Verified HTTPS range: ${model.name} via ${conn.url.host}")
                } finally { conn.disconnect() }
            }.onFailure { failures += "${model.name}: ${it.message}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun smallestModelDownloadsVerifiesAndInstalls() = runBlocking {
        optIn()
        val model = ModelLibrary.read(context).first().copy(filename = "tls-download-fixture.gguf", id = 9919)
        val manager = ModelDownloadManager(context)
        val target = File(manager.modelsDirectory, model.filename)
        val part = File(manager.modelsDirectory, "${model.filename}.part")
        target.delete(); part.delete()
        try {
            val errors = mutableListOf<String>()
            assertTrue(manager.downloadAndVerifyModel(model) {
                if (it is DownloadStateUpdate.Failure) errors += it.error
            }.also { assertTrue(errors.joinToString(), it) })
            assertTrue(target.length() > 100_000_000)
            assertTrue(manager.verifyLocalFile(target, model.sha256Checksum).isMatch)
            assertFalse(part.exists())
            Log.i("ModelDownloadTLS", "Full SmolLM2 135M download and pinned SHA-256 verified (${target.length()} bytes)")
        } finally { target.delete(); part.delete() }
        Unit
    }
}

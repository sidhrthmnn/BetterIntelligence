package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumableDownloadTest {
    private val weights = File("../models/smollm2-135m-instruct-q4_k_m.gguf")
    private fun hash(): String = MessageDigest.getInstance("SHA-256").let { digest ->
        weights.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun interruptedTransferResumesVerifiedBytesAndClosesBothConnections() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val calls = mutableListOf<Connection>()
        val manager = ModelDownloadManager(context) { url -> Connection(url, weights, fail = calls.isEmpty()).also { calls += it } }
        val base = ModelLibrary.read(context).first().copy(id = 912, filename = "resume-test.gguf", fileSizeMb = (weights.length() + 1048575) / 1048576,
            downloadUrl = "https://models.example.invalid/pinned", sha256Checksum = hash())
        val part = File(manager.modelsDirectory, "${base.filename}.part")
        val errors = mutableListOf<String>()
        part.delete(); File(manager.modelsDirectory, base.filename).delete()
        assertFalse(manager.downloadAndVerifyModel(base) { if (it is DownloadStateUpdate.Failure) errors += it.error })
        assertTrue(errors.joinToString(), part.length() > 0); val offset = part.length()
        assertTrue(errors.joinToString(), manager.downloadAndVerifyModel(base) { if (it is DownloadStateUpdate.Failure) errors += it.error })
        assertEquals("bytes=$offset-", calls.last().getRequestProperty("Range"))
        assertTrue(calls.all { it.closed }); assertFalse(part.exists())
        val installed = File(manager.modelsDirectory, base.filename)
        assertEquals(weights.length(), installed.length())
        assertTrue(manager.verifyLocalFile(installed, base.sha256Checksum).isMatch)
        installed.delete()
        Unit
    }
    @Test fun serverIgnoringRangeRestartsSafelyAndBadRangeIsNotAppended() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val base = ModelLibrary.read(context).first().copy(id = 913, filename = "range-test.gguf", fileSizeMb = (weights.length() + 1048575) / 1048576,
            downloadUrl = "https://models.example.invalid/pinned", sha256Checksum = hash())
        val manager = ModelDownloadManager(context) { Connection(it, weights, ignoreRange = true) }
        val part = File(manager.modelsDirectory, "${base.filename}.part")
        val errors = mutableListOf<String>()
        part.writeBytes(byteArrayOf(1, 2, 3, 4))
        val result = manager.downloadAndVerifyModel(base) { if (it is DownloadStateUpdate.Failure) errors += it.error }
        assertTrue(errors.joinToString(), result)
        val installed = File(manager.modelsDirectory, base.filename)
        assertTrue(manager.verifyLocalFile(installed, base.sha256Checksum).isMatch); installed.delete()
        val invalid = ModelDownloadManager(context) { Connection(it, weights, badRange = true) }
        part.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertFalse(invalid.downloadAndVerifyModel(base) {})
        assertFalse(part.exists()); assertFalse(installed.exists())
    }
    private class Connection(url: URL, private val file: File, private val fail: Boolean = false,
        private val ignoreRange: Boolean = false, private val badRange: Boolean = false) : HttpURLConnection(url) {
        var closed = false
        private val offset get() = if (ignoreRange) 0L else getRequestProperty("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull() ?: 0L
        override fun getResponseCode() = if (offset > 0) 206 else 200
        override fun getContentLengthLong() = file.length() - offset
        override fun getHeaderField(name: String?) = if (name == "Content-Range") "bytes ${if (badRange) offset + 1 else offset}-${file.length() - 1}/${file.length()}" else null
        override fun getInputStream(): java.io.InputStream {
            val stream = FileInputStream(file).apply { channel.position(offset) }
            return object : FilterInputStream(stream) {
                var read = 0L
                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    if (fail && read >= 1024 * 1024) throw IOException("Synthetic connection interruption")
                    return super.read(buffer, off, len).also { if (it > 0) read += it }
                }
            }
        }
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
    }
}

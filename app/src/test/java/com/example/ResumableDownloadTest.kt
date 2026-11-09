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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumableDownloadTest {
    // A small, well-formed GGUF container exercises transport/format handling.
    // Actual chat-model load and generation are separate real-device tests.
    private val fixture = lazy {
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        fun string(value: String) { val bytes = value.toByteArray(); buffer.putLong(bytes.size.toLong()); buffer.put(bytes) }
        buffer.putInt(0x46554747); buffer.putInt(3); buffer.putLong(1); buffer.putLong(2)
        string("general.architecture"); buffer.putInt(8); string("llama")
        string("general.name"); buffer.putInt(8); string("Synthetic transport fixture")
        string("test.weight"); buffer.putInt(1); buffer.putLong(16); buffer.putInt(0); buffer.putLong(0)
        File.createTempFile("bi-transfer-", ".gguf").apply { writeBytes(buffer.array()) }
    }
    private val weights get() = fixture.value
    @After fun removeFixture() { if (fixture.isInitialized()) weights.delete() }
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
    @Test fun badReplacementKeepsTheExistingInstalledCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = ModelDownloadManager(context) { Connection(it, weights) }
        val model = ModelLibrary.read(context).first().copy(id = 914, filename = "replacement-test.gguf", fileSizeMb = (weights.length() + 1048575) / 1048576,
            downloadUrl = "https://models.example.invalid/pinned", sha256Checksum = "0".repeat(64))
        val existing = File(manager.modelsDirectory, model.filename)
        val original = byteArrayOf(42, 8, 9); existing.writeBytes(original)
        assertFalse(manager.downloadAndVerifyModel(model) {})
        assertArrayEquals(original, existing.readBytes())
        assertFalse(File(manager.modelsDirectory, "${model.filename}.part").exists())
        existing.delete(); Unit
    }
    @Test fun lowStorageRetainsPartialAndResumesAfterSpaceReturns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var enough = false
        val manager = ModelDownloadManager(context, availableSpace = { if (enough) 2_000_000_000L else 1024L }) { Connection(it, weights) }
        val model = ModelLibrary.read(context).first().copy(id = 915, filename = "storage-test.gguf", fileSizeMb = (weights.length() + 1048575) / 1048576,
            downloadUrl = "https://models.example.invalid/pinned", sha256Checksum = hash())
        val part = File(manager.modelsDirectory, "${model.filename}.part")
        weights.inputStream().use { input -> part.outputStream().use { it.write(input.readNBytes(4096)) } }
        val errors = mutableListOf<String>()
        assertFalse(manager.downloadAndVerifyModel(model) { if (it is DownloadStateUpdate.Failure) errors += it.error })
        assertTrue(errors.joinToString(), errors.any { it.contains("storage", true) }); assertEquals(4096L, part.length())
        enough = true
        assertTrue(manager.downloadAndVerifyModel(model) {})
        assertTrue(manager.verifyLocalFile(File(manager.modelsDirectory, model.filename), model.sha256Checksum).isMatch)
        File(manager.modelsDirectory, model.filename).delete(); Unit
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

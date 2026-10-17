package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.engine.VerifiedModelFileCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerifiedModelFileCacheTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val checksum = "a".repeat(64)
    private fun file() = File.createTempFile("verified-model", ".gguf", context.cacheDir).apply { writeText("original") }

    @Test fun unchangedVerifiedPrivateFileDoesNotGetHashedOnEveryProcessStart() = runBlocking {
        val file = file()
        try {
            var checks = 0
            assertTrue(VerifiedModelFileCache(context).verify(file, checksum) { checks++; true })
            assertTrue(VerifiedModelFileCache(context).verify(file, checksum) { checks++; true })
            assertEquals(1, checks)
        } finally { file.delete() }
    }

    @Test fun replacementOrChecksumChangeRequiresRealVerificationAgain() = runBlocking {
        val file = file()
        val cache = VerifiedModelFileCache(context)
        try {
            assertTrue(cache.verify(file, checksum) { true })
            file.writeText("modified") // Same size; modification time must still invalidate.
            assertTrue(file.setLastModified(System.currentTimeMillis() + 2_000))
            assertFalse(cache.verify(file, checksum) { false })
            assertTrue(cache.verify(file, checksum) { true })
            assertFalse(cache.verify(file, "b".repeat(64)) { false })
            file.delete()
            assertFalse(cache.verify(file, checksum) { error("Must not hash a missing file") })
        } finally { file.delete() }
    }

    @Test fun failedChangedDuringHashExpiredAndExplicitlyInvalidatedChecksAreNotTrusted() = runBlocking {
        val file = file()
        var clock = System.currentTimeMillis()
        val cache = VerifiedModelFileCache(context) { clock }
        try {
            assertFalse(cache.verify(file, checksum) { file.appendText("changed while hashing"); true })
            assertTrue(cache.verify(file, checksum) { true })
            clock += VerifiedModelFileCache.MAX_AGE_MS + 1
            assertFalse(cache.verify(file, checksum) { false })
            assertTrue(cache.verify(file, checksum) { true })
            cache.invalidate(file)
            assertFalse(cache.verify(file, checksum) { false })
        } finally { file.delete() }
    }
}

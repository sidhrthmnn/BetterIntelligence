package com.example

import com.example.engine.HuggingFaceCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HuggingFaceCatalogTest {
    private val revision = "a".repeat(40)
    private val checksum = "b".repeat(64)
    private fun metadata(siblings: String) = JSONObject("""{"sha":"$revision","siblings":$siblings}""")

    @Test fun publicRepositoryUrlsAndIdsAreAccepted() {
        assertEquals("Qwen/Qwen2.5", HuggingFaceCatalog.normalizeRepo(" https://huggingface.co/Qwen/Qwen2.5/ "))
        for (invalid in listOf("../model", "https://example.com/owner/model", "owner/model/tree/main", "a/b?x=1")) {
            assertTrue(runCatching { HuggingFaceCatalog.normalizeRepo(invalid) }.isFailure)
        }
    }

    @Test fun downloadUsesImmutableRevisionExactSizeAndPublisherChecksum() {
        val file = HuggingFaceCatalog.files("owner/model", metadata("""[
            {"rfilename":"quants/a model-Q4_K_M.gguf","size":1048577,"lfs":{"sha256":"$checksum"}}
        ]""")).single()
        assertEquals("https://huggingface.co/owner/model/resolve/$revision/quants/a%20model-Q4_K_M.gguf", file.downloadUrl)
        assertEquals(1048577, file.sizeBytes)
        assertEquals(2, file.sizeMb)
        assertEquals(checksum, file.sha256Oid)
        assertEquals("Q4_K_M", file.quantization)
        assertEquals(0f, file.estimatedSpeedTokSec)
        assertTrue(file.recommendedRamMb > file.sizeMb)
    }

    @Test fun shardsProjectorsUnknownSizesAndUnsafePathsAreExcluded() {
        val files = HuggingFaceCatalog.files("owner/model", metadata("""[
            {"rfilename":"model-Q4_K_M-00001-of-00002.gguf","size":100},
            {"rfilename":"mmproj-F16.gguf","size":100},
            {"rfilename":"model.safetensors","size":100},
            {"rfilename":"../escape.gguf","size":100},
            {"rfilename":"unknown.gguf"},
            {"rfilename":"model-IQ4_XS.gguf","lfs":{"size":100,"oid":"$checksum"}}
        ]"""))
        assertEquals(1, files.size)
        assertEquals("IQ4_XS", files.single().quantization)
        assertEquals(checksum, files.single().sha256Oid)
    }

    @Test fun missingChecksumIsNotInventedAndRevisionIsRequired() {
        val file = HuggingFaceCatalog.files("owner/model", metadata("""[{"rfilename":"model-F16.gguf","size":100}]""")).single()
        assertNull(file.sha256Oid)
        assertEquals("F16", file.quantization)
        assertTrue(runCatching { HuggingFaceCatalog.files("owner/model", JSONObject("""{"sha":"main","siblings":[]}""")) }.isFailure)
    }

    @Test fun localNamesAvoidCollisionsAcrossPublishersAndSubfolders() {
        val file = HuggingFaceCatalog.files("one/model", metadata("""[{"rfilename":"nested/model-Q4_K_M.gguf","size":100}]""")).single()
        val name = HuggingFaceCatalog.localFilename("one/model", file)
        assertTrue(name.matches(Regex("hf-[a-f0-9]{12}-[A-Za-z0-9._-]+\\.gguf")))
        assertNotEquals(name, HuggingFaceCatalog.localFilename("two/model", file))
        assertNotEquals(name, HuggingFaceCatalog.localFilename("one/model", file.copy(filename = "other/model-Q4_K_M.gguf")))
        assertEquals(name, HuggingFaceCatalog.localFilename("one/model", file))
    }
}

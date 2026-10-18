package com.example.engine

import android.content.Context
import android.util.Log
import com.example.data.ModelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class HuggingFaceModelSearchResult(
    val repoId: String,
    val author: String,
    val modelName: String,
    val downloads: Int,
    val likes: Int,
    val lastModified: String,
    val pipelineTag: String,
    val ggufFileCount: Int
)

data class HuggingFaceGgufFile(
    val filename: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sizeMb: Long,
    val sha256Oid: String?,
    val quantization: String,
    val recommendedRamMb: Int,
    val estimatedSpeedTokSec: Float
)

data class ChecksumVerificationResult(
    val isMatch: Boolean,
    val computedSha256: String,
    val expectedSha256: String?,
    val status: String,
    val message: String
)

sealed class DownloadStateUpdate {
    data class Progress(val progress: Float, val speedMbps: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadStateUpdate()
    object VerifyingChecksum : DownloadStateUpdate()
    data class Success(val file: File, val sha256: String, val isVerified: Boolean) : DownloadStateUpdate()
    data class Failure(val error: String) : DownloadStateUpdate()
}

class ModelDownloadManager(private val context: Context,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {

    private val activeDownloadFlags = ConcurrentHashMap<Long, Boolean>()
    private val TAG = "ModelDownloadManager"

    val modelsDirectory: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Search official Hugging Face Hub for GGUF model repositories.
     */
    suspend fun searchHuggingFaceHub(query: String): List<HuggingFaceModelSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<HuggingFaceModelSearchResult>()
        try {
            val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val endpoint = "https://huggingface.co/api/models?search=$encodedQuery&filter=gguf&limit=20&full=true"
            val jsonText = fetchHttpString(endpoint) ?: return@withContext emptyList()
            val jsonArray = JSONArray(jsonText)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue
                val repoId = item.optString("id", "")
                if (repoId.isBlank()) continue

                val author = if (repoId.contains("/")) repoId.substringBefore("/") else "community"
                val modelName = if (repoId.contains("/")) repoId.substringAfter("/") else repoId
                val downloads = item.optInt("downloads", 0)
                val likes = item.optInt("likes", 0)
                val lastModified = item.optString("lastModified", "Recently")
                val pipelineTag = item.optString("pipeline_tag", "text-generation")

                // Count GGUF siblings if available
                var ggufCount = 0
                val siblings = item.optJSONArray("siblings")
                if (siblings != null) {
                    for (j in 0 until siblings.length()) {
                        val sib = siblings.optJSONObject(j)
                        val rfilename = sib?.optString("rfilename", "") ?: ""
                        if (rfilename.endsWith(".gguf", ignoreCase = true)) {
                            ggufCount++
                        }
                    }
                }

                results.add(
                    HuggingFaceModelSearchResult(
                        repoId = repoId,
                        author = author,
                        modelName = modelName,
                        downloads = downloads,
                        likes = likes,
                        lastModified = lastModified.take(10),
                        pipelineTag = pipelineTag,
                        ggufFileCount = if (ggufCount > 0) ggufCount else 1
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search Hugging Face Hub: ${e.message}", e)
        }
        results
    }

    /**
     * Query Hugging Face API to list all available GGUF files in a repository with their sizes & LFS SHA256 checksums.
     */
    suspend fun fetchRepoGgufFiles(repoId: String): List<HuggingFaceGgufFile> = withContext(Dispatchers.IO) {
        val repo = HuggingFaceCatalog.normalizeRepo(repoId)
        val metadata = fetchHttpString("https://huggingface.co/api/models/$repo?blobs=true")
            ?: error("Repository unavailable. Use a public repository, or import a downloaded GGUF file.")
        HuggingFaceCatalog.files(repo, JSONObject(metadata))
    }

    /**
     * Fetch the official Hugging Face Git LFS SHA256 checksum for a specific file.
     */
    suspend fun fetchOfficialSha256(repoId: String, filename: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanRepo = repoId.trim().removePrefix("https://huggingface.co/").removeSuffix("/")
            // 1. Check raw file pointer (Hugging Face raw pointer contains: 'oid sha256:<hash>')
            val rawPointerUrl = "https://huggingface.co/$cleanRepo/raw/main/$filename"
            val rawContent = fetchHttpString(rawPointerUrl, maxBytes = 4096)
            if (!rawContent.isNullOrBlank() && rawContent.contains("oid sha256:")) {
                val line = rawContent.lines().firstOrNull { it.startsWith("oid sha256:") }
                if (line != null) {
                    val hash = line.substringAfter("oid sha256:").trim()
                    if (hash.length == 64) return@withContext hash
                }
            }

            // 2. Query tree API
            val files = fetchRepoGgufFiles(cleanRepo)
            val match = files.firstOrNull { it.filename.equals(filename, ignoreCase = true) }
            if (!match?.sha256Oid.isNullOrBlank()) {
                return@withContext match?.sha256Oid
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch official sha256 for $repoId / $filename: ${e.message}")
        }
        null
    }

    /**
     * Streams the model download from Hugging Face, computes the live SHA-256 hash in real-time,
     * verifies against the official checksum, and atomically installs into the models directory.
     */
    suspend fun downloadAndVerifyModel(
        model: ModelEntity,
        onUpdate: suspend (DownloadStateUpdate) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        require(model.filename == File(model.filename).name && !model.filename.contains('\\')) { "Invalid model filename" }
        val targetFile = File(modelsDirectory, model.filename)
        val tempFile = File(modelsDirectory, "${model.filename}.part")
        require(targetFile.canonicalFile.parentFile == modelsDirectory.canonicalFile && tempFile.canonicalFile.parentFile == modelsDirectory.canonicalFile) { "Model path must stay in private model storage" }
        activeDownloadFlags[model.id] = true
        var connection: HttpURLConnection? = null

        try {
            var resumeOffset = if (!model.sha256Checksum.isNullOrBlank()) tempFile.length() else 0L
            check(modelsDirectory.usableSpace > ((model.fileSizeMb + 128) * 1024L * 1024L - resumeOffset).coerceAtLeast(128L * 1024 * 1024)) { "Insufficient storage for the remaining model download" }
            val downloadUrl = model.downloadUrl ?: throw IllegalArgumentException("No download URL provided for model ${model.name}")

            // Follow HTTP redirects (Hugging Face resolve redirects to cloud CDN)
            var currentUrl = downloadUrl
            var redirects = 0
            val maxRedirects = 8

            while (redirects < maxRedirects) {
                val url = URL(currentUrl)
                require(url.protocol == "https") { "Model downloads require HTTPS" }
                val conn = openConnection(url).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "BetterIntelligence-AiCore/1.0 (Android ARM64)")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (resumeOffset > 0) setRequestProperty("Range", "bytes=$resumeOffset-")
                }
                connection = conn
                val code = conn.responseCode
                if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!location.isNullOrBlank()) {
                        currentUrl = URL(url, location).toString()
                        redirects++
                        continue
                    }
                }
                connection = conn
                break
            }

            val activeConn = connection ?: throw IllegalStateException("Failed to open connection to download source")
            if (activeConn.responseCode == 416) {
                // A complete or stale partial must not trap the user in an endless failed resume.
                val complete = verifyLocalFile(tempFile, model.sha256Checksum)
                if (complete.isMatch) {
                    if (model.filename.endsWith(".gguf", true)) GgufParser().parseFromFile(tempFile).getOrThrow()
                    else com.google.ai.edge.litertlm.ModelInfo.from(tempFile.absolutePath).use { require(it is com.google.ai.edge.litertlm.ModelInfo.Llm) }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    check(tempFile.renameTo(targetFile)) { "Could not install completed download" }
                    onUpdate(DownloadStateUpdate.Success(targetFile, complete.computedSha256, true))
                    return@withContext true
                }
                tempFile.delete()
                error("The partial download is stale. Retry to start a fresh verified download.")
            }
            if (activeConn.responseCode !in 200..299) {
                throw IllegalStateException(if (activeConn.responseCode in listOf(401, 403)) "The publisher requires download authorisation for this model. Choose a publicly accessible package." else "Download provider returned HTTP ${activeConn.responseCode}. Retry later.")
            }

            if (activeConn.responseCode == 206) {
                val start = Regex("bytes (\\d+)-\\d+/\\d+").matchEntire(activeConn.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                if (start != resumeOffset) { tempFile.delete(); error("The server returned an invalid resume range. Retry to start a fresh download.") }
            } else resumeOffset = 0 // Server ignored Range; restart instead of appending a full file.

            val totalBytes = activeConn.contentLengthLong.let {
                if (it > 0) it + resumeOffset else (model.fileSizeMb * 1024L * 1024L)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            if (resumeOffset > 0) FileInputStream(tempFile).use { existing ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = existing.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            var downloadedBytes = resumeOffset
            var lastLogTime = System.currentTimeMillis()
            var lastBytes = resumeOffset

            activeConn.inputStream.use { input ->
                FileOutputStream(tempFile, resumeOffset > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (activeDownloadFlags[model.id] != true) {
                            throw kotlinx.coroutines.CancellationException("Download paused")
                        }

                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        check(downloadedBytes <= (model.fileSizeMb + 1) * 1024L * 1024L) { "Download exceeds its declared model size" }

                        val now = System.currentTimeMillis()
                        if (now - lastLogTime >= 400) {
                            val elapsedSec = (now - lastLogTime) / 1000f
                            val speedMbps = if (elapsedSec > 0) (((downloadedBytes - lastBytes) * 8f) / (1024 * 1024)) / elapsedSec else 0f
                            val progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0.01f, 0.99f) else 0.5f

                            onUpdate(DownloadStateUpdate.Progress(progress, speedMbps, downloadedBytes, totalBytes))
                            lastLogTime = now
                            lastBytes = downloadedBytes
                        }
                    }
                }
            }

            if (activeConn.contentLengthLong > 0 && downloadedBytes != totalBytes) throw java.io.IOException("Download interrupted. Retry to resume the remaining data.")
            // Checksum verification phase
            onUpdate(DownloadStateUpdate.VerifyingChecksum)

            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }

            // Determine expected checksum: from entity, or fetch from Hugging Face if repo provided
            var expectedChecksum = model.sha256Checksum
            if (expectedChecksum.isNullOrBlank() && !model.isCustom && !model.huggingFaceRepo.isNullOrBlank()) {
                expectedChecksum = fetchOfficialSha256(model.huggingFaceRepo, model.filename)
            }

            var isVerified = false
            if (!expectedChecksum.isNullOrBlank()) {
                if (computedSha256.equals(expectedChecksum.trim(), ignoreCase = true)) {
                    isVerified = true
                    Log.i(TAG, "SHA-256 verification SUCCEEDED for ${model.filename}: $computedSha256")
                } else {
                    Log.w(TAG, "SHA-256 MISMATCH! Computed: $computedSha256, Expected: $expectedChecksum")
                    // If checksum mismatch, we still report failure to protect system integrity
                    tempFile.delete()
                    onUpdate(DownloadStateUpdate.Failure("Checksum mismatch: expected $expectedChecksum but got $computedSha256"))
                    return@withContext false
                }
            } else {
                // If no remote checksum was recorded, file is stored with its computed SHA-256
                isVerified = false
                Log.i(TAG, "No remote checksum for ${model.filename}. Generated hash: $computedSha256")
            }

            if (downloadedBytes == 0L) error("Empty model download")
            if (model.filename.endsWith(".gguf", true)) GgufParser().parseFromFile(tempFile).getOrThrow()
            if (model.filename.endsWith(".litertlm", true)) com.google.ai.edge.litertlm.ModelInfo.from(tempFile.absolutePath).use {
                require(it is com.google.ai.edge.litertlm.ModelInfo.Llm) { "Downloaded LiteRT file is not a text-generation model" }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // Atomically move temp file to destination
            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                check(tempFile.renameTo(targetFile)) { "Could not install downloaded model" }
            }

            onUpdate(DownloadStateUpdate.Success(targetFile, computedSha256, isVerified))
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${model.name}: ${e.message}", e)
            if (e is kotlinx.coroutines.CancellationException) throw e
            onUpdate(DownloadStateUpdate.Failure(e.message ?: "Unknown download error"))
            return@withContext false
        } finally {
            connection?.disconnect()
            activeDownloadFlags.remove(model.id)
        }
    }

    /**
     * Cancel an active download by model ID.
     */
    fun cancelDownload(modelId: Long) {
        activeDownloadFlags[modelId] = false
    }

    /**
     * Verify an existing on-disk model file's SHA-256 against an expected hash or Hugging Face repository.
     */
    suspend fun verifyLocalFile(file: File, expectedSha256: String?): ChecksumVerificationResult = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext ChecksumVerificationResult(
                isMatch = false,
                computedSha256 = "",
                expectedSha256 = expectedSha256,
                status = "FAILED",
                message = "File does not exist on disk"
            )
        }

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val computedHash = digest.digest().joinToString("") { "%02x".format(it) }

            if (expectedSha256.isNullOrBlank()) {
                ChecksumVerificationResult(
                    isMatch = false,
                    computedSha256 = computedHash,
                    expectedSha256 = null,
                    status = "UNAVAILABLE",
                    message = "SHA-256 generated: $computedHash"
                )
            } else if (computedHash.equals(expectedSha256.trim(), ignoreCase = true)) {
                ChecksumVerificationResult(
                    isMatch = true,
                    computedSha256 = computedHash,
                    expectedSha256 = expectedSha256,
                    status = "VERIFIED",
                    message = "SHA-256 matches Hugging Face Git LFS checksum ($computedHash)"
                )
            } else {
                ChecksumVerificationResult(
                    isMatch = false,
                    computedSha256 = computedHash,
                    expectedSha256 = expectedSha256,
                    status = "MISMATCH",
                    message = "Hash mismatch: expected $expectedSha256, found $computedHash"
                )
            }
        } catch (e: Exception) {
            ChecksumVerificationResult(
                isMatch = false,
                computedSha256 = "",
                expectedSha256 = expectedSha256,
                status = "ERROR",
                message = "Verification error: ${e.message}"
            )
        }
    }

    private fun fetchHttpString(urlString: String, maxBytes: Int = 1024 * 1024): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "BetterIntelligence-AiCore/1.0 (Android ARM64)")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { reader ->
                    reader.readText()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP fetch failed for $urlString: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

}

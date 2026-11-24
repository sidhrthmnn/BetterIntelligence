package com.example.engine

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.example.data.ModelEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

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
    data class Failure(val error: String, val retryable: Boolean = false, val bytesSaved: Long = 0) : DownloadStateUpdate()
}

class ModelDownloadManager(private val context: Context,
    private val availableSpace: (File) -> Long = { it.usableSpace },
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
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to search Hugging Face Hub: ${e.message}", e)
        }
        results
    }

    /**
     * Query Hugging Face API to list all available GGUF files in a repository with their sizes & LFS SHA256 checksums.
     */
    suspend fun fetchRepoGgufFiles(repoId: String, network: Network? = null): List<HuggingFaceGgufFile> = withContext(Dispatchers.IO) {
        val repo = HuggingFaceCatalog.normalizeRepo(repoId)
        val metadata = fetchHttpString("https://huggingface.co/api/models/$repo?blobs=true", network = network)
            ?: error("Repository unavailable. Use a public repository, or import a downloaded GGUF file.")
        HuggingFaceCatalog.files(repo, JSONObject(metadata))
    }

    /**
     * Fetch the official Hugging Face Git LFS SHA256 checksum for a specific file.
     */
    suspend fun fetchOfficialSha256(repoId: String, filename: String, network: Network? = null): String? = withContext(Dispatchers.IO) {
        try {
            val cleanRepo = repoId.trim().removePrefix("https://huggingface.co/").removeSuffix("/")
            // 1. Check raw file pointer (Hugging Face raw pointer contains: 'oid sha256:<hash>')
            val rawPointerUrl = "https://huggingface.co/$cleanRepo/raw/main/$filename"
            val rawContent = fetchHttpString(rawPointerUrl, maxBytes = 4096, network = network)
            if (!rawContent.isNullOrBlank() && rawContent.contains("oid sha256:")) {
                val line = rawContent.lines().firstOrNull { it.startsWith("oid sha256:") }
                if (line != null) {
                    val hash = line.substringAfter("oid sha256:").trim()
                    if (hash.length == 64) return@withContext hash
                }
            }

            // 2. Query tree API
            val files = fetchRepoGgufFiles(cleanRepo, network)
            val match = files.firstOrNull { it.filename.equals(filename, ignoreCase = true) }
            if (!match?.sha256Oid.isNullOrBlank()) {
                return@withContext match?.sha256Oid
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
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
        network: Network? = null,
        canContinue: () -> Boolean = { true },
        onUpdate: suspend (DownloadStateUpdate) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        require(model.filename == File(model.filename).name && !model.filename.contains('\\')) { "Invalid model filename" }
        val targetFile = File(modelsDirectory, model.filename)
        val tempFile = File(modelsDirectory, "${model.filename}.part")
        require(targetFile.canonicalFile.parentFile == modelsDirectory.canonicalFile && tempFile.canonicalFile.parentFile == modelsDirectory.canonicalFile) { "Model path must stay in private model storage" }
        activeDownloadFlags[model.id] = true
        var connection: HttpURLConnection? = null

        val initialBytes = tempFile.length()
        try {
            var resumeOffset = if (!model.sha256Checksum.isNullOrBlank()) tempFile.length() else 0L
            val maxBytes = (model.fileSizeMb + 1) * 1048576L
            if (resumeOffset > maxBytes) {
                check(tempFile.delete()) { "Could not remove the oversized partial download" }
                resumeOffset = 0
            }
            check(availableSpace(modelsDirectory) > ((model.fileSizeMb + 128) * 1024L * 1024L - resumeOffset).coerceAtLeast(128L * 1024 * 1024)) { "Insufficient storage for the remaining model download" }
            val downloadUrl = model.downloadUrl ?: throw IllegalArgumentException("No download URL provided for model ${model.name}")

            if (!canContinue()) throw CancellationException("Download network policy changed")
            val activeConn = openHttps(downloadUrl, resumeOffset, network)
            connection = activeConn
            if (activeConn.responseCode == 416 && resumeOffset > 0) {
                // A complete or stale partial must not trap the user in an endless failed resume.
                val complete = verifyLocalFile(tempFile, model.sha256Checksum)
                if (complete.isMatch) {
                    if (model.filename.endsWith(".gguf", true)) GgufParser().parseFromFile(tempFile).getOrThrow()
                    else com.google.ai.edge.litertlm.ModelInfo.from(tempFile.absolutePath).use { require(it is com.google.ai.edge.litertlm.ModelInfo.Llm) }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        check(tempFile.renameTo(targetFile)) { "Could not install completed download" }
                        onUpdate(DownloadStateUpdate.Success(targetFile, complete.computedSha256, true))
                    }
                    return@withContext true
                }
                tempFile.delete()
                throw IOException("The partial download is stale. A fresh download is needed.")
            }
            val status = activeConn.responseCode
            if (status != 200 && status != 206) {
                when (status) {
                    401, 403 -> error("The publisher denied access to this model. Try again or choose a public package.")
                    404, 410 -> error("The publisher no longer offers this model file. Choose another package.")
                    408, 425, 429, in 500..599 -> throw IOException("The model server is temporarily unavailable (HTTP $status).")
                    else -> error("The model server returned HTTP $status.")
                }
            }

            val contentLength = activeConn.getHeaderField("Content-Length")?.toLongOrNull()
                ?: activeConn.contentLengthLong.takeIf { it > 0 }
            var responseEnd = contentLength
            val totalBytes = if (status == 206) {
                val range = CONTENT_RANGE.matchEntire(activeConn.getHeaderField("Content-Range").orEmpty())
                val start = range?.groupValues?.get(1)?.toLongOrNull()
                val end = range?.groupValues?.get(2)?.toLongOrNull()
                val total = range?.groupValues?.get(3)?.toLongOrNull()
                if (start != resumeOffset || end == null || total == null || end < resumeOffset || end >= total ||
                    contentLength != null && contentLength != end - resumeOffset + 1) {
                    tempFile.delete()
                    throw IOException("The model server returned an invalid resume range.")
                }
                responseEnd = end + 1
                total
            } else {
                resumeOffset = 0 // Server ignored Range. Start from byte zero.
                contentLength
            }
            check(totalBytes == null || totalBytes in 1..maxBytes) { "Download exceeds its declared model size" }
            val remaining = (totalBytes ?: model.fileSizeMb * 1048576L) - resumeOffset
            check(availableSpace(modelsDirectory) > remaining + 128L * 1048576) { "Insufficient storage for the remaining model download" }

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
            var lastLogTime = SystemClock.elapsedRealtime()
            var lastBytes = resumeOffset

            activeConn.inputStream.use { input ->
                FileOutputStream(tempFile, resumeOffset > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        currentCoroutineContext().ensureActive()
                        if (activeDownloadFlags[model.id] != true) {
                            throw kotlinx.coroutines.CancellationException("Download paused")
                        }

                        if (downloadedBytes / (64 * 1048576) != (downloadedBytes + bytesRead) / (64 * 1048576))
                            check(availableSpace(modelsDirectory) > 64L * 1048576) { "Insufficient storage. Free space and resume the download." }
                        val next = downloadedBytes + bytesRead
                        if (next > (responseEnd ?: maxBytes)) {
                            // Keep only the already written prefix; don't append
                            // bytes beyond the declared representation.
                            error("Download exceeds its declared model size")
                        }
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        downloadedBytes = next

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLogTime >= 400) {
                            if (!canContinue()) throw CancellationException("Download network policy changed")
                            val elapsedSec = (now - lastLogTime) / 1000f
                            val speedMbps = if (elapsedSec > 0) (((downloadedBytes - lastBytes) * 8f) / (1024 * 1024)) / elapsedSec else 0f
                            val estimate = totalBytes ?: (model.fileSizeMb * 1048576L).coerceAtLeast(1L)
                            val progress = (downloadedBytes.toFloat() / estimate).coerceIn(0f, 0.99f)
                            onUpdate(DownloadStateUpdate.Progress(progress, speedMbps, downloadedBytes, estimate))
                            lastLogTime = now
                            lastBytes = downloadedBytes
                        }
                    }
                }
            }

            if (totalBytes != null && downloadedBytes != totalBytes) throw IOException("Download interrupted before the complete file arrived.")
            connection = null
            activeConn.disconnect()
            if (!canContinue()) throw CancellationException("Download network policy changed")
            // Checksum verification phase
            onUpdate(DownloadStateUpdate.VerifyingChecksum)

            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }

            // Determine expected checksum: from entity, or fetch from Hugging Face if repo provided
            var expectedChecksum = model.sha256Checksum
            if (expectedChecksum.isNullOrBlank() && !model.isCustom && !model.huggingFaceRepo.isNullOrBlank()) {
                expectedChecksum = fetchOfficialSha256(model.huggingFaceRepo, model.filename, network)
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

            try {
                check(downloadedBytes > 0) { "Empty model download" }
                if (model.filename.endsWith(".gguf", true)) GgufParser().parseFromFile(tempFile).getOrThrow()
                if (model.filename.endsWith(".litertlm", true)) com.google.ai.edge.litertlm.ModelInfo.from(tempFile.absolutePath).use {
                    require(it is com.google.ai.edge.litertlm.ModelInfo.Llm) { "Downloaded LiteRT file is not a text-generation model" }
                }
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
            currentCoroutineContext().ensureActive()
            // Keep the atomic file move and the installed-state callback
            // together when the OS stops a worker or the user taps Pause.
            withContext(NonCancellable) {
                check(tempFile.renameTo(targetFile)) { "Could not install downloaded model" }
                onUpdate(DownloadStateUpdate.Success(targetFile, computedSha256, isVerified))
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Model download failed: ${e.javaClass.simpleName}")
            val secureConnectionFailure = generateSequence<Throwable>(e) { it.cause }
                .take(12).any { it is SSLException || it is CertificateException }
            onUpdate(DownloadStateUpdate.Failure(downloadErrorMessage(e),
                retryable = e is IOException && !secureConnectionFailure && e !is java.io.FileNotFoundException,
                bytesSaved = (tempFile.length() - initialBytes).coerceAtLeast(0)))
            false
        } finally {
            connection?.disconnect()
            activeDownloadFlags.remove(model.id)
        }
    }

    /**
     * Cancel an active download by model ID.
     */
    fun cancelDownload(modelId: Long) {
        activeDownloadFlags.computeIfPresent(modelId) { _, _ -> false }
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

    /** Shared HTTPS/redirect policy for weights, Hub metadata and checksum pointers.
     * Platform TLS uses network_security_config; never install a permissive trust manager.
     * Close even a connection that fails while establishing TLS (before returning it).
     */
    internal fun openHttps(urlString: String, resumeOffset: Long = 0, network: Network? = null): HttpURLConnection {
        var url = URL(urlString)
        repeat(9) { hop ->
            require(url.protocol == "https") { "Model downloads require HTTPS" }
            val conn = if (network == null) openConnection(url) else network.openConnection(url) as HttpURLConnection
            try {
                conn.apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "BetterIntelligence/2.1 (Android)")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (resumeOffset > 0) setRequestProperty("Range", "bytes=$resumeOffset-")
                }
                if (conn.responseCode !in setOf(301, 302, 303, 307, 308)) return conn
                val location = conn.getHeaderField("Location")
                check(!location.isNullOrBlank()) { "Download provider returned a redirect without a destination" }
                check(hop < 8) { "Download provider redirected too many times. Retry later." }
                url = URL(url, location)
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
            conn.disconnect()
        }
        error("Download provider redirected too many times")
    }

    internal fun fetchHttpString(urlString: String, maxBytes: Int = 1024 * 1024, network: Network? = null): String? {
        var connection: HttpURLConnection? = null
        return try {
            require(maxBytes in 1..(1024 * 1024))
            connection = openHttps(urlString, network = network)
            if (connection.responseCode in 200..299) {
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        if (output.size() > maxBytes) throw IOException("Model metadata response is too large")
                    }
                    output.toString("UTF-8")
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

    companion object {
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")

        internal fun downloadErrorMessage(error: Throwable): String {
            var cause: Throwable? = error
            repeat(12) {
                if (cause is SSLException || cause is CertificateException) {
                    return "Could not verify the model server's secure connection. Check automatic date/time and Android system updates, then retry on another network if a VPN or HTTPS filter is active. Your partial download is kept; you can also import a model downloaded in your browser."
                }
                cause = cause?.cause
            }
            return error.message ?: "Download failed. Check your connection and retry."
        }
    }
}

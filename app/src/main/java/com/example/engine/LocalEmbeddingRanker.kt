package com.example.engine

import android.content.Context
import android.net.Uri
import com.example.grounding.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opt-in encoder import, never a silent model download or a hosted embedding service. */
class LocalEmbeddingRanker(private val context: Context) {
    private val file get() = File(context.filesDir, "passage-encoder.gguf")
    private val preferences get() = context.getSharedPreferences("passage_encoder", Context.MODE_PRIVATE)
    fun available() = file.isFile && preferences.getString("sha256", null) != null
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val pending = File(context.filesDir, "passage-encoder.pending")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input); pending.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n
                        require(total <= 128L * 1048576) { "Use a GGUF encoder no larger than 128 MB" }; output.write(buffer, 0, n) }
                }
            }
            val metadata = GgufParser().parseFromFile(pending).getOrThrow()
            require(metadata.architecture.contains("bert", true)) { "Use a compatible BERT-family GGUF encoder, not a chat model" }
            check(safe(pending)) { "Free memory and cool the device before importing an encoder" }
            NativeGguf.ensureAvailable()
            val test = NativeGguf.rankPassages(pending.absolutePath.toByteArray(), arrayOf("local test".toByteArray(), "local test".toByteArray()))
            check(test?.singleOrNull()?.let { it.isFinite() && it > .95f } == true) { "The packaged runtime could not encode this model; lexical ranking remains available" }
            check(pending.renameTo(file)) { "Could not install the encoder" }
            val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { stream -> val b = ByteArray(65536); while (true) { val n = stream.read(b); if (n < 0) break; digest.update(b, 0, n) } }
            preferences.edit().putString("sha256", digest.digest().joinToString("") { "%02x".format(it) }).commit()
        } finally { pending.delete() }
    }
    fun remove() { file.delete(); preferences.edit().clear().apply() }
    private fun safe(model: File): Boolean = HardwareAnalyzer.analyze(context).let {
        !it.isLowMemory && !it.isThermalThrottling && it.safeModelRamLimitMb >= model.length() / 1048576 * 3 + 384
    }
    fun scores(question: String, sources: List<EvidenceSource>): Map<String, Double> {
        if (!available() || !safe(file)) return emptyMap()
        return runCatching {
            val candidates = sources.filter { ExactIdentity.accepts(question, it.title + " " + it.excerpt) }.take(8)
            if (candidates.isEmpty()) return emptyMap()
            NativeGguf.ensureAvailable()
            val inputs = listOf(question.take(500)) + candidates.map { (it.title + " " + it.excerpt).take(900) }
            val scores = NativeGguf.rankPassages(file.absolutePath.toByteArray(), inputs.map { it.toByteArray() }.toTypedArray()) ?: return emptyMap()
            candidates.mapIndexedNotNull { index, source -> scores.getOrNull(index)?.takeIf(Float::isFinite)?.let { source.url to (it.coerceIn(-1f, 1f) * 5.0) } }.toMap()
        }.getOrDefault(emptyMap())
    }
}

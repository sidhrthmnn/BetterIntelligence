package com.example.engine

import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/** Resolve public single-file GGUFs to immutable revisions before offering a download. */
object HuggingFaceCatalog {
    fun normalizeRepo(value: String): String {
        val repo = value.trim().removePrefix("https://huggingface.co/").removeSuffix("/")
        require(repo.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*"))) {
            "Enter a Hugging Face repository as owner/name"
        }
        return repo
    }

    fun files(repo: String, metadata: JSONObject): List<HuggingFaceGgufFile> {
        val cleanRepo = normalizeRepo(repo)
        val revision = metadata.getString("sha")
        require(revision.matches(Regex("[a-fA-F0-9]{40}"))) { "Repository has no immutable revision" }
        val siblings = metadata.optJSONArray("siblings") ?: return emptyList()
        return (0 until siblings.length()).mapNotNull { index ->
            val file = siblings.getJSONObject(index)
            val path = file.getString("rfilename")
            if (!path.endsWith(".gguf", true) || path.contains("mmproj", true) ||
                Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE).containsMatchIn(path)) return@mapNotNull null
            if (path.split('/').any { it.isBlank() || it == "." || it == ".." }) return@mapNotNull null
            val lfs = file.optJSONObject("lfs")
            val size = file.optLong("size", lfs?.optLong("size") ?: 0)
            if (size <= 0) return@mapNotNull null
            val checksum = (lfs?.optString("sha256")?.ifBlank { null } ?: lfs?.optString("oid"))
                ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            val encoded = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val sizeMb = (size + 1048575) / 1048576
            val quantization = Regex("(?:IQ|Q)\\d(?:_[A-Z0-9]+)*", RegexOption.IGNORE_CASE)
                .find(path.substringAfterLast('/'))?.value?.uppercase()
                ?: if (path.contains("BF16", true)) "BF16" else if (path.contains("F16", true)) "F16" else "Unknown"
            HuggingFaceGgufFile(path, "https://huggingface.co/$cleanRepo/resolve/$revision/$encoded", size,
                sizeMb, checksum, quantization, (sizeMb * 1.25 + 512).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt(), 0f)
        }.sortedBy { it.sizeBytes }
    }

    fun localFilename(repo: String, file: HuggingFaceGgufFile): String {
        val identity = "$repo/${file.filename}/${file.sha256Oid ?: file.downloadUrl}"
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)
        val base = file.filename.substringAfterLast('/').dropLast(5)
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(150)
        return "hf-$key-$base.gguf"
    }
}

package com.example.engine

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cache full checksum checks of app-private catalog files. A different checksum,
 * path, inode, size, modification/change time, or age requires another full hash.
 * Explicit Verify always hashes; this cache only avoids repeated startup I/O.
 */
class VerifiedModelFileCache(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    companion object { const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 }
    private val prefs = context.getSharedPreferences("verified_model_files", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private fun key(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun identity(file: File, checksum: String): String? {
        if (!file.isFile) return null
        val stat = runCatching { Os.stat(file.absolutePath) }.getOrNull()
        return "$checksum:${file.canonicalPath}:${file.length()}:${file.lastModified()}:${stat?.st_ino}:${stat?.st_ctime}"
    }

    suspend fun verify(file: File, checksum: String?, fullCheck: suspend () -> Boolean): Boolean = lock.withLock {
        val cacheKey = key(file)
        if (checksum == null || !checksum.matches(Regex("[a-fA-F0-9]{64}"))) return@withLock false
        val before = identity(file, checksum) ?: run { invalidate(file); return@withLock false }
        val saved = runCatching { JSONObject(prefs.getString(cacheKey, "{}")!!) }.getOrDefault(JSONObject())
        if (saved.optString("identity") == before && now() - saved.optLong("saved") in 0..MAX_AGE_MS) return@withLock true
        if (!fullCheck() || identity(file, checksum) != before) {
            invalidate(file)
            return@withLock false
        }
        if (prefs.all.size >= 64 && !prefs.contains(cacheKey)) {
            val oldest = prefs.all.minByOrNull { (_, value) ->
                runCatching { JSONObject(value.toString()).optLong("saved") }.getOrDefault(0)
            }?.key
            if (oldest != null) prefs.edit().remove(oldest).commit()
        }
        prefs.edit().putString(cacheKey, JSONObject().put("identity", before).put("saved", now()).toString()).commit()
        true
    }

    fun invalidate(file: File) { prefs.edit().remove(key(file)).commit() }
}

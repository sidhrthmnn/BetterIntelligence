package com.example.ipc

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.example.data.AiCoreRepository
import com.example.data.IpcLogEntity
import com.example.engine.GenerationParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Zero-SDK ContentProvider that allows any Android app to query Better Intelligence
 * using standard Android ContentResolver APIs (`query`, `insert`, and `call`) without
 * needing to bundle AIDL files.
 *
 * Authority: content://com.aistudio.betterintelligence.mobile.provider
 */
class AiBrainContentProvider : ContentProvider() {

    private val providerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(): Boolean {
        return true
    }

    private fun getRepo(): AiCoreRepository? {
        val ctx = context?.applicationContext ?: return null
        return AiCoreRepository.getInstance(ctx)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val repo = getRepo() ?: return MatrixCursor(arrayOf("error")).apply { addRow(arrayOf("Repository unavailable")) }
        val callingPkg = resolveCallerPackage()
        val callingUid = Binder.getCallingUid()
        val startTime = System.currentTimeMillis()

        return when (uriMatcher.match(uri)) {
            MATCH_STATUS -> {
                val cursor = MatrixCursor(arrayOf("status", "active_model", "quantization", "context_length", "authority"))
                cursor.addRow(
                    arrayOf<Any?>(
                        LocalAiAccess(requireNotNull(context), repo).status().getString("status"),
                        repo.engine.activeModelName,
                        repo.engine.activeQuantization,
                        repo.engine.activeContextLength,
                        AUTHORITY
                    )
                )
                cursor
            }

            MATCH_GENERATE -> {
                val prompt = uri.getQueryParameter("prompt") ?: selection ?: ""
                val systemPrompt = uri.getQueryParameter("system")
                val (allowed, reason) = checkScope(repo, callingPkg, "INFERENCE")
                val cursor = MatrixCursor(arrayOf("status", "response", "model", "latency_ms"))
                if (!allowed) {
                    logProviderCall(repo, callingPkg, callingUid, "PROVIDER_QUERY", prompt, reason, 0L, "SCOPE_BLOCKED")
                    cursor.addRow(arrayOf<Any?>("DENIED", reason, "None", 0L))
                    return cursor
                }

                val output = runBlocking(Dispatchers.Default) {
                    val effectiveSystem = repo.buildClientSystemPrompt(callingPkg, systemPrompt)
                    repo.engine.generateSync(
                        prompt,
                        GenerationParams(systemPrompt = effectiveSystem, maxTokens = 256)
                    )
                }
                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(10L)
                val modelName = repo.engine.activeModelName
                logProviderCall(repo, callingPkg, callingUid, "PROVIDER_QUERY", prompt, output, latency, "SUCCESS")
                cursor.addRow(arrayOf<Any?>("SUCCESS", output, modelName, latency))
                cursor
            }

            MATCH_EXTRACT_JSON -> {
                val text = uri.getQueryParameter("text") ?: selection ?: ""
                val keysParam = uri.getQueryParameter("keys") ?: "summary,intent,entities,priority"
                val keys = keysParam.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

                val (allowed, reason) = checkScope(repo, callingPkg, "STRUCTURED_JSON")
                val cursor = MatrixCursor(arrayOf("status", "json_payload", "latency_ms"))
                if (!allowed) {
                    logProviderCall(repo, callingPkg, callingUid, "JSON_EXTRACT", text, reason, 0L, "SCOPE_BLOCKED")
                    cursor.addRow(arrayOf<Any?>("DENIED", """{"error": "$reason"}""", 0L))
                    return cursor
                }

                val json = runBlocking(Dispatchers.Default) {
                    repo.engine.extractStructuredJson(text, keys)
                }
                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(8L)
                logProviderCall(repo, callingPkg, callingUid, "JSON_EXTRACT", text, json, latency, "SUCCESS")
                cursor.addRow(arrayOf<Any?>("SUCCESS", json, latency))
                cursor
            }

            MATCH_ROUTE_INTENT -> {
                val utterance = uri.getQueryParameter("utterance") ?: selection ?: ""
                val routesParam = uri.getQueryParameter("routes") ?: "CREATE_NOTE,SCHEDULE_EVENT,SEND_MESSAGE,SEARCH"
                val routes = routesParam.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

                val (allowed, reason) = checkScope(repo, callingPkg, "STRUCTURED_JSON")
                val cursor = MatrixCursor(arrayOf("status", "routing_json", "latency_ms"))
                if (!allowed) {
                    logProviderCall(repo, callingPkg, callingUid, "INTENT_ROUTE", utterance, reason, 0L, "SCOPE_BLOCKED")
                    cursor.addRow(arrayOf<Any?>("DENIED", """{"error": "$reason"}""", 0L))
                    return cursor
                }

                val routed = runBlocking(Dispatchers.Default) {
                    repo.engine.routeIntent(utterance, routes)
                }
                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(6L)
                logProviderCall(repo, callingPkg, callingUid, "INTENT_ROUTE", utterance, routed, latency, "SUCCESS")
                cursor.addRow(arrayOf<Any?>("SUCCESS", routed, latency))
                cursor
            }

            MATCH_MEMORIES -> {
                val q = uri.getQueryParameter("q") ?: selection ?: ""
                val limit = uri.getQueryParameter("limit")?.toIntOrNull() ?: 15
                val (allowed, reason) = checkScope(repo, callingPkg, "READ_MEMORY")
                val cursor = MatrixCursor(arrayOf("status", "memories_json"))
                if (!allowed) {
                    logProviderCall(repo, callingPkg, callingUid, "MEMORY_READ", q, reason, 0L, "SCOPE_BLOCKED")
                    cursor.addRow(arrayOf("DENIED", """{"error": "$reason", "memories": []}"""))
                    return cursor
                }

                val json = runBlocking(Dispatchers.IO) {
                    repo.querySharedMemoriesAsJson(callingPkg, q, limit)
                }
                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(4L)
                logProviderCall(repo, callingPkg, callingUid, "MEMORY_READ", "URI Query: '$q'", json, latency, "SUCCESS")
                cursor.addRow(arrayOf("SUCCESS", json))
                cursor
            }

            else -> {
                val cursor = MatrixCursor(arrayOf("error"))
                cursor.addRow(arrayOf("Unsupported URI: $uri"))
                cursor
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val repo = getRepo() ?: return null
        val callingPkg = resolveCallerPackage()
        val callingUid = Binder.getCallingUid()

        if (uriMatcher.match(uri) == MATCH_MEMORIES && values != null) {
            val (allowed, reason) = checkScope(repo, callingPkg, "WRITE_MEMORY")
            val title = values.getAsString("title") ?: "Shared Context"
            val content = values.getAsString("content") ?: return null
            val category = values.getAsString("category") ?: "Cross-App"

            if (!allowed) {
                logProviderCall(repo, callingPkg, callingUid, "MEMORY_WRITE", "$title: $content", reason, 0L, "SCOPE_BLOCKED")
                return null
            }

            val id = runBlocking(Dispatchers.IO) {
                repo.writeSharedMemoryFromApp(callingPkg, title, content, category)
            }
            logProviderCall(repo, callingPkg, callingUid, "MEMORY_WRITE", "$title ($category)", "Inserted Memory #$id", 5L, "SUCCESS")
            return ContentUris.withAppendedId(uri, id)
        }
        return null
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val repo = getRepo() ?: return Bundle().apply { putString("status", "ERROR") }
        // ContentProvider.call() requires explicit permission enforcement as well as manifest protection.
        val access = LocalAiAccess(requireNotNull(context), repo)
        access.callerPackage()
        if (method == "status") return access.status()
        if (method == "polish") return access.polish(arg ?: extras?.getString("text"))
        return try { legacyCall(method, arg, extras) }
        catch (e: Exception) { Bundle().apply {
            putString("status", if (e.message?.contains("busy", true) == true) "BUSY" else "ERROR")
            putString("error", "Local AI request failed")
        } }
    }

    private fun legacyCall(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        val repo = getRepo() ?: return result.apply { putString("error", "Repository unavailable") }
        val callingPkg = resolveCallerPackage()
        val callingUid = Binder.getCallingUid()
        val startTime = System.currentTimeMillis()

        when (method) {
            "generate" -> {
                val prompt = arg ?: extras?.getString("prompt") ?: ""
                val systemPrompt = extras?.getString("system_prompt")
                val (allowed, reason) = checkScope(repo, callingPkg, "INFERENCE")
                if (!allowed) {
                    logProviderCall(repo, callingPkg, callingUid, "PROVIDER_QUERY", prompt, reason, 0L, "SCOPE_BLOCKED")
                    result.putString("status", "DENIED")
                    result.putString("error", reason)
                    return result
                }
                val output = runBlocking(Dispatchers.Default) {
                    val effectiveSystem = repo.buildClientSystemPrompt(callingPkg, systemPrompt)
                    repo.engine.generateSync(prompt, GenerationParams(systemPrompt = effectiveSystem, maxTokens = 256))
                }
                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(10L)
                logProviderCall(repo, callingPkg, callingUid, "PROVIDER_QUERY", prompt, output, latency, "SUCCESS")
                result.putString("status", "SUCCESS")
                result.putString("response", output)
                result.putLong("latency_ms", latency)
            }

            "extract_json" -> {
                val text = arg ?: extras?.getString("text") ?: ""
                val keys = extras?.getStringArray("schema_keys") ?: arrayOf("summary", "intent", "entities", "priority")
                val (allowed, reason) = checkScope(repo, callingPkg, "STRUCTURED_JSON")
                if (!allowed) {
                    logProviderCall(repo, callingPkg, callingUid, "JSON_EXTRACT", text, reason, 0L, "SCOPE_BLOCKED")
                    result.putString("status", "DENIED")
                    result.putString("error", reason)
                    return result
                }
                val json = runBlocking(Dispatchers.Default) {
                    repo.engine.extractStructuredJson(text, keys)
                }
                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(8L)
                logProviderCall(repo, callingPkg, callingUid, "JSON_EXTRACT", text, json, latency, "SUCCESS")
                result.putString("status", "SUCCESS")
                result.putString("json", json)
                result.putLong("latency_ms", latency)
            }

            "route_intent" -> {
                val utterance = arg ?: extras?.getString("utterance") ?: ""
                val routes = extras?.getStringArray("routes") ?: arrayOf("CREATE_NOTE", "SCHEDULE_EVENT", "SEND_MESSAGE", "SEARCH")
                val (allowed, reason) = checkScope(repo, callingPkg, "STRUCTURED_JSON")
                if (!allowed) {
                    logProviderCall(repo, callingPkg, callingUid, "INTENT_ROUTE", utterance, reason, 0L, "SCOPE_BLOCKED")
                    result.putString("status", "DENIED")
                    result.putString("error", reason)
                    return result
                }
                val routed = runBlocking(Dispatchers.Default) {
                    repo.engine.routeIntent(utterance, routes)
                }
                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(6L)
                logProviderCall(repo, callingPkg, callingUid, "INTENT_ROUTE", utterance, routed, latency, "SUCCESS")
                result.putString("status", "SUCCESS")
                result.putString("routing_json", routed)
                result.putLong("latency_ms", latency)
            }

            "store_memory" -> {
                val title = extras?.getString("title") ?: "Shared Context"
                val content = arg ?: extras?.getString("content") ?: ""
                val category = extras?.getString("category") ?: "Cross-App"
                val (allowed, reason) = checkScope(repo, callingPkg, "WRITE_MEMORY")
                if (!allowed) {
                    logProviderCall(repo, callingPkg, callingUid, "MEMORY_WRITE", "$title: $content", reason, 0L, "SCOPE_BLOCKED")
                    result.putString("status", "DENIED")
                    result.putString("error", reason)
                    return result
                }
                val id = runBlocking(Dispatchers.IO) {
                    repo.writeSharedMemoryFromApp(callingPkg, title, content, category)
                }
                logProviderCall(repo, callingPkg, callingUid, "MEMORY_WRITE", "$title ($category)", "Saved Memory #$id: $content", 5L, "SUCCESS")
                result.putString("status", "SUCCESS")
                result.putLong("memory_id", id)
            }

            "query_memories" -> {
                val query = arg ?: extras?.getString("query") ?: ""
                val limit = extras?.getInt("limit", 10) ?: 10
                val (allowed, reason) = checkScope(repo, callingPkg, "READ_MEMORY")
                if (!allowed) {
                    logProviderCall(repo, callingPkg, callingUid, "MEMORY_READ", query, reason, 0L, "SCOPE_BLOCKED")
                    result.putString("status", "DENIED")
                    result.putString("error", reason)
                    return result
                }
                val json = runBlocking(Dispatchers.IO) {
                    repo.querySharedMemoriesAsJson(callingPkg, query, limit)
                }
                logProviderCall(repo, callingPkg, callingUid, "MEMORY_READ", "Query: '$query'", json, 5L, "SUCCESS")
                result.putString("status", "SUCCESS")
                result.putString("memories_json", json)
            }
            else -> { result.putString("status", "INVALID_REQUEST"); result.putString("error", "Unknown method") }
        }
        return result
    }

    override fun getType(uri: Uri): String {
        return "vnd.android.cursor.item/vnd.$AUTHORITY.brain"
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolveCallerPackage(): String {
        return LocalAiAccess(requireNotNull(context), requireNotNull(getRepo())).callerPackage()
    }

    private fun checkScope(repo: AiCoreRepository, pkg: String, scope: String): Pair<Boolean, String> {
        val myPkg = context?.packageName
        if (pkg == myPkg) return Pair(true, "OK")
        return runBlocking {
            repo.checkSecurityAndRateLimit(pkg, scope)
        }
    }

    private fun logProviderCall(
        repo: AiCoreRepository,
        pkg: String,
        uid: Int,
        type: String,
        prompt: String,
        response: String,
        latencyMs: Long,
        status: String
    ) {
        providerScope.launch {
            repo.logIpcCall(
                IpcLogEntity(
                    callingPackage = pkg,
                    callingUid = uid,
                    requestType = type,
                    promptPreview = prompt.take(150),
                    responsePreview = response.take(200),
                    latencyMs = latencyMs,
                    tokensPerSecond = if (latencyMs > 0) ((response.length / 4).coerceAtLeast(1) * 1000f) / latencyMs else 0f,
                    totalTokens = (response.length / 4).coerceAtLeast(1),
                    timestamp = System.currentTimeMillis(),
                    status = status
                )
            )
        }
    }

    companion object {
        const val AUTHORITY = "com.aistudio.betterintelligence.mobile.provider"
        private const val MATCH_STATUS = 1
        private const val MATCH_GENERATE = 2
        private const val MATCH_EXTRACT_JSON = 3
        private const val MATCH_ROUTE_INTENT = 4
        private const val MATCH_MEMORIES = 5

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "status", MATCH_STATUS)
            addURI(AUTHORITY, "generate", MATCH_GENERATE)
            addURI(AUTHORITY, "extract_json", MATCH_EXTRACT_JSON)
            addURI(AUTHORITY, "route_intent", MATCH_ROUTE_INTENT)
            addURI(AUTHORITY, "memories", MATCH_MEMORIES)
        }
    }
}

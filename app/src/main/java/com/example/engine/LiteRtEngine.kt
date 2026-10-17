package com.example.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Uses the same admission, cancellation and telemetry core as chat and Binder. */
class LiteRtEngine(context: Context, private val core: InferenceEngine = InferenceEngine(context)) :
    LlmEngine by LlamaCppEngine(context, core) {
    override val engineType = "LiteRT-LM (.litertlm)"
    override suspend fun initialize(modelPath: String, preferredBackend: BackendType): Result<Unit> =
        withContext(Dispatchers.IO) { core.loadLiteRt(File(modelPath)) }
}

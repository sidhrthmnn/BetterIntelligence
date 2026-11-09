package com.example.engine

import android.content.Context
import androidx.work.WorkManager
import com.example.data.*
import com.example.service.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import java.io.File

/** App-private state. No chat text, network planner or cloud inference is used to select models. */
data class AutomaticSetupState(val device: DeviceIntelligenceSnapshot? = null, val pair: AutomaticModelPair? = null,
    val status: String = "Check your device to begin", val reports: List<ModelQualityReport> = emptyList(),
    val pending: Boolean = false, val error: String = "")
class AutomaticSetupController(private val context: Context, private val repo: AiCoreRepository) {
    private val prefs = context.getSharedPreferences("automatic_setup", Context.MODE_PRIVATE)
    private val intentEpoch = java.util.concurrent.atomic.AtomicLong()
    fun approvalToken() = intentEpoch.get()
    fun isCurrentPending(epoch: Long) = intentEpoch.get() == epoch && prefs.getBoolean("pending", false)
    private val _state = MutableStateFlow(AutomaticSetupState(status = prefs.getString("status", null) ?: "Check your device to begin",
        pending = prefs.getBoolean("pending", false), reports = reports()))
    val state = _state.asStateFlow()
    fun seen() = prefs.getBoolean("seen", false)
    fun dismiss() { prefs.edit().putBoolean("seen", true).apply() }
    fun reports(): List<ModelQualityReport> = runCatching {
        val a = JSONArray(prefs.getString("reports", "[]")); (0 until a.length()).map { ModelQualityReport.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    fun cached(model: ModelEntity) = reports().lastOrNull { it.modelId == model.id && it.key.startsWith(ModelQualityBenchmark.key(model, ModelLibrary.configuredContext(model, 1024)) +
        ":${repo.engine.hardware.mode.value}:${repo.engine.hardware.layerLimit.value}:") &&
        (it.backend != "GPU" || repo.engine.hardware.capabilities.value.gpuAvailable) &&
        (it.backend != "NPU" || repo.engine.hardware.capabilities.value.npuAvailable) }
    @Synchronized fun saveReport(report: ModelQualityReport) {
        val results = (reports().filterNot { it.key == report.key } + report).takeLast(20)
        prefs.edit().putString("reports", JSONArray().apply { results.forEach { put(it.json()) } }.toString()).commit()
        _state.value = _state.value.copy(reports = results)
    }
    fun status(value: String, error: String = "") { prefs.edit().putString("status", value).apply(); _state.value = _state.value.copy(status = value, error = error) }
    suspend fun checkDevice(): DeviceIntelligenceSnapshot {
        repo.awaitModelInitialization()
        val snapshot = DeviceIntelligenceChecker(context).check(repo.engine)
        val acceptedGemma = context.getSharedPreferences("model_terms", Context.MODE_PRIVATE).getBoolean("gemma", false)
        val models = repo.allModels.first().filter { acceptedGemma || !it.architecture.startsWith("gemma") || it.architecture.startsWith("gemma4") }
        val primaryId = repo.preferencesRepository.primaryModelId.first()
        val secondaryId = repo.preferencesRepository.secondaryModelId.first()
        val selected = models.filter { it.isInstalled && it.id in listOfNotNull(primaryId, secondaryId) }
        val measured = selected.mapNotNull(::cached)
        val rejected = models.filter { cached(it)?.passed == false }.map { it.id }.toSet()
        val pair = if (!_state.value.pending && selected.any { it.id == primaryId } && measured.any { it.modelId == primaryId && it.passed })
            AutomaticModelPair(selected.find { it.id == primaryId }, selected.find { it.id == secondaryId }, 0,
                "Your primary has on-device measurements. Your model choices are preserved; recheck or choose manually whenever you need.", true)
            else AutomaticModelSelector.recommend(models.filterNot { it.id in rejected }, snapshot.profile).let {
                if (it.primary == null && rejected.isNotEmpty()) it.copy(reason = "The checked candidates did not pass automatic quality checks. Choose another model, or use your installed models manually.") else it }
        _state.value = _state.value.copy(device = snapshot, pair = pair, reports = reports())
        if (_state.value.status == "Check your device to begin") status("Device checked")
        return snapshot
    }
    suspend fun approve(wifiOnly: Boolean, reviewed: AutomaticModelPair? = _state.value.pair, epoch: Long = approvalToken()) {
        check(intentEpoch.get() == epoch) { "Automatic setup was cancelled. Review it again to continue." }
        val pair = checkNotNull(reviewed); checkNotNull(pair.primary) { pair.reason }
        val snapshot = checkDevice()
        // Freeze the reviewed IDs; periodic hardware samples never enlarge an approved download.
        val approved = listOfNotNull(pair.primary, pair.secondary).distinctBy { it.id }
        val models = approved.map { reviewedModel ->
            repo.allModels.first().singleOrNull { it.id == reviewedModel.id && it.filename == reviewedModel.filename && it.sha256Checksum == reviewedModel.sha256Checksum }
                ?: error("The model library changed. Review the recommendation again.")
        }
        check(models.all { ModelLibrary.fits(it, snapshot.profile, ModelLibrary.configuredContext(it, 1024)) }) { "Available memory changed. Review the recommendation again." }
        check(models.filterNot { it.isInstalled }.sumOf { it.fileSizeMb } <= pair.downloadMb) { "Download size changed. Review the recommendation again." }
        check(models.filterNot { it.isInstalled }.sumOf { it.fileSizeMb } + 128 <= checkNotNull(_state.value.device).profile.internalStorageFreeGb * 1024) { "Free storage before setup" }
        synchronized(this) {
            check(intentEpoch.get() == epoch) { "Automatic setup was cancelled. Review it again to continue." }
            check(prefs.edit().putInt("validation_failures", 0).putBoolean("seen", true).putBoolean("pending", true).putString("ids", models.joinToString(",") { it.id.toString() }).commit())
            repo.automaticSetupApproved()
            _state.value = _state.value.copy(pending = true, pair = pair)
        }
        DownloadQueueStore(context).setWifiOnly(wifiOnly)
        models.sortedBy { it.fileSizeMb }.filterNot { it.isInstalled }.forEach { repo.startModelDownload(it, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)) }
        if (isCurrentPending(epoch)) status(if (models.any { !it.isInstalled }) "Downloading selected models" else "Models installed; waiting for safe benchmark conditions")
        if (models.all { it.isInstalled }) AutomaticSetupWorker.enqueue(context)
    }
    @Synchronized fun cancelPending() {
        intentEpoch.incrementAndGet()
        val wasPending = prefs.getBoolean("pending", false)
        prefs.edit().putBoolean("pending", false).commit(); _state.value = _state.value.copy(pending = false)
        WorkManager.getInstance(context).cancelUniqueWork("automatic-model-validation")
        if (wasPending) status("Using your manual model choice")
    }
    suspend fun finish(force: Boolean = false): Boolean {
        repo.awaitModelInitialization()
        if (!prefs.getBoolean("pending", false)) return true
        val epoch = approvalToken()
        if (DownloadQueueStore(context).ids().isNotEmpty()) return false
        val ids = prefs.getString("ids", "").orEmpty().split(',').mapNotNull(String::toLongOrNull)
        val models = repo.allModels.first().filter { it.id in ids }
        if (models.isEmpty() || models.any { !it.isInstalled }) { status("Setup needs attention", "Download the selected models or choose manually."); prefs.edit().putBoolean("pending", false).commit(); _state.value = _state.value.copy(pending = false); return true }
        if (!checkDevice().canBenchmark || repo.engine.telemetry.value.isRunning || repo.isModelLoading) {
            status("Waiting for free memory, a cooler device and battery headroom"); return false
        }
        try {
            status("Checking models on this device")
            val reports = repo.evaluateSetupModels(models, force, setupEpoch = epoch, progress = ::status)
            if (!isCurrentPending(epoch)) return true // Manual selection wins.
            val pair = AutomaticModelSelector.chooseMeasured(models, reports)
            if (pair.primary == null) { status("Choose a model manually", "No candidate passed the local quality and stability checks. Your previous model was kept."); prefs.edit().putBoolean("pending", false).apply(); _state.value = _state.value.copy(pending = false); return true }
            repo.activateMeasuredPair(pair.primary, pair.secondary, epoch)
            synchronized(this) {
            if (!isCurrentPending(epoch)) return true
            prefs.edit().putBoolean("pending", false).commit()
            _state.value = _state.value.copy(pair = pair, pending = false)
            status("Your AI is ready")
            }
            return true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failures = prefs.getInt("validation_failures", 0) + 1
            prefs.edit().putInt("validation_failures", failures).commit()
            status("Setup paused", e.message.orEmpty().take(240))
            if (failures >= 3) { prefs.edit().putBoolean("pending", false).commit(); _state.value = _state.value.copy(pending = false) }
            return failures >= 3
        }
    }
    suspend fun rerun(epoch: Long = approvalToken()) {
        val primary = repo.preferencesRepository.primaryModelId.first(); val secondary = repo.preferencesRepository.secondaryModelId.first()
        synchronized(this) {
        if (intentEpoch.get() != epoch) return
        prefs.edit().putInt("validation_failures", 0).putString("ids", listOfNotNull(primary, secondary).distinct().joinToString(",")).putBoolean("pending", true).commit()
        _state.value = _state.value.copy(pending = true)
        }
        finish(force = true)
    }
}

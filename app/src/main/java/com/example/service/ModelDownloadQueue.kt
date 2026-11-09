package com.example.service

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.MainActivity
import com.example.R
import com.example.data.AiCoreRepository
import com.example.data.ModelEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Durable ordering/consent; Room remains the authoritative model/download state. */
class DownloadQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("model_download_queue", Context.MODE_PRIVATE)
    fun ids(): List<Long> = prefs.getString("queue", "").orEmpty().split(',').mapNotNull(String::toLongOrNull).distinct()
    fun wifiOnly() = prefs.getBoolean("wifi_only", true)
    fun setWifiOnly(value: Boolean) { prefs.edit().putBoolean("wifi_only", value).commit() }
    fun enqueue(id: Long) = synchronized(lock) { write(ids() + id) }
    fun remove(id: Long) = synchronized(lock) { write(ids().filterNot { it == id }); prefs.edit().remove("attempt_$id").commit(); Unit }
    fun failure(id: Long): Int = synchronized(lock) { val n = prefs.getInt("attempt_$id", 0) + 1; prefs.edit().putInt("attempt_$id", n).commit(); n }
    private fun write(ids: List<Long>) { check(prefs.edit().putString("queue", ids.distinct().joinToString(",")).commit()) }
    companion object { private val lock = Any() }
}
object ModelDownloadQueue {
    const val JOB = 51032
    const val CHANNEL = "model_downloads"
    private val serial = Mutex()
    /** Called when the user reopens BI. Respect existing OS work and force-stop semantics. */
    fun ensureScheduled(context: Context) {
        if (DownloadQueueStore(context).ids().isEmpty()) return
        if (Build.VERSION.SDK_INT >= 34 && context.getSystemService(JobScheduler::class.java).getPendingJob(JOB) != null) return
        val active = WorkManager.getInstance(context).getWorkInfosForUniqueWork("model-download-queue").get().any { !it.state.isFinished }
        if (!active) schedule(context, userInitiated = false)
    }
    fun schedule(context: Context, userInitiated: Boolean) {
        val queue = DownloadQueueStore(context)
        if (queue.ids().isEmpty()) return
        if (Build.VERSION.SDK_INT >= 34 && userInitiated) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(JOB, ComponentName(context, ModelTransferJob::class.java))
                .setUserInitiated(true).setPersisted(true).setRequiredNetworkType(if (queue.wifiOnly()) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setRequiresStorageNotLow(true).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            if (runCatching { scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)) return
        }
        // Deferred retry/fallback: Android may postpone execution; never bypass system quotas.
        WorkManager.getInstance(context).enqueueUniqueWork("model-download-queue", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>().setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (queue.wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun notification(context: Context, model: ModelEntity? = null): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val id = model?.id ?: 0L
        fun action(name: String) = PendingIntent.getBroadcast(context, name.hashCode() + id.toInt(),
            Intent(context, ModelDownloadAction::class.java).putExtra("model", id).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val percent = ((model?.downloadProgress ?: 0f) * 100).toInt()
        val remainingMb = model?.let { (it.fileSizeMb * (1 - it.downloadProgress)).toLong() }
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(open)
            .setContentTitle(model?.name ?: "Preparing model downloads")
            .setContentText(model?.let { "$percent% · ${"%.1f".format(it.downloadSpeedMbps / 8)} MB/s · $remainingMb MB remaining" } ?: "Waiting for a permitted network")
            .setProgress(100, percent, model == null).setOnlyAlertOnce(true).setOngoing(true)
        if (model != null) builder.addAction(0, "Pause", action("pause")).addAction(0, "Cancel", action("cancel"))
        return builder.build()
    }
    suspend fun run(context: Context, progress: suspend (ModelEntity) -> Unit): Boolean = serial.withLock {
        val repo = AiCoreRepository.getInstance(context); repo.awaitModelInitialization()
        val queue = DownloadQueueStore(context)
        while (true) {
            val id = queue.ids().firstOrNull() ?: break
            try {
                val model = repo.modelForDownload(id)
                if (model == null || model.isInstalled && model.downloadState == "DOWNLOADED") { queue.remove(id); continue }
                repo.executeQueuedDownload(model, progress)
                if (id !in queue.ids()) continue
                val installed = repo.modelForDownload(id)?.takeIf { it.isInstalled && it.downloadState == "DOWNLOADED" }
                if (installed != null) { queue.remove(id); repo.provisionFirstSetupModel(installed) }
                else if (queue.failure(id) >= 3) queue.remove(id) else return@withLock false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (queue.failure(id) >= 3) queue.remove(id) else return@withLock false }
        }
        AutomaticSetupWorker.enqueue(context)
        true
    }
}
class ModelTransferJob : JobService() {
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT >= 34) setNotification(params, ModelDownloadQueue.JOB,
            ModelDownloadQueue.notification(this), JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
        task = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
            val ok = ModelDownloadQueue.run(this@ModelTransferJob) {
                if (Build.VERSION.SDK_INT >= 34) setNotification(params, ModelDownloadQueue.JOB, ModelDownloadQueue.notification(this@ModelTransferJob, it), JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
            }
            jobFinished(params, !ok)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { jobFinished(params, DownloadQueueStore(this@ModelTransferJob).ids().isNotEmpty()) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); return DownloadQueueStore(this).ids().isNotEmpty() }
    override fun onDestroy() { task?.cancel(); super.onDestroy() }
}
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        setForeground(info(null))
        if (ModelDownloadQueue.run(applicationContext) { setForeground(info(it)) }) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    private fun info(model: ModelEntity?) = if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ModelDownloadQueue.JOB,
        ModelDownloadQueue.notification(applicationContext, model), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(ModelDownloadQueue.JOB, ModelDownloadQueue.notification(applicationContext, model))
}
class ModelDownloadAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = AiCoreRepository.getInstance(context); val id = intent.getLongExtra("model", 0)
                if (intent.action == "cancel") repo.deletePartialDownload(id) else repo.cancelModelDownload(id, this)
            } finally { pending.finish() }
        }
    }
}
class AutomaticSetupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = AiCoreRepository.getInstance(applicationContext)
        return try { if (repo.finishAutomaticSetup()) Result.success() else Result.retry() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun enqueue(context: Context) {
            if (!context.getSharedPreferences("automatic_setup", Context.MODE_PRIVATE).getBoolean("pending", false)) return
            WorkManager.getInstance(context).enqueueUniqueWork("automatic-model-validation", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutomaticSetupWorker>().setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build())
        }
    }
}

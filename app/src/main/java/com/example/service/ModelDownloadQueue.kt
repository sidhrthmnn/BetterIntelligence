package com.example.service

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.MainActivity
import com.example.R
import com.example.data.AiCoreRepository
import com.example.data.ModelEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Durable ordering/consent; Room remains the authoritative model/download state. */
class DownloadQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("model_download_queue", Context.MODE_PRIVATE)
    fun ids(): List<Long> = prefs.getString("queue", "").orEmpty().split(',').mapNotNull(String::toLongOrNull).distinct()
    fun backgroundDownloadsEnabled() = prefs.getBoolean("background_downloads_enabled", true)
    fun setBackgroundDownloadsEnabled(value: Boolean) { prefs.edit().putBoolean("background_downloads_enabled", value).commit() }
    fun wifiOnly() = prefs.getBoolean("wifi_only", !prefs.getBoolean("allow_mobile_data", false))
    fun setWifiOnly(value: Boolean) { check(prefs.edit().putBoolean("wifi_only", value).remove("allow_mobile_data").commit()) }
    fun autoResumeEnabled() = prefs.getBoolean("auto_resume_downloads", true)
    fun setAutoResumeEnabled(value: Boolean) { prefs.edit().putBoolean("auto_resume_downloads", value).commit() }
    fun notificationsEnabled() = prefs.getBoolean("download_notifications", true)
    fun setNotificationsEnabled(value: Boolean) { prefs.edit().putBoolean("download_notifications", value).commit() }
    fun enqueue(id: Long) = synchronized(lock) { write(ids() + id) }
    fun remove(id: Long) = synchronized(lock) {
        check(prefs.edit().putString("queue", ids().filterNot { it == id }.joinToString(","))
            .remove("attempt_$id").commit())
    }
    fun resetFailures(id: Long) = synchronized(lock) { prefs.edit().remove("attempt_$id").commit(); Unit }
    fun failure(id: Long): Int = synchronized(lock) { val n = prefs.getInt("attempt_$id", 0) + 1; prefs.edit().putInt("attempt_$id", n).commit(); n }
    private fun write(ids: List<Long>) { check(prefs.edit().putString("queue", ids.distinct().joinToString(",")).commit()) }
    companion object { private val lock = Any() }
}
object ModelDownloadQueue {
    const val JOB = 51032
    const val CHANNEL = "model_downloads"
    private const val WORK = "model-download-queue"
    private const val TAG = "ModelDownloadQueue"
    private val serial = Mutex()
    private val schedulingLock = Any()
    private val scheduled = MutableStateFlow(0)
    private var uidtFinishing = false

    private fun wifiRequest() = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    private fun Network.isWifi(context: Context): Boolean =
        context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(this)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    fun ensureScheduled(context: Context) {
        if (DownloadQueueStore(context).autoResumeEnabled()) schedule(context, userInitiated = false)
    }

    fun schedule(context: Context, userInitiated: Boolean, policyChanged: Boolean = false) = synchronized(schedulingLock) {
        val queue = DownloadQueueStore(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val work = WorkManager.getInstance(context)
        scheduled.update { it + 1 }
        if (policyChanged) {
            if (Build.VERSION.SDK_INT >= 34) scheduler.cancel(JOB)
            work.cancelUniqueWork(WORK)
        }
        if (!queue.backgroundDownloadsEnabled() || queue.ids().isEmpty()) return@synchronized
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = scheduler.getPendingJob(JOB)
            if (!policyChanged && !uidtFinishing && pending != null) return@synchronized
            if (pending == null) uidtFinishing = false
            if (userInitiated && !uidtFinishing) {
                val submitted = runCatching {
                    val builder = JobInfo.Builder(JOB, ComponentName(context, ModelTransferJob::class.java))
                        .setUserInitiated(true).setPersisted(queue.autoResumeEnabled())
                        .setRequiresStorageNotLow(true).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    if (queue.wifiOnly()) builder.setRequiredNetwork(wifiRequest())
                    else builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
                }.onFailure { Log.w(TAG, "User-initiated download unavailable: " + it.javaClass.simpleName) }.getOrDefault(false)
                if (submitted) {
                    work.cancelUniqueWork(WORK)
                    return@synchronized
                }
            }
        }
        val constraints = Constraints.Builder().setRequiresStorageNotLow(true)
        if (Build.VERSION.SDK_INT >= 28 && queue.wifiOnly()) {
            constraints.setRequiredNetworkRequest(wifiRequest(), NetworkType.UNMETERED)
        } else {
            constraints.setRequiredNetworkType(if (queue.wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED)
        }
        val policy = when {
            policyChanged -> ExistingWorkPolicy.REPLACE
            userInitiated -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.KEEP
        }
        work.enqueueUniqueWork(WORK, policy, OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints.build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        Unit
    }

    fun jobStarted() = synchronized(schedulingLock) { uidtFinishing = false }
    fun finishJob(context: Context, retry: Boolean, finish: (Boolean) -> Unit) = synchronized(schedulingLock) {
        // Cover requests appended between the last drain read and jobFinished.
        val reschedule = retry || DownloadQueueStore(context).ids().isNotEmpty()
        uidtFinishing = !reschedule
        finish(reschedule)
    }

    /** When background downloads are off, the activity owns the transfer. */
    suspend fun runWhileVisible(context: Context) = withContext(Dispatchers.IO) {
        val queue = DownloadQueueStore(context)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        while (isActive) {
            val changedAt = scheduled.value
            if (!queue.backgroundDownloadsEnabled() && queue.ids().isNotEmpty()) {
                val network = connectivity.activeNetwork
                if (network != null && (!queue.wifiOnly() || network.isWifi(context))) {
                    if (!run(context, network, background = false) {}) delay(15_000)
                } else delay(3_000)
            } else scheduled.first { it != changedAt }
        }
    }

    fun notification(context: Context, model: ModelEntity? = null): Notification {
        val queue = DownloadQueueStore(context)
        val notifyEnabled = queue.notificationsEnabled()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Model downloads", if (notifyEnabled) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val id = model?.id ?: 0L
        fun action(name: String) = PendingIntent.getBroadcast(context, name.hashCode() + id.toInt(),
            Intent(context, ModelDownloadAction::class.java).putExtra("model", id).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val percent = ((model?.downloadProgress ?: 0f) * 100).toInt()
        val remainingMb = model?.let { (it.fileSizeMb * (1 - it.downloadProgress)).toLong() }
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(open)
            .setContentTitle(model?.name ?: "Preparing model downloads")
            .setContentText(model?.let { "$percent% · ${"%.1f".format(it.downloadSpeedMbps / 8)} MB/s · $remainingMb MB remaining" } ?: "Waiting for a permitted network")
            .setProgress(100, percent, model == null).setOnlyAlertOnce(true).setSilent(!notifyEnabled).setOngoing(true)
            .setPriority(if (notifyEnabled) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
        if (model != null && notifyEnabled) builder.addAction(0, "Pause", action("pause")).addAction(0, "Cancel", action("cancel"))
        return builder.build()
    }
    suspend fun run(context: Context, assignedNetwork: Network?, background: Boolean,
        progress: suspend (ModelEntity) -> Unit): Boolean = serial.withLock {
        val queue = DownloadQueueStore(context)
        val wifiOnly = queue.wifiOnly()
        if (queue.ids().isEmpty()) return@withLock true
        if (queue.backgroundDownloadsEnabled() != background) return@withLock false
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun activeVpn(): Network? = connectivity.activeNetwork?.takeIf {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

        // The job's assigned network prevents a Wi-Fi download from silently
        // switching to mobile data. Keep the user's active VPN when there is one.
        val network = activeVpn() ?: assignedNetwork ?: connectivity.activeNetwork
        if (network == null || wifiOnly && !network.isWifi(context)) return@withLock false

        val repo = AiCoreRepository.getInstance(context)
        repo.awaitModelInitialization()
        val deferred = mutableSetOf<Long>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val id = queue.ids().firstOrNull { it !in deferred } ?: break
            val canContinue = {
                queue.backgroundDownloadsEnabled() == background && wifiOnly == queue.wifiOnly() &&
                    (!wifiOnly || network.isWifi(context)) && activeVpn().let { it == null || it == network }
            }
            if (!canContinue()) return@withLock false
            val model = repo.modelForDownload(id)
            if (model == null || model.isInstalled) { queue.remove(id); continue }
            val failure = repo.executeQueuedDownload(model, network, canContinue, progress)
            if (id !in queue.ids()) continue
            val installed = repo.modelForDownload(id)?.takeIf { it.isInstalled && it.downloadState == "DOWNLOADED" }
            if (installed != null) {
                queue.remove(id)
                repo.provisionFirstSetupModel(installed)
                continue
            }
            if (failure == null) continue
            if (repo.recordDownloadFailure(id, failure)) {
                deferred += id
                repo.modelForDownload(id)?.let { progress(it) }
            }
        }
        if (deferred.isNotEmpty() && queue.ids().any { it in deferred }) return@withLock false
        AutomaticSetupWorker.enqueue(context)
        true
    }
}
class ModelTransferJob : JobService() {
    private var task: Job? = null

    @androidx.annotation.RequiresApi(34)
    override fun onStartJob(params: JobParameters): Boolean {
        ModelDownloadQueue.jobStarted()
        val app = applicationContext
        if (DownloadQueueStore(app).ids().isEmpty()) return false
        try {
            setNotification(params, ModelDownloadQueue.JOB, ModelDownloadQueue.notification(app),
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
        } catch (e: IllegalStateException) {
            Log.w("ModelDownloadQueue", "Transfer notification rejected", e)
            ModelDownloadQueue.schedule(app, userInitiated = false, policyChanged = true)
            return false
        }
        task = CoroutineScope(Dispatchers.Main.immediate).launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    ModelDownloadQueue.run(app, params.network, background = true) {
                        try {
                            setNotification(params, ModelDownloadQueue.JOB, ModelDownloadQueue.notification(app, it),
                                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
                        } catch (e: IllegalStateException) {
                            Log.i("ModelDownloadQueue", "Transfer notification update was unavailable")
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("ModelDownloadQueue", "Transfer job interrupted", e)
                false
            }
            ModelDownloadQueue.finishJob(app, !ok) { jobFinished(params, it) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel()
        return DownloadQueueStore(this).let { it.backgroundDownloadsEnabled() && it.ids().isNotEmpty() }
    }
    override fun onDestroy() { task?.cancel(); super.onDestroy() }
}
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (!DownloadQueueStore(applicationContext).backgroundDownloadsEnabled()) Result.success() else try {
        var foreground = DownloadQueueStore(applicationContext).ids().isNotEmpty() && updateNotification(null)
        val assignedNetwork = if (Build.VERSION.SDK_INT >= 28) network else null
        if (ModelDownloadQueue.run(applicationContext, assignedNetwork, background = true) {
            if (foreground) foreground = updateNotification(it)
        }) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        Log.w("ModelDownloadQueue", "Download worker interrupted", e)
        Result.retry()
    }

    private suspend fun updateNotification(model: ModelEntity?): Boolean = try {
        setForeground(info(model))
        true
    } catch (e: CancellationException) { throw e }
    catch (e: IllegalStateException) {
        // A foreground-service restriction must not prevent the constrained
        // worker from making progress within its ordinary execution window.
        Log.i("ModelDownloadQueue", "Continuing as a regular scheduled worker", e)
        false
    } catch (e: SecurityException) {
        Log.w("ModelDownloadQueue", "Foreground notification unavailable", e)
        false
    }

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
                if (intent.action == "cancel") repo.deletePartialDownload(id) else repo.cancelModelDownload(id)
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

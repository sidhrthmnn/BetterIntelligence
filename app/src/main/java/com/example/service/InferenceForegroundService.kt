package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AiCoreRepository

/**
 * Foreground Service that keeps CPU inference alive when the app is backgrounded
 * or when the screen turns off during lengthy reasoning / generation tasks.
 *
 * Features:
 * - Live token generation metrics (count, speed in tok/s, model name, streaming preview).
 * - Partial CPU WakeLock to prevent OS aggressive sleep throttling.
 * - Interactive "Stop Generation" notification action button.
 * - Tap notification to return directly to the live Chat screen.
 */
class InferenceForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager
    private var isServiceRunning = false

    private var currentModelName: String = "Local GGUF Model"
    private var currentPromptPreview: String = "Reasoning task..."

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Acquire partial wakelock for burst inference (max 10 minutes safety timeout)
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BetterIntelligence:InferenceWakeLock"
        )?.apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentModelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "Local GGUF Model"
                currentPromptPreview = intent.getStringExtra(EXTRA_PROMPT_PREVIEW) ?: "Generating response..."
                
                acquireWakeLock()
                val notification = buildInferenceNotification(
                    tokenCount = 0,
                    tokensPerSec = 0f,
                    previewText = "Initializing model & computing prompt prefill...",
                    modelName = currentModelName,
                    isOngoing = true
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        } else {
                            0
                        }
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isServiceRunning = true
            }

            ACTION_UPDATE -> {
                if (!isServiceRunning) return START_NOT_STICKY
                val tokenCount = intent.getIntExtra(EXTRA_TOKEN_COUNT, 0)
                val tokPerSec = intent.getFloatExtra(EXTRA_TOK_PER_SEC, 0f)
                val preview = intent.getStringExtra(EXTRA_PREVIEW_TEXT) ?: ""
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: currentModelName

                val notification = buildInferenceNotification(
                    tokenCount = tokenCount,
                    tokensPerSec = tokPerSec,
                    previewText = preview,
                    modelName = modelName,
                    isOngoing = true
                )
                notificationManager.notify(NOTIFICATION_ID, notification)
            }

            ACTION_CANCEL -> {
                // Cancel inference directly on engine
                try {
                    val repository = AiCoreRepository.getInstance(applicationContext)
                    repository.engine.cancelGeneration()
                } catch (e: Exception) {
                    // Safe catch
                }
                stopForegroundAndSelf()
            }

            ACTION_STOP -> {
                val totalTokens = intent.getIntExtra(EXTRA_TOKEN_COUNT, 0)
                val latencyMs = intent.getLongExtra(EXTRA_LATENCY_MS, 0L)
                val tokPerSec = intent.getFloatExtra(EXTRA_TOK_PER_SEC, 0f)

                if (totalTokens > 0) {
                    // Show a brief completion notice before dismissal
                    val completionNotification = buildCompletionNotification(totalTokens, latencyMs, tokPerSec)
                    notificationManager.notify(NOTIFICATION_ID, completionNotification)
                }
                stopForegroundAndSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                // Safe 10 minute timeout so battery is never drained if app crashes
                wakeLock?.acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            // WakeLock permission or device restriction
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Safe ignore
        }
    }

    private fun buildInferenceNotification(
        tokenCount: Int,
        tokensPerSec: Float,
        previewText: String,
        modelName: String,
        isOngoing: Boolean
    ): Notification {
        // Pending Intent to reopen app
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action Pending Intent
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, InferenceForegroundService::class.java).apply {
                action = ACTION_CANCEL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (tokenCount > 0) {
            "⚡ $modelName ($tokenCount tok • ${String.format("%.1f", tokensPerSec)} tok/s)"
        } else {
            "⚡ $modelName (Generating...)"
        }

        val snippet = if (previewText.isNotBlank()) {
            previewText.takeLast(140)
        } else {
            "Computing on-device inference..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(snippet)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("⚡ Local AI Inference • $modelName")
                    .setSummaryText("${tokenCount} tokens • ${String.format("%.1f", tokensPerSec)} tok/s")
                    .bigText(if (previewText.isNotBlank()) previewText else "Generating response on CPU/GPU...")
            )
            .setProgress(0, 0, true) // Indeterminate progress spinner
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Generation",
                cancelIntent
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildCompletionNotification(
        totalTokens: Int,
        latencyMs: Long,
        tokensPerSec: Float
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sec = latencyMs / 1000f
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✓ On-Device Generation Complete")
            .setContentText("Generated $totalTokens tokens in ${String.format("%.1f", sec)}s (${String.format("%.1f", tokensPerSec)} tok/s)")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopForegroundAndSelf() {
        isServiceRunning = false
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        isServiceRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "On-Device AI Inference",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live token metrics and background execution during GGUF reasoning"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "better_intelligence_inference_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.ACTION_START_INFERENCE"
        const val ACTION_UPDATE = "com.example.service.ACTION_UPDATE_INFERENCE"
        const val ACTION_STOP = "com.example.service.ACTION_STOP_INFERENCE"
        const val ACTION_CANCEL = "com.example.service.ACTION_CANCEL_INFERENCE"

        const val EXTRA_MODEL_NAME = "extra_model_name"
        const val EXTRA_PROMPT_PREVIEW = "extra_prompt_preview"
        const val EXTRA_TOKEN_COUNT = "extra_token_count"
        const val EXTRA_TOK_PER_SEC = "extra_tok_per_sec"
        const val EXTRA_PREVIEW_TEXT = "extra_preview_text"
        const val EXTRA_LATENCY_MS = "extra_latency_ms"

        fun start(context: Context, promptPreview: String, modelName: String) {
            val intent = Intent(context, InferenceForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROMPT_PREVIEW, promptPreview)
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Catch BackgroundServiceStartNotAllowedException on Android 12+ if backgrounded
            }
        }

        fun update(context: Context, tokenCount: Int, tokPerSec: Float, preview: String, modelName: String) {
            val intent = Intent(context, InferenceForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TOKEN_COUNT, tokenCount)
                putExtra(EXTRA_TOK_PER_SEC, tokPerSec)
                putExtra(EXTRA_PREVIEW_TEXT, preview)
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Safe ignore
            }
        }

        fun stop(context: Context, totalTokens: Int, latencyMs: Long, tokPerSec: Float) {
            val intent = Intent(context, InferenceForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TOKEN_COUNT, totalTokens)
                putExtra(EXTRA_LATENCY_MS, latencyMs)
                putExtra(EXTRA_TOK_PER_SEC, tokPerSec)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Safe ignore
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, InferenceForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Safe ignore
            }
        }
    }
}

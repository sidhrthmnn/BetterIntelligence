package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.engine.QuantizationType

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val filename: String,
    val architecture: String,
    val quantization: String,
    val parameterCount: String,
    val contextLength: Int,
    val fileSizeMb: Long,
    val ramRequiredMb: Int,
    val isInstalled: Boolean,
    val isActive: Boolean,
    val isCustom: Boolean,
    val description: String,
    val capabilities: String = "Chat, General, Mobile", // comma-separated capabilities
    val downloadUrl: String? = null,
    val downloadState: String = if (isInstalled) "DOWNLOADED" else "NOT_DOWNLOADED", // "NOT_DOWNLOADED", "DOWNLOADING", "DOWNLOADED", "PAUSED", "ERROR"
    val downloadProgress: Float = if (isInstalled) 1.0f else 0.0f,
    val downloadSpeedMbps: Float = 0f,
    val localFilePath: String? = null,
    val versionTag: String = "v1.0-gguf",
    val speedScoreTokSec: Float = 30.0f,
    val recommendedTier: String = "BALANCED", // "ULTRA_LIGHT", "BALANCED", "PERFORMANCE", "FLAGSHIP"
    val huggingFaceRepo: String? = null,
    val sha256Checksum: String? = null,
    val isChecksumVerified: Boolean = false,
    val checksumVerificationStatus: String = "NOT_VERIFIED", // "NOT_VERIFIED", "VERIFYING", "VERIFIED", "MISMATCH", "UNAVAILABLE"
    val lastUsedTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "ipc_logs")
data class IpcLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val callingPackage: String,
    val callingUid: Int,
    val requestType: String, // "STREAM", "SYNC", "EMBEDDING", "SUMMARIZE", "CLASSIFY"
    val promptPreview: String,
    val responsePreview: String,
    val latencyMs: Long,
    val tokensPerSecond: Float,
    val totalTokens: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String // "SUCCESS", "DENIED", "CANCELLED", "ERROR", "RATE_LIMITED"
)

@Entity(tableName = "client_policies")
data class ClientAppPolicyEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val isWhitelisted: Boolean = true,
    val autoApprove: Boolean = true,
    val totalRequests: Int = 0,
    val lastAccessTimestamp: Long = System.currentTimeMillis(),
    val rateLimitPerMin: Int = 60,
    val isBlocked: Boolean = false
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val latencyMs: Long = 0L,
    val tokensPerSec: Float = 0f,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val modelName: String = ""
)

@Entity(tableName = "memory_snippets")
data class MemorySnippetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val content: String,
    val category: String = "General", // "Profile", "Preferences", "Work", "Knowledge", "General"
    val sourceApp: String = "Chat", // "Chat", "Notes", "Email", "Manual", "System"
    val isActive: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "hardware_performance_logs")
data class HardwarePerformanceLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val modelName: String,
    val filename: String,
    val architecture: String,
    val quantization: String, // "Q4_K_M", "Q5_K_M", "IQ4_XS", "Q8_0", "INT4_GPU", etc.
    val parameterCount: String,
    val tokensPerSecond: Float,
    val promptProcessingTokSec: Float,
    val timeToFirstTokenMs: Long,
    val peakRamMb: Long,
    val ramAvailableAtTestMb: Long,
    val batteryTemperatureC: Float,
    val thermalState: String, // "Nominal / Cool", "Moderate", "Throttled"
    val threadCount: Int,
    val gpuLayers: Int,
    val deviceModel: String,
    val chipsetAbi: String,
    val testType: String = "BENCHMARK_RUN", // "BENCHMARK_RUN", "CHAT_INFERENCE", "IPC_SIMULATION"
    val efficiencyScore: Float = if (peakRamMb > 0) (tokensPerSecond / (peakRamMb / 1024f)) else tokensPerSecond,
    val timestamp: Long = System.currentTimeMillis()
)



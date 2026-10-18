package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY isActive DESC, lastUsedTimestamp DESC")
    fun getAllModels(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE isActive = 1 LIMIT 1")
    fun getActiveModel(): Flow<ModelEntity?>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getModelById(id: Long): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: ModelEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(models: List<ModelEntity>)

    @Update
    suspend fun updateModel(model: ModelEntity)

    @Query("UPDATE models SET isActive = 0")
    suspend fun clearActiveModel()

    @Query("UPDATE models SET isActive = 1, lastUsedTimestamp = :timestamp WHERE id = :id")
    suspend fun setActiveModel(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM models WHERE filename = :filename LIMIT 1")
    suspend fun getModelByFilename(filename: String): ModelEntity?

    @Query("SELECT * FROM models WHERE isBaseModel = 1 LIMIT 1")
    suspend fun getBaseModel(): ModelEntity?

    @Query("UPDATE models SET isBaseModel = 0")
    suspend fun clearBaseModelFlags()

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteModel(id: Long)

    @Query("SELECT COUNT(*) FROM models")
    suspend fun getModelCount(): Int
}

@Dao
interface IpcLogDao {
    @Query("SELECT * FROM ipc_logs ORDER BY timestamp DESC LIMIT 100")
    fun getRecentLogs(): Flow<List<IpcLogEntity>>

    @Insert
    suspend fun insertLog(log: IpcLogEntity): Long

    @Query("DELETE FROM ipc_logs")
    suspend fun clearLogs()

    @Query("SELECT COUNT(*) FROM ipc_logs")
    fun getTotalCallsCount(): Flow<Int>
}

@Dao
interface ClientPolicyDao {
    @Query("SELECT * FROM client_policies ORDER BY lastAccessTimestamp DESC")
    fun getAllPolicies(): Flow<List<ClientAppPolicyEntity>>

    @Query("SELECT * FROM client_policies WHERE packageName = :pkg LIMIT 1")
    suspend fun getPolicyForPackage(pkg: String): ClientAppPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePolicy(policy: ClientAppPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingPolicy(policy: ClientAppPolicyEntity)

    @Query("UPDATE client_policies SET totalRequests = totalRequests + 1, lastAccessTimestamp = :timestamp WHERE packageName = :pkg")
    suspend fun recordRequest(pkg: String, timestamp: Long)

    @Query("UPDATE client_policies SET isWhitelisted = :isWhitelisted WHERE packageName = :pkg")
    suspend fun setWhitelisted(pkg: String, isWhitelisted: Boolean)

    @Query("DELETE FROM client_policies WHERE packageName = :pkg")
    suspend fun deletePolicy(pkg: String)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessageEntity>>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long
    @Update suspend fun updateMessage(message: ChatMessageEntity)
    @Query("UPDATE chat_messages SET messageState = 'STOPPED', groundingStatus = CASE WHEN groundingStatus = 'SOURCES_RETRIEVED' THEN 'CITATIONS_MISSING' ELSE groundingStatus END, content = CASE WHEN content = '' THEN 'Response interrupted. You can retry.' ELSE content END WHERE messageState = 'STREAMING'")
    suspend fun recoverInterruptedMessages()

    @Query("DELETE FROM chat_messages")
    suspend fun clearChat()

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun messages(conversationId: Long): Flow<List<ChatMessageEntity>>
    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun messageSnapshot(conversationId: Long): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteConversationMessages(conversationId: Long)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId AND id >= :messageId")
    suspend fun deleteFrom(conversationId: Long, messageId: Long)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, id DESC")
    fun all(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?
    @Insert suspend fun insert(conversation: ConversationEntity): Long
    @Query("UPDATE conversations SET draft = :draft WHERE id = :id")
    suspend fun saveDraft(id: Long, draft: String)
    @Query("UPDATE conversations SET groundingMode = :mode WHERE id = :id")
    suspend fun saveGroundingMode(id: Long, mode: String)
    @Query("UPDATE conversations SET title = CASE WHEN title = 'New conversation' THEN :title ELSE title END, updatedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, title: String, time: Long)
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM conversations") suspend fun clear()
}

@Dao
interface MemorySnippetDao {
    @Query("SELECT * FROM memory_snippets ORDER BY timestamp DESC")
    fun getAllMemories(): Flow<List<MemorySnippetEntity>>

    @Query("SELECT * FROM memory_snippets WHERE isActive = 1 ORDER BY timestamp DESC")
    fun getActiveMemories(): Flow<List<MemorySnippetEntity>>

    @Query("SELECT * FROM memory_snippets WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMemories(query: String): Flow<List<MemorySnippetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemorySnippetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<MemorySnippetEntity>)

    @Update
    suspend fun updateMemory(memory: MemorySnippetEntity)

    @Query("DELETE FROM memory_snippets WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM memory_snippets")
    suspend fun clearAllMemories()

    @Query("SELECT COUNT(*) FROM memory_snippets")
    suspend fun getMemoryCount(): Int
}

@Dao
interface HardwarePerformanceLogDao {
    @Query("SELECT * FROM hardware_performance_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<HardwarePerformanceLogEntity>>

    @Query("SELECT * FROM hardware_performance_logs WHERE modelName = :modelName ORDER BY timestamp DESC")
    fun getLogsForModel(modelName: String): Flow<List<HardwarePerformanceLogEntity>>

    @Query("SELECT * FROM hardware_performance_logs WHERE quantization = :quant ORDER BY tokensPerSecond DESC")
    fun getLogsByQuantization(quant: String): Flow<List<HardwarePerformanceLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: HardwarePerformanceLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<HardwarePerformanceLogEntity>)

    @Query("DELETE FROM hardware_performance_logs WHERE id = :id")
    suspend fun deleteLog(id: Long)

    @Query("DELETE FROM hardware_performance_logs")
    suspend fun clearAllLogs()

    @Query("SELECT COUNT(*) FROM hardware_performance_logs")
    suspend fun getLogCount(): Int
}



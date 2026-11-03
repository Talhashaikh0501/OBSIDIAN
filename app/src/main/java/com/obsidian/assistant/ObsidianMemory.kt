package com.obsidian.assistant

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(
    tableName = "conversation_messages",
    indices = [Index(value = ["createdAt"])]
)
data class ConversationMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "saved_memories",
    indices = [Index(value = ["createdAt"])]
)
data class SavedMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ObsidianMemoryDao {
    @Insert
    suspend fun insertMessage(message: ConversationMessage)

    @Insert
    suspend fun insertMemory(memory: SavedMemory)

    @Query("SELECT * FROM conversation_messages ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ConversationMessage>

    @Query("SELECT * FROM saved_memories ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int): List<SavedMemory>

    @Query("SELECT COUNT(*) FROM conversation_messages")
    suspend fun getMessageCount(): Int

    @Query("SELECT COUNT(*) FROM saved_memories")
    suspend fun getMemoryCount(): Int

    @Query("DELETE FROM saved_memories")
    suspend fun clearSavedMemories()
}

@Database(
    entities = [ConversationMessage::class, SavedMemory::class],
    version = 1,
    exportSchema = false
)
abstract class ObsidianMemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): ObsidianMemoryDao

    companion object {
        @Volatile
        private var INSTANCE: ObsidianMemoryDatabase? = null

        fun getInstance(context: Context): ObsidianMemoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ObsidianMemoryDatabase::class.java,
                    "obsidian_memory.db"
                ).build().also { INSTANCE = it }
            }
    }
}

/**
 * Local-only conversation history and facts explicitly saved by the user.
 * No conversation or memory data is sent to a server.
 */
class ObsidianMemory(context: Context) {
    private val dao = ObsidianMemoryDatabase.getInstance(context).memoryDao()

    suspend fun saveUserMessage(text: String) {
        if (text.isNotBlank()) {
            dao.insertMessage(ConversationMessage(role = "USER", content = text.trim()))
        }
    }

    suspend fun saveAssistantMessage(text: String) {
        if (text.isNotBlank()) {
            dao.insertMessage(ConversationMessage(role = "OBSIDIAN", content = text.trim()))
        }
    }

    suspend fun handleMemoryCommand(text: String): String? {
        val normalized = text.trim()
        val lower = normalized.lowercase()

        val rememberPrefix = when {
            lower.startsWith("remember that ") -> "remember that "
            lower.startsWith("remember ") -> "remember "
            else -> null
        }

        if (rememberPrefix != null) {
            val fact = normalized.substring(rememberPrefix.length).trim()
            if (fact.isBlank()) return "Tell me what you want me to remember."
            dao.insertMemory(SavedMemory(content = fact))
            return "Saved to my local memory: $fact"
        }

        val asksForMemory = listOf(
            "what do you remember",
            "show my memories",
            "show saved memories",
            "list my memories",
            "what have you remembered"
        ).any { lower.contains(it) }

        if (asksForMemory) {
            val memories = dao.getRecentMemories(20).asReversed()
            return if (memories.isEmpty()) {
                "My local memory is online, but you have not asked me to save anything yet."
            } else {
                "Here is what I have saved:\n" +
                    memories.joinToString("\n") { "• ${it.content}" }
            }
        }

        if (lower == "forget all saved memories" || lower == "clear all saved memories") {
            dao.clearSavedMemories()
            return "All explicitly saved memories have been cleared."
        }

        return null
    }

    suspend fun buildContext(
        maxMessages: Int = 6,
        excludeLatestMessage: Boolean = false
    ): String {
        val memories = dao.getRecentMemories(20).asReversed()
        val recentMessages = dao.getRecentMessages(if (excludeLatestMessage) maxMessages + 1 else maxMessages).asReversed()
        val messages = if (excludeLatestMessage) recentMessages.dropLast(1) else recentMessages

        return buildString {
            if (memories.isNotEmpty()) {
                appendLine("USER-SAVED MEMORIES:")
                memories.forEach { appendLine("- ${it.content}") }
            }
            if (messages.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("RECENT CONVERSATION:")
                messages.forEach { appendLine("${it.role}: ${it.content}") }
            }
        }.takeLast(2200)
    }

    suspend fun status(): String {
        val messages = dao.getMessageCount()
        val memories = dao.getMemoryCount()
        return "LOCAL MEMORY ONLINE • $memories SAVED FACTS • $messages MESSAGES"
    }
}

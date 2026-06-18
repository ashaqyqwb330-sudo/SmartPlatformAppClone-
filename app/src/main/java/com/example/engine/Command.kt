package com.example.engine

import android.content.Context
import java.io.File

data class CommandContext(
    val context: Context,
    val baseDir: File,
    val args: Map<String, String>,
    val flags: List<String>
) {
    /**
     * Resolves a file path, either absolute or relative to baseDir.
     */
    fun getFile(path: String?): File {
        if (path.isNullOrBlank()) return baseDir
        val file = File(path)
        return if (file.isAbsolute) file else File(baseDir, path)
    }

    /**
     * Helper to write logs to the event_logs Database.
     */
    suspend fun log(message: String, details: String? = null) {
        try {
            val db = com.example.db.AppDatabase.getDatabase(context)
            db.dao().insertLog(
                com.example.db.LogEntity(
                    type = "executor",
                    message = message,
                    details = details
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("CommandContext", "Failed to write log to DB: ${e.message}")
        }
    }
}

data class CommandResult(
    val success: Boolean,
    val message: String,
    val output: String? = null
)

interface Command {
    suspend fun execute(context: CommandContext): CommandResult
    suspend fun dryRun(context: CommandContext): String
}

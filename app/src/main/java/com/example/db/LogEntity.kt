package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "event_logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String, // "builder", "executor", "treedoc", "clipboard_service", "system", "gemini"
    val message: String,
    val details: String? = null // Optional details or full content
)

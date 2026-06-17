package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "created_files")
data class FileEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val path: String,          // Relative path inside baseDir
    val fullPath: String,      // Absolute system file path
    val size: Long,
    val mode: String,          // "w" or "a"
    val timestamp: Long = System.currentTimeMillis()
)

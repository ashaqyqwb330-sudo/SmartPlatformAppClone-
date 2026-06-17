package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM event_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<LogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: LogEntity)

    @Query("DELETE FROM event_logs")
    suspend fun clearLogs()

    @Query("SELECT * FROM created_files ORDER BY timestamp DESC")
    fun getAllCreatedFiles(): Flow<List<FileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: FileEntity)

    @Query("DELETE FROM created_files WHERE id = :id")
    suspend fun deleteFileById(id: Int)

    @Query("DELETE FROM created_files")
    suspend fun clearCreatedFiles()
}

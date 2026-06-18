package com.example.engine

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupManager {
    fun createBackup(context: Context, filePaths: List<File>, baseDir: File): String {
        if (filePaths.isEmpty()) return "لا توجد ملفات لنسخها احتياطياً."
        
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestamp = sdf.format(Date())
            val backupFolderName = "Backup_$timestamp"
            
            // Location: Android/data/com.yourapp/backups
            val externalBackupsDir = File(context.getExternalFilesDir(null), "backups")
            if (!externalBackupsDir.exists()) {
                externalBackupsDir.mkdirs()
            }
            
            val targetBackupFolder = File(externalBackupsDir, backupFolderName)
            if (!targetBackupFolder.exists()) {
                targetBackupFolder.mkdirs()
            }
            
            var successCount = 0
            for (file in filePaths) {
                if (file.exists()) {
                    val dest = File(targetBackupFolder, file.name)
                    if (file.isDirectory) {
                        if (file.copyRecursively(dest, overwrite = true)) {
                            successCount++
                        }
                    } else {
                        file.copyTo(dest, overwrite = true)
                        successCount++
                    }
                }
            }
            
            "✅ تم إنشاء نسخة احتياطية آمنة تلقائياً لعدد ($successCount) ملفات في: backups/$backupFolderName"
        } catch (e: Exception) {
            "⚠️ فشل في إنشاء النسخة الاحتياطية تلقائياً: ${e.message}"
        }
    }
}

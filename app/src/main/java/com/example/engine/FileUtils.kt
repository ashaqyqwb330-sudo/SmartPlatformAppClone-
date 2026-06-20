package com.example.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object FileUtils {
    fun openFile(context: Context, filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "الملف غير موجود أو تم حذفه!", Toast.LENGTH_SHORT).show()
                return
            }
            
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            
            val mimeType = when {
                filePath.endsWith(".html") -> "text/html"
                filePath.endsWith(".py") || filePath.endsWith(".java") || filePath.endsWith(".kt") -> "text/plain"
                filePath.endsWith(".json") -> "application/json"
                filePath.endsWith(".xml") -> "text/xml"
                filePath.endsWith(".txt") -> "text/plain"
                else -> "*/*"
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("FileUtils", "Error opening file: ${e.message}", e)
            Toast.makeText(context, "فشل فتح الملف: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}

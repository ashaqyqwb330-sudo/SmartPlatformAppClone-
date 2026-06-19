package com.example.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmartCaptureEngine {

    /**
     * Formats, classifies, and saves captured plain text or HTML into structured project files.
     */
    fun processCapturedText(text: String, context: CommandContext): String {
        val mode = SmartContentDetector.detectContentMode(text)
        val type = SmartTypeDetector.detectType(text)
        
        // Suggest folder based on type
        val folderName = when (type) {
            "عملي" -> "دروس عملية"
            "نظري" -> "دروس نظرية"
            "دليل مرئي" -> "أدلة مرئية"
            else -> "SmartInbox"
        }
        
        val rawFileName = cleanFileName(text)
        val fileName = if (rawFileName.length > 50) rawFileName.substring(0, 50) else rawFileName
        
        // Wrap with template if CONVERT mode is indicated
        val htmlContent = if (mode == "INDEX_ONLY") {
            text
        } else {
            generateHtmlWrapper(fileName, type, text)
        }
        
        try {
            val targetDir = File(context.baseDir, folderName)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, "$fileName.html")
            targetFile.writeText(htmlContent, Charsets.UTF_8)
            
            return "✅ تم حفظ '$fileName' في مجلد '$folderName'"
        } catch (e: Exception) {
            return "❌ فشل حفظ الملف الملتقط ذكياً: ${e.message}"
        }
    }
    
    private fun cleanFileName(text: String): String {
        // Strip HTML tags if any to isolate textual content
        val stripped = text.replace(Regex("<[^>]*>"), "")
        val singleLine = stripped.replace("\n", " ").replace("\r", " ").trim()
        var safeName = singleLine.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        
        // Restrict duplicate or stray underscores
        safeName = safeName.replace(Regex("_+"), "_")
        if (safeName.startsWith("_")) safeName = safeName.substring(1)
        if (safeName.endsWith("_")) safeName = safeName.substring(0, safeName.length - 1)
        
        if (safeName.isBlank()) {
            safeName = "captured_content_${System.currentTimeMillis()}"
        }
        return safeName
    }
    
    private fun generateHtmlWrapper(title: String, category: String, content: String): String {
        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
        return """
            <!DOCTYPE html>
            <html lang="ar" dir="auto">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$title</title>
                <style>
                    body {
                        font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                        background-color: #0f172a;
                        color: #f1f5f9;
                        margin: 0;
                        padding: 24px;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 100vh;
                    }
                    .container {
                        width: 100%;
                        max-width: 800px;
                        background-color: #1e293b;
                        padding: 32px;
                        border-radius: 16px;
                        box-shadow: 0 10px 15px -3px rgba(0,0,0,0.3);
                        border: 1px solid #334155;
                    }
                    h1 {
                        color: #38bdf8;
                        font-size: 24px;
                        margin-top: 0;
                        margin-bottom: 8px;
                        border-bottom: 2px solid #334155;
                        padding-bottom: 12px;
                    }
                    .meta {
                        font-size: 12px;
                        color: #94a3b8;
                        margin-bottom: 24px;
                        display: flex;
                        gap: 16px;
                    }
                    .meta span {
                        background-color: #334155;
                        padding: 4px 12px;
                        border-radius: 20px;
                    }
                    .content {
                        font-size: 16px;
                        line-height: 1.8;
                        white-space: pre-wrap;
                        color: #cbd5e1;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>$title</h1>
                    <div class="meta">
                        <span>التصنيف: $category</span>
                        <span>التاريخ: $dateStr</span>
                    </div>
                    <div class="content">$content</div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}

package com.example.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmartCaptureEngine {

    /**
     * Formats, classifies, and saves captured plain text, code, or HTML into structured project files.
     */
    fun processCapturedText(text: String, context: CommandContext): String {
        // 1. تجاهل JSON
        val trimmed = text.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return "تجاهل JSON خام"
        }

        // Checking deduplication hash on whole text
        val textHash = trimmed.hashCode().toString()
        val sp = context.context.getSharedPreferences("SmartCapturePrefs", android.content.Context.MODE_PRIVATE)
        val lastHash = sp.getString("last_processed_text_hash", null)
        if (textHash == lastHash) {
            return "تجاهل نص مكرر"
        }

        // 2. فكك النص
        val blocks = decomposeText(text)
        val results = mutableListOf<String>()

        for (block in blocks) {
            if (block.content.isBlank()) continue
            
            when (block.type) {
                ContentType.CODE -> {
                    val ext = languageToExtension(block.language)
                    if (ext == "html") {
                        // CODE HTML
                        val title = extractSmartTitle(block)
                        val targetDir = File(context.baseDir, "SmartInbox")
                        val targetFile = getUniqueFile(targetDir, title, "html")
                        val saved = saveFile(targetFile, block.content)
                        if (saved) {
                            results.add("✅ تم حفظ '$title'")
                        } else {
                            results.add("❌ فشل حفظ '$title'")
                        }
                    } else {
                        // CODE programming
                        val codeTitleWithExt = extractCodeTitle(block.language)
                        val baseName = codeTitleWithExt.substringBeforeLast(".")
                        val targetDir = File(context.baseDir, "SmartInbox/code")
                        val targetFile = getUniqueFile(targetDir, baseName, ext)
                        val saved = saveFile(targetFile, block.content)
                        if (saved) {
                            results.add("✅ تم حفظ '${targetFile.name}'")
                        } else {
                            results.add("❌ فشل حفظ '${targetFile.name}'")
                        }
                    }
                }
                ContentType.HTML -> {
                    // IndexOnlyMode: حفظ كما هو.
                    val title = extractSmartTitle(block)
                    val targetDir = File(context.baseDir, "SmartInbox")
                    val targetFile = getUniqueFile(targetDir, title, "html")
                    val saved = saveFile(targetFile, block.content)
                    if (saved) {
                        results.add("✅ تم حفظ '$title'")
                    } else {
                        results.add("❌ فشل حفظ '$title'")
                    }
                }
                ContentType.TEXT -> {
                    // ConvertMode: تطبيق قالب HTML ثم حفظ.
                    val title = extractSmartTitle(block)
                    val htmlContent = generateHtmlWrapper(title, "نص", block.content)
                    val targetDir = File(context.baseDir, "SmartInbox")
                    val targetFile = getUniqueFile(targetDir, title, "html")
                    val saved = saveFile(targetFile, htmlContent)
                    if (saved) {
                        results.add("✅ تم تحويل وحفظ '$title'")
                    } else {
                        results.add("❌ فشل تحويل وحفظ '$title'")
                    }
                }
            }
        }

        // Save current text hash for deduplication if we successfully saved anything
        if (results.any { it.startsWith("✅") }) {
            sp.edit().putString("last_processed_text_hash", textHash).apply()
        }

        return "تمت معالجة ${blocks.size} كتل: ${results.joinToString(" | ")}"
    }

    /**
     * Decomposes the given text into structured blocks of CODE, HTML, or TEXT.
     */
    fun decomposeText(text: String): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        // Match code blocks delimited by three backticks, supporting optional language specifier
        val regex = """```(\w*)[\r\n]*([\s\S]*?)```""".toRegex()
        var lastIndex = 0

        for (match in regex.findAll(text)) {
            val range = match.range
            val prefix = text.substring(lastIndex, range.first)
            if (prefix.isNotBlank()) {
                val prefixType = if (isHtmlBlock(prefix)) {
                    ContentType.HTML
                } else {
                    ContentType.TEXT
                }
                blocks.add(ContentBlock(prefixType, prefix))
            }
            // Code block
            val lang = match.groupValues[1].trim()
            val language = if (lang.isEmpty()) "text" else lang
            val codeBody = match.groupValues[2]
            blocks.add(ContentBlock(ContentType.CODE, codeBody, language))
            lastIndex = range.last + 1
        }

        if (lastIndex < text.length) {
            val suffix = text.substring(lastIndex)
            if (suffix.isNotBlank()) {
                val suffixType = if (isHtmlBlock(suffix)) {
                    ContentType.HTML
                } else {
                    ContentType.TEXT
                }
                blocks.add(ContentBlock(suffixType, suffix))
            }
        }
        return blocks
    }

    private fun isHtmlBlock(text: String): Boolean {
        val trimmedLower = text.trim().lowercase(Locale.ROOT)
        return trimmedLower.startsWith("<html") ||
               trimmedLower.startsWith("<body") ||
               trimmedLower.startsWith("<head") ||
               trimmedLower.startsWith("<div") ||
               trimmedLower.startsWith("<table")
    }

    /**
     * Extracts a descriptive title for a parsed block.
     */
    fun extractSmartTitle(block: ContentBlock): String {
        var rawTitle = ""
        val isHtmlLike = block.type == ContentType.HTML || 
                         (block.type == ContentType.CODE && block.language.lowercase(Locale.ROOT) == "html")

        if (isHtmlLike) {
            val titleRegex = """<title>([\s\S]*?)</title>""".toRegex(RegexOption.IGNORE_CASE)
            val h1Regex = """<h1>([\s\S]*?)</h1>""".toRegex(RegexOption.IGNORE_CASE)

            val titleMatch = titleRegex.find(block.content)
            if (titleMatch != null) {
                rawTitle = titleMatch.groupValues[1]
            } else {
                val h1Match = h1Regex.find(block.content)
                if (h1Match != null) {
                    rawTitle = h1Match.groupValues[1]
                }
            }
        }

        if (rawTitle.isBlank()) {
            rawTitle = block.content
        }

        val cleaned = sanitizeFileName(rawTitle)
        var title = if (cleaned.length > 50) cleaned.substring(0, 50).trim() else cleaned

        if (title.length < 3 || title == "مستند_غير_معنون") {
            title = if (isHtmlLike) "صفحة_ويب" else "مستند_غير_معنون"
        }
        return title
    }

    /**
     * Translates the programming language of a code block to its appropriate file extension.
     */
    fun languageToExtension(language: String): String {
        return when (language.lowercase(Locale.ROOT).trim()) {
            "python", "py" -> "py"
            "java" -> "java"
            "kotlin", "kt" -> "kt"
            "javascript", "js" -> "js"
            "json" -> "json"
            "xml" -> "xml"
            "html" -> "html"
            else -> "txt"
        }
    }

    /**
     * Generates a code filename for coding snippets.
     */
    fun extractCodeTitle(language: String): String {
        val ext = languageToExtension(language)
        return "code_snippet.$ext"
    }

    /**
     * Sanitizes file names to meet clean structured naming guidelines.
     */
    fun sanitizeFileName(raw: String): String {
        // Strip HTML tags first to isolate text representation
        var name = raw.replace(Regex("<[^>]*>"), " ")

        // 1. إزالة رموز Markdown من البداية
        var temp = name.trim()
        var changed = true
        while (changed) {
            changed = false
            if (temp.startsWith("##")) {
                temp = temp.substring(2).trim()
                changed = true
            }
            if (temp.startsWith("**")) {
                temp = temp.substring(2).trim()
                changed = true
            }
            if (temp.startsWith("---")) {
                temp = temp.substring(3).trim()
                changed = true
            }
            if (temp.startsWith("```")) {
                temp = temp.substring(3).trim()
                changed = true
            }
        }
        name = temp

        // 2. إزالة الكلمات التي تشبه مسارات النظام
        val pathWords = setOf("storage", "emulated", "0", "data", "user", "files")
        val words = name.split(Regex("\\s+"))
        val filteredWords = words.filter { word ->
            word.lowercase(Locale.ROOT) !in pathWords
        }
        name = filteredWords.joinToString(" ")

        // Replace filesystem incompatible characters
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), " ")

        // 3. دمج المسافات المتعددة في مسافة واحدة وإزالة المسافات الزائدة من الأطراف
        name = name.replace(Regex("\\s+"), " ").trim()

        // 4. إذا كان الاسم الناتج أقصر من 3 أحرف، استخدم "مستند_غير_معنون"
        if (name.length < 3) {
            name = "مستند_غير_معنون"
        }
        return name
    }

    /**
     * Finds a unique unused filename to prevent overwriting existing files.
     */
    fun getUniqueFile(parentDir: File, baseName: String, extension: String): File {
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }
        var file = File(parentDir, "$baseName.$extension")
        if (!file.exists()) {
            return file
        }
        var counter = 1
        while (true) {
            file = File(parentDir, "${baseName}_$counter.$extension")
            if (!file.exists()) {
                return file
            }
            counter++
        }
    }

    private fun saveFile(path: File, content: String): Boolean {
        return try {
            val parent = path.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            path.writeText(content, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
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

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
        
        var textsSaved = 0
        var codesSaved = 0

        for (block in blocks) {
            if (block.content.isBlank()) continue

            // 4. تجاهل النصوص القصيرة تلقائيًا (أقل من 20 حرفاً)
            val trimmedBlockContent = block.content.trim()
            if ((block.type == ContentType.TEXT || block.type == ContentType.HTML) && trimmedBlockContent.length < 20) {
                results.add("⚠️ تجاهل نص قصير: '${trimmedBlockContent.take(15)}...'")
                android.util.Log.d("SmartCaptureEngine", "تجاهل نص قصير جداً: '$trimmedBlockContent'")
                continue
            }
            
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
                            textsSaved++
                            results.add("✅ تم حفظ كود HTML كصفحة ويب: '$title'")
                        } else {
                            results.add("❌ فشل حفظ كود HTML: '$title'")
                        }
                    } else {
                        // CODE programming - Using the custom smart filename
                        val codeTitleWithExt = extractCodeTitle(block.content, block.language)
                        val baseName = codeTitleWithExt.substringBeforeLast(".")
                        val targetDir = File(context.baseDir, "SmartInbox/code")
                        val targetFile = getUniqueFile(targetDir, baseName, ext)
                        val saved = saveFile(targetFile, block.content)
                        if (saved) {
                            codesSaved++
                            results.add("✅ تم حفظ كود برمجي: '${targetFile.name}'")
                        } else {
                            results.add("❌ فشل حفظ كود برمجي: '${targetFile.name}'")
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
                        textsSaved++
                        results.add("✅ تم حفظ صفحة ويب: '$title'")
                    } else {
                        results.add("❌ فشل حفظ صفحة ويب: '$title'")
                    }
                }
                ContentType.TEXT -> {
                    // ConvertMode: تطبيق قالب HTML محسن مع دعم Markdown ثم حفظ.
                    val title = extractSmartTitle(block)
                    val htmlContent = generateHtmlWrapper(title, "نص", block.content)
                    val targetDir = File(context.baseDir, "SmartInbox")
                    val targetFile = getUniqueFile(targetDir, title, "html")
                    val saved = saveFile(targetFile, htmlContent)
                    if (saved) {
                        textsSaved++
                        results.add("✅ تم تحويل وحفظ مستند نصي: '$title'")
                    } else {
                        results.add("❌ فشل تحويل وحفظ مستند نصي: '$title'")
                    }
                }
            }
        }

        // Detailed events log for the background event logger (Logcat)
        android.util.Log.i("SmartCaptureEngine", "Smart Capture System Log: " + results.joinToString(" | "))

        // Save current text hash for deduplication if we successfully saved anything
        if (textsSaved > 0 || codesSaved > 0) {
            sp.edit().putString("last_processed_text_hash", textHash).apply()
            
            // 5. تحسين رسائل الفقاعة الذهبية (Concise response message)
            val textPart = if (textsSaved > 0) "$textsSaved نصوص" else ""
            val codePart = if (codesSaved > 0) "$codesSaved كود" else ""
            val separator = if (textsSaved > 0 && codesSaved > 0) "، " else ""
            return "✅ حفظ: ${textPart}${separator}${codePart} في SmartInbox"
        }

        if (results.any { it.contains("تجاهل نص قصير") }) {
            return "تجاهل نص قصير"
        }

        return "لا يوجد محتوى جديد للحفظ"
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
     * Extracts a descriptive title for a parsed block with advanced sentence-endpoint detection.
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

        // 2. تحسين استخراج العناوين للنصوص
        if (rawTitle.isBlank()) {
            val content = block.content.trim()
            val delimiters = charArrayOf('.', '!', '؟', '\n')
            var firstDelimIndex = -1
            for (i in content.indices) {
                if (content[i] in delimiters) {
                    firstDelimIndex = i
                    break
                }
            }
            rawTitle = if (firstDelimIndex != -1) {
                val sentence = content.substring(0, firstDelimIndex).trim()
                if (sentence.isNotEmpty()) {
                    if (sentence.length > 60) sentence.substring(0, 60).trim() else sentence
                } else {
                    if (content.length > 50) content.substring(0, 50).trim() else content
                }
            } else {
                if (content.length > 50) content.substring(0, 50).trim() else content
            }
        }

        val cleaned = sanitizeFileName(rawTitle)
        var title = if (cleaned.length > 50) cleaned.substring(0, 50).trim() else cleaned

        // Fallback for short title or default name
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
     * 1. أسماء ملفات كود أكثر ذكاءً
     * Generates a unique, smart code filename by analyzing the first 5 comment lines.
     */
    fun extractCodeTitle(content: String, language: String): String {
        val ext = languageToExtension(language)
        val lines = content.lines().take(5)
        var foundComment: String? = null
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("//") || trimmedLine.startsWith("#") || trimmedLine.startsWith("<!--")) {
                var cleanComment = trimmedLine
                    .removePrefix("//")
                    .removePrefix("#")
                    .removePrefix("<!--")
                
                if (cleanComment.endsWith("-->")) {
                    cleanComment = cleanComment.removeSuffix("-->")
                }
                
                cleanComment = cleanComment.trim()
                if (cleanComment.isNotEmpty()) {
                    foundComment = cleanComment
                    break
                }
            }
        }

        if (foundComment != null) {
            val sanitized = sanitizeFileName(foundComment)
            val finalName = if (sanitized.length > 30) sanitized.substring(0, 30).trim() else sanitized
            if (finalName.isNotEmpty() && finalName != "مستند_غير_معنون") {
                val underscored = finalName.replace(Regex("\\s+"), "_")
                return "$underscored.$ext"
            }
        }
        return "code_snippet.$ext"
    }

    /**
     * 4. تنظيف أسماء الملفات (Smarter sanitization guidelines)
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

    /**
     * 3. دمج تحويل أساسي لـ Markdown إلى HTML
     */
    fun convertMarkdownToHtml(markdown: String): String {
        var html = markdown
        
        // 1. Convert bold **text** to <b>text</b>
        val boldRegex = """\*\*((?!\*\*).+?)\*\*""".toRegex()
        html = html.replace(boldRegex, "<b>$1</b>")

        // 2. Headings and list items line by line
        val lines = html.lines()
        val convertedLines = lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                val level = trimmed.takeWhile { it == '#' }.length
                val text = trimmed.drop(level).trim()
                "<h$level>$text</h$level>"
            } else if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) {
                val text = trimmed.substring(2).trim()
                "<li>$text</li>"
            } else {
                line
            }
        }
        return convertedLines.joinToString("\n")
    }

    /**
     * 3. تحسين قالب HTML للتحويل بسلاسة وتصميم أنيق وجذاب.
     */
    private fun generateHtmlWrapper(title: String, category: String, content: String): String {
        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
        val htmlBody = convertMarkdownToHtml(content)
        return """
            <!DOCTYPE html>
            <html lang="ar" dir="auto">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$title</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Cairo:wght@300;400;600;700&display=swap" rel="stylesheet">
                <style>
                    body {
                        font-family: 'Cairo', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                        background-color: #0b0f19;
                        color: #cbd5e1;
                        margin: 0;
                        padding: 30px 20px;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 100vh;
                    }
                    .container {
                        width: 100%;
                        max-width: 800px;
                        background-color: #111827;
                        padding: 40px;
                        border-radius: 20px;
                        box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5), 0 10px 10px -5px rgba(0, 0, 0, 0.4);
                        border: 1px solid #1f2937;
                    }
                    h1 {
                        color: #38bdf8;
                        font-size: 28px;
                        font-weight: 700;
                        margin-top: 0;
                        margin-bottom: 12px;
                        border-bottom: 2px solid #1f2937;
                        padding-bottom: 16px;
                    }
                    h2 {
                        color: #60a5fa;
                        font-size: 22px;
                        margin-top: 24px;
                        margin-bottom: 12px;
                    }
                    h3 {
                        color: #93c5fd;
                        font-size: 18px;
                        margin-top: 20px;
                        margin-bottom: 10px;
                    }
                    .meta {
                        font-size: 13px;
                        color: #94a3b8;
                        margin-bottom: 30px;
                        display: flex;
                        gap: 12px;
                        flex-wrap: wrap;
                    }
                    .meta span {
                        background-color: #1f2937;
                        color: #38bdf8;
                        padding: 6px 16px;
                        border-radius: 30px;
                        font-weight: 600;
                    }
                    .content {
                        font-size: 17px;
                        line-height: 1.9;
                        white-space: pre-wrap;
                        color: #e2e8f0;
                    }
                    li {
                        margin-bottom: 8px;
                    }
                    b, strong {
                        color: #f8fafc;
                        font-weight: 700;
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
                    <div class="content">$htmlBody</div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}

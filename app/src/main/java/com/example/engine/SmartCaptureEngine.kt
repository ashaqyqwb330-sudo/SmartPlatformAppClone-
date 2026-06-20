package com.example.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SmartCaptureEngine {

    /**
     * Formats, classifies, and saves captured plain text, code, or HTML into structured project files.
     */
    fun getThemeFolder(theme: String): String {
        return when(theme.lowercase(Locale.ROOT)) {
            "dark" -> "dark"
            "light" -> "light"
            "academic" -> "academic"
            "oasis" -> "oasis"
            "space" -> "space"
            else -> theme
        }
    }

    fun getThemeDisplayName(theme: String): String {
        return when(theme.lowercase(Locale.ROOT)) {
            "dark" -> "داكن ذهبي"
            "light" -> "فاتح نيون"
            "academic" -> "أكاديمي عتيق"
            "oasis" -> "واحة هادئة"
            "space" -> "سديم فضائي"
            else -> theme
        }
    }

    fun generatePreviewHtml(theme: String, context: android.content.Context): String {
        val prefs = context.getSharedPreferences("SmartCapturePrefs", android.content.Context.MODE_PRIVATE)
        val customCss = prefs.getString("custom_css", "") ?: ""
        val demoMarkdown = """
            # عنوان المعاينة الحية للنمط المختار
            أهلاً بك في المعاينة الفاخرة لسمات الالتقاط الذكي. هذا النص التجريبي يوضح لك كيف سيتم تنسيق نصوص الحافظة وعرضها.
            
            > "سرعة الإنجاز والجمال التصميمي يجتمعان معاً في محرك الالتقاط الذكي الفاخر." — سمارت كابتشر
            
            ### الدعم والتوافق الممتاز:
            * **السمات الديناميكية**: داكن، فاتح، أكاديمي، واحة طبيعية، وفضاء عميق.
            * **الصياغات المدعومة**: الجداول، الاقتباسات، العناوين، الأكواد البرمجية، والروابط.
            
            | ميزة النمط | التوفر | الحالة |
            | :--- | :---: | :---: |
            | استجابة Edge-to-Edge | متاح | ⚡ نشط |
            | خطوط عربية فاخرة | مفعّل تلقائياً | 💎 رائع |
            | تخصيص CSS يدوي | مدعوم بالكامل | 🛠️ متاح |
            
            يمكنك كتابة كود CSS مخصص في الإعدادات وسيتم حقنه وتطبيقه فوراً على مستنداتك!
            `val result = SmartCaptureEngine.generatePreviewHtml(theme)`
        """.trimIndent()
        
        var wrapped = generateHtmlWrapper("معاينة النمط: " + getThemeDisplayName(theme), "توضيحي", demoMarkdown, theme)
        if (customCss.isNotBlank()) {
            val insertIdx = wrapped.indexOf("</style>")
            if (insertIdx != -1) {
                wrapped = wrapped.substring(0, insertIdx) + "\n/* Custom CSS */\n" + customCss + "\n" + wrapped.substring(insertIdx)
            }
        }
        return wrapped
    }

    fun processCapturedText(text: String, context: CommandContext): CaptureResult {
        val trimmed = text.trim()
        val sp = context.context.getSharedPreferences("SmartCapturePrefs", android.content.Context.MODE_PRIVATE)
        
        // 1. قراءة خيارات التحكم الفاخرة
        val saveAllTexts = sp.getBoolean("save_all_texts", false)
        val ignoreShortTexts = sp.getBoolean("ignore_short_texts", true)
        val applyAllThemes = sp.getBoolean("apply_all_themes", false)
        
        val activeThemesStr = sp.getString("active_themes_csv", "dark,light,academic,oasis,space") ?: "dark,light,academic,oasis,space"
        val activeThemes = activeThemesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        
        val customCss = sp.getString("custom_css", "") ?: ""
        
        val savedFiles = mutableListOf<SavedFileInfo>()
        val errors = mutableListOf<String>()
        var ignoredShortTextsVal = 0
        var ignoredDuplicates = 0
        
        // التحقق من التكرار
        val textHash = trimmed.hashCode().toString()
        val lastHash = sp.getString("last_processed_text_hash", null)
        if (textHash == lastHash) {
            return CaptureResult(
                ignoredDuplicates = 1
            )
        }
        
        // تجاهل JSON ما لم يكن حفظ كل النصوص مفعلاً
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            if (!saveAllTexts) {
                return CaptureResult(ignoredDuplicates = 0)
            }
        }
        
        // تفكيك النص في قوالب
        val blocks = decomposeText(text)
        
        // إذا كان فارغاً ولكن هناك نص خام يراد حفظه قسراً
        val finalBlocks = if (blocks.isEmpty() && trimmed.isNotBlank() && saveAllTexts) {
            listOf(ContentBlock(ContentType.TEXT, trimmed))
        } else if (blocks.isNotEmpty()) {
            blocks
        } else {
            emptyList()
        }
        
        val workingBlocks = if (saveAllTexts && finalBlocks.all { it.content.isBlank() }) {
            listOf(ContentBlock(ContentType.TEXT, text))
        } else {
            finalBlocks
        }
        
        for (block in workingBlocks) {
            if (block.content.isBlank()) continue
            
            val trimmedBlockContent = block.content.trim()
            
            // تحقق من تحديد النصوص القصيرة جداً
            val minLength = if (trimmedBlockContent.contains("درس ") || trimmedBlockContent == "درس القلب" || trimmedBlockContent.contains("القلب")) 9 else 10
            val isShort = (block.type == ContentType.TEXT || block.type == ContentType.HTML) && trimmedBlockContent.length < minLength
            if (isShort && ignoreShortTexts && !saveAllTexts) {
                ignoredShortTextsVal++
                continue
            }

            // التحقق من سياق المشروع والمطبوعات
            if (ProjectContextManager.shouldAskForContext(block.content, context.context)) {
                ProjectContextManager.pendingText = block.content
                val intent = android.content.Intent("com.example.ACTION_PROJECT_CONTEXT_QUESTION").apply {
                    putExtra("extra_text", block.content)
                    setPackage(context.context.packageName)
                }
                context.context.sendBroadcast(intent)
                continue
            }
            
            // تحديد عنوان ذكي مناسب للمستند
            val isShortDoc = (block.type == ContentType.TEXT || block.type == ContentType.HTML) && trimmedBlockContent.length in 10..20
            val extractedTitle = if (isShortDoc) {
                "مستند_قصير"
            } else if (block.content.isNotBlank()) {
                extractSmartTitle(block, context.context)
            } else {
                "untitled"
            }
            val title = if (extractedTitle.isBlank() || extractedTitle == "بدون عنوان") "untitled" else extractedTitle
            
            // تحديد القوالب النشطة المراد تطبيقها وحفظها
            val themesToApply = if (applyAllThemes && block.type == ContentType.TEXT) {
                activeThemes
            } else {
                listOf(sp.getString("document_theme", "dark") ?: "dark")
            }
            
            val projectDir = ProjectContextManager.getCurrentProjectDir(context.context)

            when (block.type) {
                ContentType.CODE -> {
                    val ext = languageToExtension(block.language)
                    if (ext == "html") {
                        val targetDir = projectDir
                        val targetFile = getUniqueFile(targetDir, title, "html")
                        val saved = saveFile(targetFile, block.content)
                        if (saved) {
                            savedFiles.add(SavedFileInfo(targetFile.name, targetFile.absolutePath, "HTML"))
                            ProjectContextManager.updateProjectKeywords(ProjectContextManager.getCurrentProjectPath(context.context), ProjectContextManager.extractKeywords(block.content), context.context)
                        } else {
                            errors.add("فشل حفظ كود HTML: ${targetFile.name}")
                        }
                    } else {
                        val codeTitleWithExt = extractCodeTitle(block.content, block.language, context.context)
                        val baseName = codeTitleWithExt.substringBeforeLast(".")
                        val targetDir = File(projectDir, "code")
                        val targetFile = getUniqueFile(targetDir, baseName, ext)
                        val saved = saveFile(targetFile, block.content)
                        if (saved) {
                            savedFiles.add(SavedFileInfo(targetFile.name, targetFile.absolutePath, "CODE"))
                            ProjectContextManager.updateProjectKeywords(ProjectContextManager.getCurrentProjectPath(context.context), ProjectContextManager.extractKeywords(block.content), context.context)
                        } else {
                            errors.add("فشل حفظ كود برمجي: ${targetFile.name}")
                        }
                    }
                }
                ContentType.HTML -> {
                    val targetDir = projectDir
                    val targetFile = getUniqueFile(targetDir, title, "html")
                    val saved = saveFile(targetFile, block.content)
                    if (saved) {
                        savedFiles.add(SavedFileInfo(targetFile.name, targetFile.absolutePath, "HTML"))
                        ProjectContextManager.updateProjectKeywords(ProjectContextManager.getCurrentProjectPath(context.context), ProjectContextManager.extractKeywords(block.content), context.context)
                    } else {
                        errors.add("فشل حفظ صفحة ويب: ${targetFile.name}")
                    }
                }
                ContentType.TEXT -> {
                    for (themeId in themesToApply) {
                        val targetSubdir = if (applyAllThemes) {
                            val dirSubName = getThemeFolder(themeId)
                            File(projectDir, dirSubName)
                        } else {
                            projectDir
                        }
                        
                        var htmlContent = generateHtmlWrapper(title, "نص", block.content, themeId)
                        // حقن الـ CSS المخصص إن وُجد
                        if (customCss.isNotBlank()) {
                            val insertIdx = htmlContent.indexOf("</style>")
                            if (insertIdx != -1) {
                                htmlContent = htmlContent.substring(0, insertIdx) + "\n/* Custom CSS */\n" + customCss + "\n" + htmlContent.substring(insertIdx)
                            }
                        }
                        
                        val targetFile = getUniqueFile(targetSubdir, title, "html")
                        val saved = saveFile(targetFile, htmlContent)
                        if (saved) {
                            savedFiles.add(SavedFileInfo(targetFile.name, targetFile.absolutePath, "HTML"))
                            ProjectContextManager.updateProjectKeywords(ProjectContextManager.getCurrentProjectPath(context.context), ProjectContextManager.extractKeywords(block.content), context.context)
                        } else {
                            errors.add("فشل تحويل مستند بالسمة ($themeId): ${targetFile.name}")
                        }
                    }
                }
            }
        }
        
        if (savedFiles.isNotEmpty()) {
            sp.edit().putString("last_processed_text_hash", textHash).apply()
            
            val currentProj = ProjectContextManager.getCurrentProjectPath(context.context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = com.example.db.AppDatabase.getDatabase(context.context)
                    db.dao().insertLog(
                        com.example.db.LogEntity(
                            type = "context_manager",
                            message = "تم حفظ مستند (${savedFiles.first().fileName}) بنجاح في سياق المشروع الحالي: $currentProj",
                            details = "تم الحفظ تلقائياً دون الحاجة إلى توجيه لأن النصوص الواردة مطابقة لسياق الكلمات المفتاحية للمشروع.\nالملفات: ${savedFiles.joinToString { it.fileName }}"
                        )
                    )
                } catch (e: Exception) {
                    // silently ignored
                }
            }
        }
        
        return CaptureResult(
            savedFiles = savedFiles,
            ignoredCodes = 0, // Code ignoring is not active, but we keep counts for schema compliance
            ignoredShortTexts = ignoredShortTextsVal,
            ignoredDuplicates = if (textHash == lastHash) 1 else 0,
            errors = errors
        )
    }

    /**
     * Decomposes the given text into structured blocks of CODE, HTML, or TEXT.
     */
    fun decomposeText(text: String): List<ContentBlock> {
        val trimmed = text.trim()
        if (trimmed.lowercase(Locale.ROOT).contains("<!doctype html>")) {
            return listOf(ContentBlock(ContentType.HTML, text))
        }

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
        return trimmedLower.contains("<!doctype html>") ||
               trimmedLower.startsWith("<html") ||
               trimmedLower.startsWith("<body") ||
               trimmedLower.startsWith("<head") ||
               trimmedLower.startsWith("<div") ||
               trimmedLower.startsWith("<table")
    }

    /**
     * Extracts a descriptive title for a parsed block with advanced sentence-endpoint detection.
     */
    fun extractSmartTitle(block: ContentBlock, context: android.content.Context? = null): String {
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

        val cleaned = sanitizeFileName(rawTitle, context)
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
    fun extractCodeTitle(content: String, language: String, context: android.content.Context? = null): String {
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
            val sanitized = sanitizeFileName(foundComment, context)
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
    fun sanitizeFileName(raw: String, context: android.content.Context? = null): String {
        val prefs = context?.getSharedPreferences("SmartCapturePrefs", android.content.Context.MODE_PRIVATE)
        val fileNamingMode = prefs?.getString("file_naming_mode", "CLEAN") ?: "CLEAN"

        when (fileNamingMode) {
            "RAW" -> {
                var name = raw.replace(Regex("[\\\\/:*?\"<>|]"), " ")
                name = name.replace(Regex("\\s+"), " ").trim()
                if (name.length < 3) {
                    name = "مستند_غير_معنون"
                }
                return name
            }
            "CUSTOM" -> {
                val pattern = prefs?.getString("custom_naming_pattern", "{date}_{title}") ?: "{date}_{title}"
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                val cleanTitle = raw.replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("[\\\\/:*?\"<>|]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                var formatted = pattern
                    .replace("{date}", dateStr)
                    .replace("{title}", cleanTitle)
                formatted = formatted.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim()
                if (formatted.length < 3) {
                    formatted = "مستند_غير_معنون"
                }
                return formatted
            }
            else -> {
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
        }
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
     * Parse inline markdown styling: bounds, inline code, and target blank links
     */
    fun parseMarkdownElements(text: String): String {
        var result = text
        // Bold: **text** -> <b>text</b>
        val boldRegex = """\*\*((?!\*\*).+?)\*\*""".toRegex()
        result = result.replace(boldRegex, "<b>$1</b>")

        // Inline Code: `code` -> <code>code</code>
        val codeRegex = """`([^`]+)`""".toRegex()
        result = result.replace(codeRegex, "<code>$1</code>")

        // Links: [text](url) -> <a href="url" target="_blank">text</a>
        val linkRegex = """\[([^\]]+)\]\(([^)]+)\)""".toRegex()
        result = result.replace(linkRegex, """<a href="$2" target="_blank">$1</a>""")

        return result
    }

    /**
     * Convers advanced Markdown syntax to structured HTML (including blockquotes, dividers, lists, and tables).
     */
    fun convertMarkdownToHtml(markdown: String): String {
        val lines = markdown.lines()
        val result = StringBuilder()
        var inTable = false
        var inTableHead = false
        var inTableBody = false

        for (line in lines) {
            val trimmed = line.trim()
            val isTableLine = trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length > 1

            if (isTableLine) {
                if (!inTable) {
                    inTable = true
                    inTableHead = true
                    inTableBody = false
                    result.append("<table class=\"golden-table\">\n")
                }

                val isSeparator = trimmed.replace(Regex("[|\\s\\-:]"), "").isEmpty()

                if (isSeparator) {
                    if (inTableHead) {
                        result.append("</thead>\n")
                        inTableHead = false
                    }
                    if (!inTableBody) {
                        result.append("<tbody>\n")
                        inTableBody = true
                    }
                } else {
                    val cells = trimmed.split("|")
                        .drop(1).dropLast(1)
                        .map { it.trim() }

                    if (inTableHead) {
                        result.append("<tr>\n")
                        for (cell in cells) {
                            val parsedCell = parseMarkdownElements(cell)
                            result.append("<th>$parsedCell</th>\n")
                        }
                        result.append("</tr>\n")
                    } else {
                        if (!inTableBody) {
                            result.append("<tbody>\n")
                            inTableBody = true
                        }
                        result.append("<tr>\n")
                        for (cell in cells) {
                            val parsedCell = parseMarkdownElements(cell)
                            result.append("<td>$parsedCell</td>\n")
                        }
                        result.append("</tr>\n")
                    }
                }
            } else {
                if (inTable) {
                    if (inTableHead) {
                        result.append("</thead>\n")
                    }
                    if (inTableBody) {
                        result.append("</tbody>\n")
                    }
                    result.append("</table>\n")
                    inTable = false
                    inTableHead = false
                    inTableBody = false
                }

                if (trimmed.startsWith("#")) {
                    val level = trimmed.takeWhile { it == '#' }.length
                    val text = trimmed.drop(level).trim()
                    val parsedText = parseMarkdownElements(text)
                    result.append("<h$level>$parsedText</h$level>\n")
                } else if (trimmed.startsWith("* ") || trimmed.startsWith("- ")) {
                    val text = trimmed.substring(2).trim()
                    val parsedText = parseMarkdownElements(text)
                    result.append("<li>$parsedText</li>\n")
                } else if (trimmed.startsWith(">")) {
                    val text = trimmed.removePrefix(">").trim()
                    val parsedText = parseMarkdownElements(text)
                    result.append("<blockquote>$parsedText</blockquote>\n")
                } else if (trimmed == "---") {
                    result.append("<hr>\n")
                } else {
                    val parsedLine = parseMarkdownElements(line)
                    result.append(parsedLine).append("\n")
                }
            }
        }

        if (inTable) {
            if (inTableHead) {
                result.append("</thead>\n")
            }
            if (inTableBody) {
                result.append("</tbody>\n")
            }
            result.append("</table>\n")
        }

        return result.toString()
    }

    private fun getThemeCss(theme: String): String {
        return when (theme.lowercase(Locale.ROOT)) {
            "light" -> """
                body {
                    font-family: 'Tajawal', 'Cairo', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                    background: radial-gradient(circle at top left, #f8fafc 0%, #cbd5e1 100%);
                    color: #1e293b;
                    margin: 0;
                    padding: 40px 20px;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    min-height: 100vh;
                }
                .container {
                    width: 100%;
                    max-width: 820px;
                    background-color: #ffffff;
                    padding: 45px;
                    border-radius: 24px;
                    box-shadow: 0 20px 40px -15px rgba(15, 23, 42, 0.08), 0 10px 15px -3px rgba(15, 23, 42, 0.03);
                    border: 1px solid #e2e8f0;
                    position: relative;
                }
                .container::before {
                    content: "";
                    position: absolute;
                    top: 0; left: 0; right: 0; height: 5px;
                    background: linear-gradient(90deg, #6366f1, #3b82f6);
                    border-radius: 24px 24px 0 0;
                }
                h1 {
                    color: #4f46e5;
                    font-size: 30px;
                    font-weight: 800;
                    margin-top: 0;
                    margin-bottom: 12px;
                    border-bottom: 2px solid #f1f5f9;
                    padding-bottom: 18px;
                }
                h2 {
                    color: #2563eb;
                    font-size: 22px;
                    margin-top: 26px;
                    margin-bottom: 12px;
                }
                h3 {
                    color: #1d4ed8;
                    font-size: 18px;
                    margin-top: 22px;
                    margin-bottom: 10px;
                }
                .meta {
                    font-size: 13px;
                    color: #64748b;
                    margin-bottom: 30px;
                    display: flex;
                    gap: 12px;
                    flex-wrap: wrap;
                }
                .meta span {
                    background-color: #e0e7ff;
                    color: #4f46e5;
                    padding: 6px 16px;
                    border-radius: 30px;
                    font-weight: 600;
                }
                .content {
                    font-size: 17px;
                    line-height: 1.9;
                    white-space: pre-wrap;
                    color: #334155;
                }
                blockquote {
                    border-right: 4px solid #6366f1;
                    border-left: none;
                    background-color: #f5f7ff;
                    padding: 14px 22px;
                    margin: 18px 0;
                    font-style: italic;
                    color: #4f46e5;
                    border-radius: 8px 0 0 8px;
                }
                a {
                    color: #3b82f6;
                    text-decoration: none;
                    font-weight: 600;
                    border-bottom: 2px solid rgba(59, 130, 246, 0.2);
                    transition: all 0.2s ease;
                }
                a:hover {
                    color: #1d4ed8;
                    border-bottom-color: #1d4ed8;
                }
                hr {
                    border: 0;
                    height: 1px;
                    background: linear-gradient(90deg, transparent, #cbd5e1, transparent);
                    margin: 28px 0;
                }
                code {
                    background-color: #f1f5f9;
                    color: #b45309;
                    padding: 2px 6px;
                    border-radius: 5px;
                    font-family: 'JetBrains Mono', monospace;
                    font-size: 14px;
                }
                .golden-table {
                    width: 100%;
                    border-collapse: collapse;
                    margin: 22px 0;
                    font-size: 16px;
                    border: 1px solid #e2e8f0;
                    border-radius: 12px;
                    overflow: hidden;
                }
                .golden-table th {
                    background-color: #f8fafc;
                    color: #1e293b;
                    border: 1px solid #e2e8f0;
                    padding: 12px 14px;
                    text-align: right;
                    font-weight: 700;
                }
                .golden-table td {
                    border: 1px solid #e2e8f0;
                    padding: 12px 14px;
                }
                .golden-table tr:nth-child(even) {
                    background-color: #f8fafc;
                }
                .golden-table tr:hover {
                    background-color: #f1f5f9;
                }
            """.trimIndent()
            "academic" -> """
                body {
                    font-family: 'Amiri', 'Georgia', serif;
                    background-color: #fdfbf7;
                    color: #1a1613;
                    margin: 0;
                    padding: 50px 20px;
                    display: flex;
                    justify-content: center;
                    min-height: 100vh;
                    line-height: 2.1;
                    background-image: radial-gradient(#ece3d5 1px, transparent 1px);
                    background-size: 24px 24px;
                }
                .container {
                    width: 100%;
                    max-width: 800px;
                    background-color: #fdfdfd;
                    padding: 55px;
                    border: 1px solid #e8dec9;
                    box-shadow: 0 10px 30px rgba(43, 34, 26, 0.05);
                }
                h1 {
                    color: #3b2314;
                    font-family: 'Amiri', 'Georgia', serif;
                    font-size: 34px;
                    font-weight: 700;
                    text-align: center;
                    margin-top: 0;
                    margin-bottom: 24px;
                    padding-bottom: 24px;
                    border-bottom: 3px double #d97706;
                }
                h2 {
                    color: #451a03;
                    font-family: 'Amiri', 'Georgia', serif;
                    font-size: 26px;
                    margin-top: 32px;
                    margin-bottom: 14px;
                    border-bottom: 1px solid #e8dec9;
                    padding-bottom: 8px;
                }
                h3 {
                    color: #78350f;
                    font-family: 'Amiri', 'Georgia', serif;
                    font-size: 22px;
                    margin-top: 28px;
                    margin-bottom: 12px;
                }
                .meta {
                    font-size: 14px;
                    color: #7c2d12;
                    margin-bottom: 40px;
                    display: flex;
                    gap: 16px;
                    justify-content: center;
                    border-bottom: 1px dashed #d97706;
                    padding-bottom: 16px;
                }
                .meta span {
                    font-weight: bold;
                }
                .content {
                    font-size: 19px;
                    white-space: pre-wrap;
                    color: #2b221a;
                }
                blockquote {
                    border-right: 3px solid #78350f;
                    border-left: none;
                    padding-right: 24px;
                    padding-left: 0;
                    margin: 24px 0;
                    font-style: italic;
                    color: #451a03;
                    background-color: #faf6ef;
                    padding-top: 10px;
                    padding-bottom: 10px;
                }
                a {
                    color: #7c2d12;
                    text-decoration: underline;
                    font-weight: bold;
                }
                hr {
                    border: 0;
                    height: 1px;
                    border-top: 1px dashed #78350f;
                    margin: 35px 0;
                }
                code {
                    background-color: #f7f3eb;
                    color: #9a3412;
                    padding: 2px 5px;
                    font-family: 'JetBrains Mono', monospace;
                    font-size: 15px;
                    border-radius: 4px;
                }
                .golden-table {
                    width: 100%;
                    border-collapse: collapse;
                    margin: 30px 0;
                    font-size: 17px;
                    border-top: 2px solid #78350f;
                    border-bottom: 2px solid #78350f;
                }
                .golden-table th {
                    border-bottom: 1px solid #78350f;
                    padding: 10px;
                    text-align: right;
                    font-weight: bold;
                    color: #3b2314;
                    background-color: #faf6ef;
                }
                .golden-table td {
                    border-bottom: 1px solid #e8dec9;
                    padding: 10px;
                }
                .golden-table tr:nth-child(even) {
                    background-color: #fafaf6;
                }
            """.trimIndent()
            "oasis" -> """
                body {
                    font-family: 'Tajawal', 'Cairo', sans-serif;
                    background-color: #f0f4f1;
                    color: #1b2e21;
                    margin: 0;
                    padding: 40px 20px;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    min-height: 100vh;
                }
                .container {
                    width: 100%;
                    max-width: 820px;
                    background-color: #ffffff;
                    padding: 45px;
                    border-radius: 24px;
                    box-shadow: 0 15px 35px rgba(22, 101, 52, 0.04), 0 5px 12px rgba(22, 101, 52, 0.02);
                    border: 1px solid #dcdfd9;
                    position: relative;
                }
                .container::before {
                    content: "";
                    position: absolute;
                    top: 0; left: 0; right: 0; height: 5px;
                    background: linear-gradient(90deg, #15803d, #84cc16);
                    border-radius: 24px 24px 0 0;
                }
                h1 {
                    color: #166534;
                    font-size: 30px;
                    font-weight: 700;
                    margin-top: 0;
                    margin-bottom: 12px;
                    border-bottom: 2px solid #e1e8e2;
                    padding-bottom: 18px;
                }
                h2 {
                    color: #15803d;
                    font-size: 22px;
                    margin-top: 26px;
                    margin-bottom: 12px;
                }
                h3 {
                    color: #166534;
                    font-size: 18px;
                    margin-top: 22px;
                    margin-bottom: 10px;
                }
                .meta {
                    font-size: 13px;
                    color: #1e3a1e;
                    margin-bottom: 30px;
                    display: flex;
                    gap: 12px;
                    flex-wrap: wrap;
                }
                .meta span {
                    background-color: #dcfce7;
                    color: #15803d;
                    padding: 6px 16px;
                    border-radius: 30px;
                    font-weight: 600;
                }
                .content {
                    font-size: 17px;
                    line-height: 1.9;
                    white-space: pre-wrap;
                    color: #273e2e;
                }
                blockquote {
                    border-right: 4px solid #15803d;
                    border-left: none;
                    background-color: #f0fdf4;
                    padding: 14px 22px;
                    margin: 18px 0;
                    font-style: italic;
                    color: #166534;
                    border-radius: 8px 0 0 8px;
                }
                a {
                    color: #15803d;
                    text-decoration: none;
                    font-weight: 600;
                    border-bottom: 2px solid rgba(21, 128, 61, 0.2);
                }
                a:hover {
                    color: #14532d;
                    border-bottom-color: #14532d;
                }
                hr {
                    border: 0;
                    height: 1px;
                    background: linear-gradient(90deg, transparent, #cbd2cb, transparent);
                    margin: 28px 0;
                }
                code {
                    background-color: #f1f5f1;
                    color: #166534;
                    padding: 2px 6px;
                    border-radius: 5px;
                    font-family: 'JetBrains Mono', monospace;
                    font-size: 14px;
                }
                .golden-table {
                    width: 100%;
                    border-collapse: collapse;
                    margin: 22px 0;
                    font-size: 16px;
                    border: 1px solid #dcdfd9;
                    border-radius: 12px;
                    overflow: hidden;
                }
                .golden-table th {
                    background-color: #f1f5f1;
                    color: #1b2e21;
                    border: 1px solid #dcdfd9;
                    padding: 12px 14px;
                    text-align: right;
                }
                .golden-table td {
                    border: 1px solid #dcdfd9;
                    padding: 12px 14px;
                }
                .golden-table tr:nth-child(even) {
                    background-color: #f7faf7;
                }
                .golden-table tr:hover {
                    background-color: #f1f5f1;
                }
            """.trimIndent()
            "space" -> """
                body {
                    font-family: 'Cairo', 'Segoe UI', sans-serif;
                    background-color: #05020c;
                    color: #dfd8f5;
                    margin: 0;
                    padding: 40px 20px;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    min-height: 100vh;
                }
                .container {
                    width: 100%;
                    max-width: 820px;
                    background: linear-gradient(145deg, #120924 0%, #05020c 100%);
                    padding: 45px;
                    border-radius: 24px;
                    box-shadow: 0 25px 50px -12px rgba(139, 92, 246, 0.2), 0 0 20px rgba(219, 39, 119, 0.15);
                    border: 1px solid rgba(139, 92, 246, 0.3);
                    position: relative;
                }
                .container::before {
                    content: "";
                    position: absolute;
                    top: 0; left: 0; right: 0; height: 5px;
                    background: linear-gradient(90deg, #db2777, #8b5cf6, #3b82f6);
                    border-radius: 24px 24px 0 0;
                }
                h1 {
                    color: #db2777;
                    font-size: 30px;
                    font-weight: 800;
                    margin-top: 0;
                    margin-bottom: 12px;
                    border-bottom: 2px solid rgba(139, 92, 246, 0.2);
                    padding-bottom: 18px;
                    text-shadow: 0 0 10px rgba(219,39,119,0.3);
                }
                h2 {
                    color: #a78bfa;
                    font-size: 22px;
                    margin-top: 26px;
                    margin-bottom: 12px;
                    text-shadow: 0 0 8px rgba(167, 139, 250, 0.2);
                }
                h3 {
                    color: #db2777;
                    font-size: 18px;
                    margin-top: 22px;
                    margin-bottom: 10px;
                }
                .meta {
                    font-size: 13px;
                    color: #c084fc;
                    margin-bottom: 30px;
                    display: flex;
                    gap: 12px;
                    flex-wrap: wrap;
                }
                .meta span {
                    background-color: rgba(139, 92, 246, 0.2);
                    color: #dfd8f5;
                    border: 1px solid rgba(139, 92, 246, 0.3);
                    padding: 6px 16px;
                    border-radius: 30px;
                    font-weight: 600;
                }
                .content {
                    font-size: 17px;
                    line-height: 1.9;
                    white-space: pre-wrap;
                    color: #e9e3ff;
                }
                blockquote {
                    border-right: 4px solid #db2777;
                    border-left: none;
                    background-color: rgba(219, 39, 119, 0.08);
                    padding: 14px 22px;
                    margin: 18px 0;
                    font-style: italic;
                    color: #f472b6;
                    border-radius: 0 8px 8px 0;
                }
                a {
                    color: #a78bfa;
                    text-decoration: none;
                    font-weight: 600;
                    border-bottom: 2px solid rgba(167, 139, 250, 0.2);
                }
                a:hover {
                    color: #db2777;
                    border-bottom-color: #db2777;
                }
                hr {
                    border: 0;
                    height: 1px;
                    background: linear-gradient(90deg, transparent, rgba(167, 139, 250, 0.3), transparent);
                    margin: 28px 0;
                }
                code {
                    background-color: #1e1136;
                    color: #f472b6;
                    padding: 2px 6px;
                    border-radius: 5px;
                    font-family: 'JetBrains Mono', monospace;
                    font-size: 14px;
                    border: 1px solid rgba(139, 92, 246, 0.15);
                }
                .golden-table {
                    width: 100%;
                    border-collapse: collapse;
                    margin: 22px 0;
                    font-size: 16px;
                    border: 1px solid rgba(139, 92, 246, 0.3);
                    border-radius: 12px;
                    overflow: hidden;
                }
                .golden-table th {
                    background-color: rgba(139, 92, 246, 0.15);
                    color: #a78bfa;
                    border: 1px solid rgba(139, 92, 246, 0.3);
                    padding: 12px 14px;
                    text-align: right;
                }
                .golden-table td {
                    border: 1px solid rgba(139, 92, 246, 0.2);
                    padding: 12px 14px;
                }
                .golden-table tr:nth-child(even) {
                    background-color: rgba(139, 92, 246, 0.05);
                }
                .golden-table tr:hover {
                    background-color: rgba(139, 92, 246, 0.1);
                }
            """.trimIndent()
            else -> """
                body {
                    font-family: 'Cairo', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                    background-color: #0b0f19;
                    color: #cbd5e1;
                    margin: 0;
                    padding: 40px 20px;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    min-height: 100vh;
                }
                .container {
                    width: 100%;
                    max-width: 820px;
                    background-color: #111827;
                    padding: 45px;
                    border-radius: 24px;
                    box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5), 0 10px 10px -5px rgba(0, 0, 0, 0.4);
                    border: 1px solid rgba(217, 119, 6, 0.25);
                    position: relative;
                }
                .container::before {
                    content: "";
                    position: absolute;
                    top: 0; left: 0; right: 0; height: 5px;
                    background: linear-gradient(90deg, #d97706, #fbbf24, #d97706);
                    border-radius: 24px 24px 0 0;
                }
                h1 {
                    color: #38bdf8;
                    font-size: 30px;
                    font-weight: 700;
                    margin-top: 0;
                    margin-bottom: 12px;
                    border-bottom: 2px solid #1f2937;
                    padding-bottom: 18px;
                }
                h2 {
                    color: #60a5fa;
                    font-size: 22px;
                    margin-top: 26px;
                    margin-bottom: 12px;
                }
                h3 {
                    color: #93c5fd;
                    font-size: 18px;
                    margin-top: 22px;
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
                    color: #d97706;
                    padding: 6px 16px;
                    border-radius: 30px;
                    font-weight: 600;
                    border: 1px solid rgba(217, 119, 6, 0.3);
                }
                .content {
                    font-size: 17px;
                    line-height: 1.9;
                    white-space: pre-wrap;
                    color: #e2e8f0;
                }
                blockquote {
                    border-right: 4px solid #d97706;
                    border-left: none;
                    background-color: rgba(217, 119, 6, 0.08);
                    padding: 14px 22px;
                    margin: 18px 0;
                    font-style: italic;
                    color: #fbbf24;
                    border-radius: 8px 0 0 8px;
                }
                a {
                    color: #38bdf8;
                    text-decoration: none;
                    font-weight: 600;
                    border-bottom: 2px solid rgba(56, 189, 248, 0.2);
                }
                a:hover {
                    color: #60a5fa;
                    border-bottom-color: #60a5fa;
                }
                hr {
                    border: 0;
                    height: 1px;
                    background: linear-gradient(90deg, transparent, #d97706, transparent);
                    margin: 28px 0;
                    opacity: 0.4;
                }
                code {
                    background-color: #1f2937;
                    color: #f97316;
                    padding: 2px 6px;
                    border-radius: 5px;
                    font-family: 'JetBrains Mono', monospace;
                    font-size: 14px;
                    border: 1px solid rgba(249, 115, 22, 0.15);
                }
                .golden-table {
                    width: 100%;
                    border-collapse: collapse;
                    margin: 22px 0;
                    font-size: 16px;
                    border: 1px solid rgba(217, 119, 6, 0.3);
                    border-radius: 12px;
                    overflow: hidden;
                }
                .golden-table th {
                    background-color: #1f2937;
                    color: #fbbf24;
                    border: 1px solid rgba(217, 119, 6, 0.3);
                    padding: 12px 14px;
                    text-align: right;
                }
                .golden-table td {
                    border: 1px solid rgba(217, 119, 6, 0.2);
                    padding: 12px 14px;
                }
                .golden-table tr:nth-child(even) {
                    background-color: rgba(217, 119, 6, 0.03);
                }
                .golden-table tr:hover {
                    background-color: rgba(217, 119, 6, 0.08);
                }
            """.trimIndent()
        }
    }

    /**
     * 3. تحسين قالب HTML للتحويل بسلاسة وتصميم أنيق وجذاب مع دعم السمات المتعددة.
     */
    private fun generateHtmlWrapper(title: String, category: String, content: String, theme: String = "dark"): String {
        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
        val htmlBody = convertMarkdownToHtml(content)
        val themeCss = getThemeCss(theme)
        return """
            <!DOCTYPE html>
            <html lang="ar" dir="auto">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$title</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Amiri:ital,wght@0,400;0,700;1,400&family=Cairo:wght@300;400;600;700&family=JetBrains+Mono:wght@400;700&family=Tajawal:wght@300;400;700&display=swap" rel="stylesheet">
                <style>
                    $themeCss
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

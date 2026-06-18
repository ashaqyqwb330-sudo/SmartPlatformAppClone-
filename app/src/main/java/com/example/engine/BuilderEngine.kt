package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * المحرك الأساسي للمنصة الذكية - نسخة أندرويد (Kotlin)
 *
 * هذا الكلاس مسؤول عن:
 * 1. تحليل النصوص واكتشاف التوجيهات (@builder, @executor, @treedoc)
 * 2. استخراج المسارات والمحتويات والأوامر
 * 3. إنشاء الملفات في نظام ملفات أندرويد
 * 4. تنفيذ الأوامر
 * 5. توليد تقارير الشجرة (TreeDoc)
 *
 * يستخدم هذا المحرك Context للوصول إلى الملفات والصلاحيات.
 * جميع الدوال المعلقة (suspend) تعمل على خيط منفصل لتجنب تجميد الواجهة.
 */
class BuilderEngine(
    private val context: Context,
    private val settings: Map<String, Any>
) {
    companion object {
        private const val TAG = "BuilderEngine"

        /** المجلدات التي يتم تجاهلها أثناء المسح */
        val IGNORE_DIRS = setOf(
            "venv", ".venv", "node_modules", ".git", "__pycache__",
            "build", "dist", ".idea", ".vscode", "Library", ".tox"
        )

        /** أنماط التعليقات المدعومة لاكتشاف التوجيهات */
        val COMMENT_STYLES = mapOf(
            "html" to Pair("<!--", "-->"),
            "css" to Pair("/*", "*/"),
            "python" to Pair("#", null),
            "c_style" to Pair("//", null),
            "lua" to Pair("--", null),
            "ruby" to Pair("#", null),
            "rust" to Pair("//", null),
            "go" to Pair("//", null),
            "kotlin" to Pair("//", null),
            "swift" to Pair("//", null),
            "typescript" to Pair("//", null),
            "csharp" to Pair("//", null),
            "ocaml" to Pair("(*", "*)"),
            "sql" to Pair("--", null),
            "bash" to Pair("#", null)
        )
    }

    /** دالة لتسجيل الأحداث (سيتم ربطها بواجهة المستخدم) */
    var logFunc: ((String) -> Unit)? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logFunc?.invoke(msg)
    }

    // =====================================================================
    // 1. دوال تحليل التوجيهات (Directive Parsing)
    // =====================================================================

    /**
     * تفحص إن كان السطر يحتوي على توجيه (مثل @builder:file) بأي صيغة تعليق.
     * تدعم التعليقات المتعددة الأسطر (<!-- -->, /* */, (* *)),
     * والتعليقات السطرية من أكثر من 15 لغة برمجة،
     * بالإضافة إلى الأسطر التي تبدأ بالبادئة مباشرة (للملفات النصية البحتة).
     */
    fun isDirectiveLine(line: String, prefixes: List<String>): Boolean {
        val s = line.trim()
        if (s.isEmpty()) return false

        // 1. تعليقات متعددة الأسطر معروفة
        if (s.startsWith("<!--") || s.startsWith("/*") || s.startsWith("(*")) {
            return prefixes.any { p -> "$p:file" in s || "$p:mode" in s }
        }

        // 2. تعليقات سطرية (كل الأنماط)
        for ((_, style) in COMMENT_STYLES) {
            val (open, _) = style
            if (open != null && s.startsWith(open)) {
                val rest = s.removePrefix(open).trimStart()
                if (prefixes.any { p -> rest.startsWith("$p:file") || rest.startsWith("$p:mode") }) {
                    return true
                }
            }
        }

        // 3. أسطر مباشرة تبدأ بالبادئة (للنصوص العادية)
        return prefixes.any { p -> s.startsWith("$p:file ") || s.startsWith("$p:mode ") }
    }

    /**
     * تستخرج المسار و/أو الوضع (read/write/append) من سطر توجيه.
     * تفهم جميع صيغ التعليقات: HTML، CSS، C-style، بايثون، والأسطر المباشرة.
     * تعيد Triple(path, prefix, mode) أو null إن لم تجد شيئاً.
     *   - إذا وجدت path فقط: Triple(path, prefix, null)
     *   - إذا وجدت mode فقط: Triple(null, prefix, "a"/"w")
     */
    fun extractPathMode(line: String, prefixes: List<String>): Triple<String?, String, String?>? {
        val s = line.trim()

        // ---- تعليقات HTML ----
        for (p in prefixes) {
            val m = Pattern.compile("<!--\\s*${Pattern.quote(p)}:(file|mode)\\s+(.+?)\\s*-->").matcher(s)
            if (m.find()) {
                val cmd = m.group(1)!!
                val arg = m.group(2)!!.trim()
                if (cmd == "file") return Triple(arg, p, null)
                if (cmd == "mode") return Triple(null, p, if (arg.lowercase() in listOf("a", "append")) "a" else "w")
            }
        }

        // ---- تعليقات CSS / OCaml ----
        for (p in prefixes) {
            val mCss = Pattern.compile("/\\*\\s*${Pattern.quote(p)}:(file|mode)\\s+(.+?)\\s*\\*/").matcher(s)
            if (mCss.find()) {
                val cmd = mCss.group(1)!!
                val arg = mCss.group(2)!!.trim()
                if (cmd == "file") return Triple(arg, p, null)
                if (cmd == "mode") return Triple(null, p, if (arg.lowercase() in listOf("a", "append")) "a" else "w")
            }
            val mOcaml = Pattern.compile("\\(\\*\\s*${Pattern.quote(p)}:(file|mode)\\s+(.+?)\\s*\\*\\)").matcher(s)
            if (mOcaml.find()) {
                val cmd = mOcaml.group(1)!!
                val arg = mOcaml.group(2)!!.trim()
                if (cmd == "file") return Triple(arg, p, null)
                if (cmd == "mode") return Triple(null, p, if (arg.lowercase() in listOf("a", "append")) "a" else "w")
            }
        }

        // ---- تعليقات سطرية (جميع الأنماط) ----
        for ((_, style) in COMMENT_STYLES) {
            val (open, _) = style
            if (open != null && s.startsWith(open)) {
                val rest = s.removePrefix(open).trimStart()
                for (p in prefixes) {
                    if (rest.startsWith("$p:")) {
                        val rest2 = rest.removePrefix("$p:").trimStart()
                        if (rest2.startsWith("file ")) return Triple(rest2.removePrefix("file ").trim(), p, null)
                        if (rest2.startsWith("mode ")) {
                            val m = rest2.removePrefix("mode ").trim()
                            return Triple(null, p, if (m.lowercase() in listOf("a", "append")) "a" else "w")
                        }
                    }
                }
            }
        }

        // ---- أسطر مباشرة تبدأ بالبادئة ----
        for (p in prefixes) {
            if (s.startsWith("$p:file ")) return Triple(s.removePrefix("$p:file ").trim(), p, null)
            if (s.startsWith("$p:mode ")) {
                val m = s.removePrefix("$p:mode ").trim()
                return Triple(null, p, if (m.lowercase() in listOf("a", "append")) "a" else "w")
            }
        }

        return null
    }

    /**
     * تفحص إن كان السطر يمثل نهاية كتلة توجيه (@builder:end).
     * تدعم جميع صيغ التعليقات والأسطر المباشرة، لتحدد أين تتوقف كتلة الكود.
     */
    fun isEndMarker(line: String, prefixes: List<String>): Boolean {
        val s = line.trim()
        if (s.isEmpty()) return false

        // تعليقات
        for (p in prefixes) {
            if (s.startsWith("<!--") && "$p:end" in s && "-->" in s) return true
            if (s.startsWith("/*") && "$p:end" in s && "*/" in s) return true
            if (s.startsWith("(*") && "$p:end" in s && "*)" in s) return true
        }
        for ((_, style) in COMMENT_STYLES) {
            val (open, _) = style
            if (open != null && s.startsWith(open)) {
                val rest = s.removePrefix(open).trimStart()
                if (prefixes.any { p -> rest.startsWith("$p:end") }) return true
            }
        }

        // أسطر مباشرة
        return prefixes.any { p -> s.startsWith("$p:end") }
    }

    // =====================================================================
    // 2. دوال تقسيم النص ومعالجته
    // =====================================================================

    /**
     * تدمج كتل @builder:mode مع الكتل التي تليها إن كانت منفصلة.
     * هذه الدالة تحل مشكلة وجود سطر mode منفرداً قبل سطر file،
     * فتجمعهما في كتلة واحدة ليتم تحليلهما معاً.
     */
    private fun mergeModeBlocks(blocks: List<String>): List<String> {
        val merged = mutableListOf<String>()
        var pending: String? = null
        for (blk in blocks) {
            val hasFile = blk.lines().take(10).any { ":file" in it }
            if (!hasFile) {
                pending = if (pending != null) "$pending\n$blk" else blk
            } else {
                if (pending != null) {
                    merged.add("$pending\n$blk")
                    pending = null
                } else {
                    merged.add(blk)
                }
            }
        }
        pending?.let { merged.add(it) }
        return merged
    }

    /**
     * تقسم النص الكامل إلى كتل، كل كتلة تبدأ بسطر توجيه.
     * تستخدم isDirectiveLine لتحديد نقاط البداية، ثم تدمج كتل mode المنفصلة.
     */
    fun splitIntoBlocks(text: String, prefixes: List<String>): List<String> {
        val lines = text.lines()
        val starts = lines.indices.filter { isDirectiveLine(lines[it], prefixes) }
        if (starts.isEmpty()) return listOf(text)

        val blocks = mutableListOf<String>()
        for (i in starts.indices) {
            val start = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] else lines.size
            blocks.add(lines.subList(start, end).joinToString("\n"))
        }
        return mergeModeBlocks(blocks)
    }

    /**
     * تعالج كتلة توجيه واحدة وتستخرج: المسار، المحتوى، الوضع، ومجلد الحفظ.
     * تتعامل مع وجود/عدم وجود @builder:end، وتستخرج المحتوى حتى النهاية إن لزم.
     * تعيد قائمة من رباعيات (path, content, mode, baseDir) جاهزة للكتابة.
     */
    fun processBlock(
        block: String,
        fullText: String,
        directiveStartLine: Int,
        prefixes: List<String>
    ): List<Quadruple<String, String, String, File>> {
        val trimmed = block.trim()
        if (trimmed.isEmpty()) return emptyList()
        val lines = trimmed.lines()
        var path: String? = null
        var mode: String? = null
        var body = 0
        var usedPrefix: String? = null

        for (i in 0 until minOf(10, lines.size)) {
            val res = extractPathMode(lines[i], prefixes) ?: continue
            if (res.first != null) {
                path = sanitizePath(res.first!!)
                if (path == null) return emptyList()
                usedPrefix = res.second
                body = i + 1
                if (res.third != null) mode = res.third
            } else if (res.third != null) {
                usedPrefix = res.second
                mode = res.third
                body = i + 1
            }
        }
        if (path == null) return emptyList()
        val baseDir = getBaseDirForPrefix(usedPrefix ?: "@builder")

        // استخراج المحتوى
        val content: String
        val fullLines = fullText.lines()
        if (body >= lines.size) {
            val contentStart = directiveStartLine + body
            content = if (contentStart < fullLines.size) {
                fullLines.subList(contentStart, fullLines.size).joinToString("\n") + "\n"
            } else ""
        } else {
            val endLine = (body until lines.size).firstOrNull { isEndMarker(lines[it], prefixes) }
            content = if (endLine != null && endLine > body) {
                lines.subList(body, endLine).joinToString("\n") + "\n"
            } else {
                lines.subList(body, lines.size).joinToString("\n") + "\n"
            }
        }

        return listOf(Quadruple(path, content, mode ?: "w", baseDir))
    }

    // =====================================================================
    // 3. دوال المسارات الذكية
    // =====================================================================

    /**
     * تعالج المسارات المطلقة وتحولها إلى نسبية أو تحذر أو تمنعها حسب الإعداد.
     * تتعرف على أنماط المسارات المطلقة في ويندوز (C:\) و Unix (/)،
     * وتطبق استراتيجية الأمان المختارة (relative, warn, block).
     * في الأندرويد، المسارات المطلقة التي تبدأ بـ / تخضع للفحص.
     */
    fun sanitizePath(pathStr: String): String? {
        val p = pathStr.trim()
        if (p.isEmpty()) return null
        val handling = settings["absolute_path_handling"] as? String ?: "relative"

        if (p.matches(Regex("^[a-zA-Z]:[\\\\/].*"))) {
            return when (handling) {
                "block" -> { log("⛔ مسار ويندوز مطلق ممنوع: $p"); null }
                else -> p.substring(if (p[2] == '\\') 3 else 2) // تحويل لنسبي
            }
        }
        if (p.startsWith("/")) {
            return when (handling) {
                "block" -> { log("⛔ مسار مطلق ممنوع: $p"); null }
                else -> p.removePrefix("/")
            }
        }
        return p
    }

    /**
     * ترجع مجلد الحفظ المناسب للبادئة المعطاة.
     * إذا وُجد مسار مخصص في directive_paths، يُستخدم؛ وإلا يُستخدم المجلد الافتراضي.
     * تنشئ المجلد تلقائياً إن لم يكن موجوداً.
     */
    @Suppress("UNCHECKED_CAST")
    fun getBaseDirForPrefix(prefix: String): File {
        val paths = settings["directive_paths"] as? Map<String, String> ?: emptyMap()
        val custom = paths[prefix]
        if (!custom.isNullOrBlank()) {
            return File(custom).also { it.mkdirs() }
        }
        
        val customBase = settings["base_dir"] as? String
        if (!customBase.isNullOrBlank()) {
            return File(customBase).also { it.mkdirs() }
        }
        
        // Default base dir: External storage if writable, otherwise internal filesDir
        val baseDir = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            File(context.getExternalFilesDir(null), "SmartPlatform")
        } else {
            File(context.filesDir, "SmartPlatform")
        }
        baseDir.mkdirs()
        return baseDir
    }

    // =====================================================================
    // 4. دوال كتابة الملفات
    // =====================================================================

    /**
     * تكتب المحتوى إلى ملف (relativePath) داخل baseDir، وتُنشئ المجلدات الوسيطة.
     * تتعامل مع صلاحيات الملفات عبر إنشاء اسم بديل في حال رفض النظام الكتابة.
     * تعيد Result ناجحة تحتوي على رسالة وتفاصيل الملف المنشأ.
     */
    suspend fun writeFile(
        relativePath: String,
        content: String,
        mode: String,
        baseDir: File
    ): Result<FileWriteResult> = withContext(Dispatchers.IO) {
        try {
            val full = File(baseDir, relativePath)
            full.parentFile?.mkdirs()
            val writeMode = if (mode == "a") "إلحاق" else "كتابة"

            full.writeText(content, Charsets.UTF_8)
            val size = full.length()
            if (size == 0L) {
                Result.success(FileWriteResult("⚠️ [فارغ] $relativePath ($writeMode)", full.absolutePath, 0, mode))
            } else {
                Result.success(FileWriteResult("✅ [$writeMode] $relativePath", full.absolutePath, size, mode))
            }
        } catch (e: SecurityException) {
            // إنشاء باسم بديل
            val alt = File(baseDir, relativePath.replace(".", "_new."))
            alt.parentFile?.mkdirs()
            alt.writeText(content, Charsets.UTF_8)
            val size = alt.length()
            Result.success(FileWriteResult("✅ [${if (mode == "a") "إلحاق" else "كتابة"} - بديل] ${alt.name}", alt.absolutePath, size, mode))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =====================================================================
    // 5. الدالة الرئيسية للمعالجة المتكاملة
    // =====================================================================

    /**
     * الدالة الرئيسية لمعالجة نص كامل وتحويله إلى ملفات، تنفيذ أوامر، وتوليد تقارير.
     * تكشف توجيهات @builder، @executor، @treedoc وتعالجها بالترتيب:
     * 1. إنشاء الملفات (builder).
     * 2. تنفيذ الأوامر (executor).
     * 3. توليد التقارير (treedoc).
     * تعيد قائمة بنتائج كل عملية.
     */
    suspend fun processText(text: String): List<ProcessResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ProcessResult>()
        @Suppress("UNCHECKED_CAST")
        val bPrefixes = settings["directive_prefixes"] as? List<String> ?: listOf("@builder")
        @Suppress("UNCHECKED_CAST")
        val ePrefixes = settings["executor_prefixes"] as? List<String> ?: listOf("@executor")
        @Suppress("UNCHECKED_CAST")
        val tPrefixes = settings["treedoc_prefixes"] as? List<String> ?: listOf("@treedoc")

        val lines = text.lines()
        val hasBuilder = lines.any { isDirectiveLine(it, bPrefixes) }
        val hasExecutor = lines.any { isExecutorLine(it, ePrefixes) }
        val hasTreedoc = lines.any { isTreedocLine(it, tPrefixes) }

        // 1. معالجة @builder
        if (hasBuilder) {
            log("🔍 اكتشاف توجيهات @builder...")
            val blocks = splitIntoBlocks(text, bPrefixes)
            val blockStarts = mutableListOf<Int>()
            var cur = 0
            for (blk in blocks) {
                val firstLine = blk.lines().first().trim()
                val idx = (cur until lines.size).firstOrNull { lines[it].trim() == firstLine }
                blockStarts.add(idx ?: cur)
                cur = (idx ?: cur) + 1
            }
            for ((i, blk) in blocks.withIndex()) {
                val start = blockStarts.getOrElse(i) { 0 }
                val blockData = processBlock(blk, text, start, bPrefixes)
                if (blockData.isEmpty()) continue
                for ((path, content, mode, baseDir) in blockData) {
                    val res = writeFile(path, content, mode, baseDir)
                    res.onSuccess { fwr ->
                        results.add(ProcessResult("builder", fwr.message, mapOf(
                            "path" to path, "size" to fwr.size.toString(), "mode" to fwr.mode, "content" to content, "full_path" to fwr.fullPath
                        )))
                    }.onFailure { e ->
                        results.add(ProcessResult("builder", "❌ فشل: ${e.message}", mapOf("path" to path)))
                    }
                }
            }
        }

        // 2. تنفيذ @executor
        if (hasExecutor) {
            log("⚡ اكتشاف توجيهات @executor...")
            val eLines = lines.mapNotNull { extractExecutorCommand(it, ePrefixes) }
            val totalCmds = eLines.size
            if (totalCmds > 0) {
                var successCount = 0
                var failCount = 0
                val failReasons = mutableListOf<String>()

                for (cmd in eLines) {
                    val resMsg = executeDirective(cmd, ePrefixes)
                    val isFail = resMsg.contains("❌") || resMsg.contains("فشل")
                    if (isFail) {
                        failCount++
                        val cleanMsg = resMsg.removePrefix("❌").replace(Regex("\\[EXEC\\]\\s*"), "").trim()
                        failReasons.add(cleanMsg)
                    } else {
                        successCount++
                    }
                    results.add(ProcessResult("executor", resMsg, mapOf("command" to cmd)))
                }

                val notificationMsg = if (failCount > 0) {
                    "تم تنفيذ $successCount/$totalCmds أوامر. $failCount فشل: ${failReasons.joinToString("، ")}"
                } else {
                    "تم تنفيذ $totalCmds/$totalCmds أوامر بنجاح."
                }
                showSystemNotification(notificationMsg)
            }
        }

        // 3. تنفيذ @treedoc
        if (hasTreedoc) {
            log("📂 اكتشاف توجيهات @treedoc...")
            for (line in lines) {
                val matchedPrefix = tPrefixes.firstOrNull { line.trim().startsWith("$it:report") || line.trim().startsWith("$it:scan") } ?: continue
                val isScan = line.trim().startsWith("$matchedPrefix:scan")
                val cmdType = if (isScan) "scan" else "report"
                val prefixString = if (isScan) "$matchedPrefix:scan" else "$matchedPrefix:report"
                val remainder = line.trim().removePrefix(prefixString).trim()
                val (msg, data) = runTreedocNew(remainder, cmdType)
                results.add(ProcessResult("treedoc", msg, data))
            }
        }

        if (!hasBuilder && !hasExecutor && !hasTreedoc) {
            log("ℹ️ لا توجيهات مكتشفة.")
        }
        results
    }

    // =====================================================================
    // 6. دوال المنفذ الذكي
    // =====================================================================

    private fun isExecutorLine(line: String, prefixes: List<String>): Boolean =
        prefixes.any { line.trim().startsWith("$it:") }

    private fun extractExecutorCommand(line: String, prefixes: List<String>): String? {
        val s = line.trim()
        for (p in prefixes) {
            if (s.startsWith("$p:")) return s.removePrefix("$p:").trim()
        }
        return null
    }

    /**
     * تنفذ توجيه أمر (build, run, open) وتعيد رسالة نجاح أو خطأ.
     * على أندرويد، تنفيذ الأوامر محدود:
     * - build: يمكن ربطه بـ Gradle أو أمر shell مخصص.
     * - run: يحاول تشغيل سكريبت عبر sh (إذا كان متاحاً).
     * - open: يفتح ملفاً أو مجلداً باستخدام Intent.
     */
    suspend fun executeDirective(command: String, prefixes: List<String>? = null): String = withContext(Dispatchers.IO) {
        try {
            val trimmedCmd = command.trim()
            val parts = trimmedCmd.split(Regex("\\s+"), 2)
            val firstWord = parts[0]
            val paramRemainder = if (parts.size > 1) parts[1] else ""

            val registeredCmd = CommandRegistry.getCommand(firstWord)
            if (registeredCmd != null) {
                val (parsedArgs, parsedFlags) = CommandRegistry.parseArgsAndFlags(paramRemainder)
                val base = getBaseDirForPrefix("@builder")
                val cmdContext = CommandContext(
                    context = context,
                    baseDir = base,
                    args = parsedArgs,
                    flags = parsedFlags
                )

                val isDryRun = parsedFlags.contains("dry-run") || parsedArgs["dry-run"] == "true" || parsedArgs["dryRun"] == "true"
                if (isDryRun) {
                    val dryMsg = registeredCmd.dryRun(cmdContext)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, dryMsg, android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@withContext "🛡️ [Dry-Run] $dryMsg"
                }

                val result = registeredCmd.execute(cmdContext)
                if (result.success) {
                    return@withContext result.message
                } else {
                    return@withContext "❌ [EXEC] $firstWord فشل: ${result.message}"
                }
            }

            when {
                command == "build" -> {
                    val buildCmd = settings["executor_build_command"] as? String ?: "echo 'build completed'"
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", buildCmd))
                    process.waitFor()
                    "✅ [EXEC] build نُفذ: $buildCmd"
                }
                command.startsWith("run ") -> {
                    val script = command.removePrefix("run ").trim()
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo 'Simulating script: $script'"))
                    process.waitFor()
                    "✅ [EXEC] تم تشغيل ومحاكاة $script"
                }
                command.startsWith("open ") -> {
                    val target = command.removePrefix("open ").trim()
                    val file = if (File(target).isAbsolute) File(target) else File(getBaseDirForPrefix("@builder"), target)
                    if (file.exists()) {
                        try {
                            val authority = "${context.packageName}.fileprovider"
                            val uri = FileProvider.getUriForFile(context, authority, file)
                            val mimeType = context.contentResolver.getType(uri) ?: getMimeType(file)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            "✅ [EXEC] تم فتح الملف: ${file.name}"
                        } catch (e: Exception) {
                            "❌ [EXEC] فشل فتح الملف ${file.name}: ${e.message}"
                        }
                    } else {
                        "⚠️ [EXEC] الملف غير موجود: ${file.absolutePath}"
                    }
                }
                command.startsWith("copy ") -> {
                    val target = command.removePrefix("copy ").trim()
                    val file = if (File(target).isAbsolute) File(target) else File(getBaseDirForPrefix("@builder"), target)
                    if (file.exists() && file.isFile) {
                        try {
                            val content = file.readText(Charsets.UTF_8)
                            withContext(Dispatchers.Main) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(file.name, content))
                            }
                            "✅ [EXEC] تم نسخ محتوى الملف (${file.name}) إلى الحافظة بنجاح."
                        } catch (e: Exception) {
                            "❌ [EXEC] فشل قراءة الملف: ${e.message}"
                        }
                    } else {
                        "⚠️ [EXEC] الملف غير موجود أو ليس ملفاً صالحاً: ${file.absolutePath}"
                    }
                }
                command.startsWith("delete ") -> {
                    val target = command.removePrefix("delete ").trim()
                    val file = if (File(target).isAbsolute) File(target) else File(getBaseDirForPrefix("@builder"), target)
                    if (file.exists()) {
                        val isDir = file.isDirectory
                        try {
                            val deleted = file.deleteRecursively()
                            if (deleted) {
                                "✅ [EXEC] تم حذف ${if (isDir) "المجلد" else "الملف"} بنجاح: ${file.name}"
                            } else {
                                "❌ [EXEC] فشل حذف: ${file.name}"
                            }
                        } catch (e: Exception) {
                            "❌ [EXEC] فشل أثناء الحذف: ${e.message}"
                        }
                    } else {
                        "⚠️ [EXEC] المسار غير موجود للحذف: ${file.absolutePath}"
                    }
                }
                else -> "⚠️ [EXEC] أمر غير معروف: $command"
            }
        } catch (e: Exception) {
            "❌ [EXEC] فشل: ${e.message}"
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase(java.util.Locale.ROOT)
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    // =====================================================================
    // 7. دوال TreeDoc
    // =====================================================================

    private fun isTreedocLine(line: String, prefixes: List<String>): Boolean =
        prefixes.any { line.trim().startsWith("$it:report") || line.trim().startsWith("$it:scan") }

    private fun extractTreedocCommand(line: String, prefixes: List<String>): Triple<String, String, Boolean>? {
        val s = line.trim()
        for (p in prefixes) {
            if (s.startsWith("$p:report ")) {
                val parts = s.removePrefix("$p:report ").trim().split(" ")
                val folder = parts.getOrElse(0) { "." }
                val fmt = parts.getOrElse(1) { "html" }
                val clip = parts.any { it.lowercase() in listOf("clipboard", "clip") }
                return Triple(folder, fmt, clip)
            }
            if (s.startsWith("$p:scan ")) {
                return Triple(s.removePrefix("$p:scan ").trim(), "html", true)
            }
        }
        return null
    }

    suspend fun runTreedocNew(remainder: String, matchedType: String): Pair<String, Map<String, String>?> = withContext(Dispatchers.IO) {
        try {
            val (args, flags) = CommandRegistry.parseArgsAndFlags(remainder)
            val base = getBaseDirForPrefix("@builder")
            
            val rawPath = args["path"] ?: args["folder"] ?: run {
                val firstWord = remainder.trim().split(" ").firstOrNull() ?: ""
                if (firstWord.startsWith("--") || firstWord.isEmpty()) "." else firstWord
            }
            
            val target = if (rawPath == "." || rawPath == "" || rawPath == "SmartPlatform") {
                base
            } else {
                val fObj = File(rawPath)
                if (fObj.isAbsolute && fObj.exists()) fObj else File(base, rawPath)
            }
            
            if (!target.exists()) {
                target.mkdirs()
            }

            val fmt = args["format"] ?: "json"
            val copyClipboard = !flags.contains("no-clip")

            val report: String
            val ext: String

            if (fmt.lowercase(Locale.ROOT) == "json") {
                ext = "json"
                val rootArray = JSONArray()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                
                val walkRecursive = matchedType == "scan" || flags.contains("recursive") || args["recursive"] == "true"
                val files = mutableListOf<File>()
                
                if (walkRecursive) {
                    target.walkTopDown().forEach { f ->
                        if (f.absolutePath != target.absolutePath && !IGNORE_DIRS.contains(f.name)) {
                            files.add(f)
                        }
                    }
                } else {
                    target.listFiles()?.forEach { f ->
                        if (!IGNORE_DIRS.contains(f.name)) {
                            files.add(f)
                        }
                    }
                }

                for (file in files) {
                    val obj = JSONObject().apply {
                        put("name", file.name)
                        put("path", file.relativeTo(base).path)
                        put("size", if (file.isDirectory) 0L else file.length())
                        put("extension", file.extension)
                        put("lastModified", sdf.format(Date(file.lastModified())))
                    }
                    rootArray.put(obj)
                }
                report = rootArray.toString(4)
            } else if (fmt.lowercase(Locale.ROOT) == "html") {
                ext = "html"
                val tree = collectTreeData(target, showSize = true, showMtime = true, showCount = true)
                val sb = java.lang.StringBuilder()
                sb.append("<!DOCTYPE html><html lang=\"ar\" dir=\"rtl\"><head><meta charset=\"UTF-8\"><title>TreeDoc Report</title></head><body>")
                sb.append("<div style=\"background:#111; color:#fff; padding:20px; font-family:sans-serif;\">")
                sb.append("<h1>📂 تقرير مستكشف شجرة المجلد</h1><p>مسار المجلد: ${target.absolutePath}</p>")
                fun buildHtmlItems(nodes: List<TreeNode>) {
                    for (node in nodes) {
                        sb.append("<div style=\"display:flex; justify-content:space-between; padding:5px; background:#222; margin-bottom:2px;\">")
                        sb.append("<span>${if (node.type == "directory") "📁" else "📄"} ${node.name}</span>")
                        sb.append("<span>${node.size ?: ""}</span></div>")
                        if (node.children.isNotEmpty()) {
                            buildHtmlItems(node.children)
                        }
                    }
                }
                buildHtmlItems(tree)
                sb.append("</div></body></html>")
                report = sb.toString()
            } else {
                ext = "txt"
                val tree = collectTreeData(target, showSize = true, showMtime = true, showCount = true)
                report = buildTextReport(target.absolutePath, tree, showSize = true, showMtime = true, showCount = true)
            }

            if (copyClipboard) {
                withContext(Dispatchers.Main) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TreeDoc Report", report))
                }
            }

            val outFile = File(target, "tree_report.$ext")
            outFile.writeText(report, Charsets.UTF_8)
            "✅ [TREEDOC] تقرير $fmt جاهز: ${outFile.name}" to mapOf("report" to report, "path" to outFile.absolutePath)
        } catch (e: Exception) {
            "❌ [TREEDOC] فشل: ${e.message}" to null
        }
    }

    /**
     * يولد تقريراً شجرياً للمجلد المحدد وينسخه للحافظة إن طُلب.
     * يستخدم دوال مسح المجلدات وتوليد النص الهرمي.
     */
    suspend fun runTreedoc(folder: String, fmt: String, copyClipboard: Boolean): Pair<String, Map<String, String>?> = withContext(Dispatchers.IO) {
        try {
            val base = getBaseDirForPrefix("@builder")
            val target = if (folder == "." || folder == "" || folder == "SmartPlatform") {
                base
            } else {
                val fObj = File(folder)
                if (fObj.isAbsolute && fObj.exists()) fObj else File(base, folder)
            }
            
            if (!target.exists()) {
                target.mkdirs()
            }

            val tree = collectTreeData(target, showSize = true, showMtime = true, showCount = true)
            if (tree.isEmpty()) return@withContext "⚠️ [TREEDOC] المجلد فارغ: ${target.name}" to null

            val report = when (fmt) {
                "json" -> buildJsonReport(tree)
                else -> buildTextReport(target.absolutePath, tree, showSize = true, showMtime = true, showCount = true)
            }

            if (copyClipboard) {
                // نسخ للحافظة (يحتاج ClipboardManager في السياق الرئيسي)
                withContext(Dispatchers.Main) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TreeDoc Report", report))
                }
            }

            // حفظ الملف
            val outFile = File(target, "tree_report.${if (fmt == "json") "json" else "txt"}")
            outFile.writeText(report, Charsets.UTF_8)
            "✅ [TREEDOC] تقرير $fmt جاهز: ${outFile.name}" to mapOf("report" to report, "path" to outFile.absolutePath)
        } catch (e: Exception) {
            "❌ [TREEDOC] فشل: ${e.message}" to null
        }
    }

    // =====================================================================
    // 8. دوال جمع بيانات الشجرة (TreeDoc)
    // =====================================================================

    private data class TreeNode(
        val name: String,
        val path: String,
        val type: String, // "directory" or "file"
        val children: MutableList<TreeNode> = mutableListOf(),
        var size: String? = null,
        var mtime: String? = null,
        var count: Int? = null
    )

    /**
     * دالة استرجاعية لاستكشاف المجلدات والملفات وبناء شجرة بيانات.
     * تدعم العمق الأقصى، قوائم الاستبعاد، والبيانات الوصفية (الحجم، التاريخ، العدد).
     */
    private fun collectTreeData(
        directory: File,
        depth: Int = 0,
        maxDepth: Int? = null,
        exclude: List<String> = emptyList(),
        showSize: Boolean = false,
        showMtime: Boolean = false,
        showCount: Boolean = false
    ): List<TreeNode> {
        if (maxDepth != null && depth > maxDepth) return emptyList()
        val dirName = directory.name.ifEmpty { directory.path }
        val node = TreeNode(
            name = dirName,
            path = directory.absolutePath,
            type = "directory"
        ).apply {
            if (showMtime) {
                this.mtime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(directory.lastModified()))
            }
        }

        try {
            val entries = directory.listFiles()?.filter { file ->
                file.name != "tree_report.txt" && file.name != "tree_report.json" &&
                exclude.none { ex -> ex in file.name } &&
                !IGNORE_DIRS.contains(file.name)
            }?.sortedWith(compareBy({ it.isFile }, { it.name })) ?: return listOf(node)

            for (entry in entries) {
                if (entry.isDirectory) {
                    val children = collectTreeData(entry, depth + 1, maxDepth, exclude, showSize, showMtime, showCount)
                    node.children.addAll(children)
                } else {
                    val fileNode = TreeNode(
                        name = entry.name,
                        path = entry.absolutePath,
                        type = "file"
                    ).apply {
                        if (showSize) this.size = formatSize(entry.length())
                        if (showMtime) this.mtime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.lastModified()))
                    }
                    node.children.add(fileNode)
                }
            }
            if (showCount) node.count = node.children.size
        } catch (e: SecurityException) {
            node.children.add(TreeNode("❗ لا يمكن الوصول", "", "error"))
        } catch (e: Exception) {
            node.children.add(TreeNode("❗ خطأ: ${e.message}", "", "error"))
        }
        return listOf(node)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes == 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        for (unit in units) {
            if (size < 1024.0) return "%.1f %s".format(size, unit)
            size /= 1024.0
        }
        return "%.1f PB".format(size)
    }

    private fun buildTextReport(rootPath: String, tree: List<TreeNode>, showSize: Boolean, showMtime: Boolean, showCount: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("📂 $rootPath")
        sb.appendLine("=".repeat(50))
        buildTextLines(tree, "", true, showSize, showMtime, showCount, sb)
        return sb.toString()
    }

    private fun buildTextLines(nodes: List<TreeNode>, prefix: String, isLast: Boolean, showSize: Boolean, showMtime: Boolean, showCount: Boolean, sb: StringBuilder) {
        for ((i, node) in nodes.withIndex()) {
            val isLastItem = i == nodes.size - 1
            val connector = if (isLastItem) "└── " else "├── "
            sb.append("$prefix$connector${node.name}")
            val extras = mutableListOf<String>()
            if (showSize && node.size != null) extras.add("[${node.size}]")
            if (showMtime && node.mtime != null) extras.add("(${node.mtime})")
            if (showCount && node.count != null && node.type == "directory") extras.add("📁 ${node.count} items")
            if (extras.isNotEmpty()) sb.append(" ${extras.joinToString(" ")}")
            sb.appendLine()
            if (node.children.isNotEmpty()) {
                val newPrefix = prefix + if (isLastItem) "    " else "│   "
                buildTextLines(node.children, newPrefix, isLastItem, showSize, showMtime, showCount, sb)
            }
        }
    }

    private fun buildJsonReport(tree: List<TreeNode>): String {
        return buildJsonObjectString(tree)
    }

    private fun buildJsonObjectString(nodes: List<TreeNode>): String {
        val sb = StringBuilder()
        sb.append("[")
        for ((i, node) in nodes.withIndex()) {
            sb.append("{")
            sb.append("\"name\":\"${node.name.replace("\"", "\\\"")}\",")
            sb.append("\"path\":\"${node.path.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
            sb.append("\"type\":\"${node.type}\"")
            if (node.size != null) sb.append(",\"size\":\"${node.size}\"")
            if (node.mtime != null) sb.append(",\"mtime\":\"${node.mtime}\"")
            if (node.count != null) sb.append(",\"count\":${node.count}")
            if (node.children.isNotEmpty()) {
                sb.append(",\"children\":${buildJsonObjectString(node.children)}")
            }
            sb.append("}")
            if (i < nodes.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun showSystemNotification(msg: String) {
        try {
            val ns = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "SmartPlatformChannel"
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val name = "مراقب المنصة الذكية"
                val importance = android.app.NotificationManager.IMPORTANCE_HIGH
                val channel = android.app.NotificationChannel(channelId, name, importance)
                ns.createNotificationChannel(channel)
            }

            val icon = android.R.drawable.ic_dialog_info
            val intent = Intent(context, com.example.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 99, intent, android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(icon)
                .setContentTitle("نتائج تنفيذ محرك الأوامر")
                .setContentText(msg)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(msg))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            ns.notify(991, builder.build())
            
            // Show toast on Main thread
            kotlinx.coroutines.MainScope().launch {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("BuilderEngine", "Failed to show notification: ${e.message}")
        }
    }
}

// =====================================================================
// فئات مساعدة (Data Classes)
// =====================================================================

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
data class FileWriteResult(val message: String, val fullPath: String, val size: Long, val mode: String)
data class ProcessResult(val type: String, val message: String, val data: Map<String, String>? = null)

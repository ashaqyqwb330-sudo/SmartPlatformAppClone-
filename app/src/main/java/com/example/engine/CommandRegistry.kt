package com.example.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object CommandRegistry {
    private val commands = mutableMapOf<String, Command>()

    init {
        registerCommand("scan", ScanCommand())
        registerCommand("move", MoveCommand())
        registerCommand("rename", RenameCommand())
        registerCommand("copy-safe", CopySafeCommand())
        registerCommand("delete", DeleteCommand())
        registerCommand("report", ReportCommand())
        registerCommand("open", OpenCommand())
        registerCommand("mkdir", MkdirCommand())
        registerCommand("selftest", SelfTestCommand())
    }

    fun registerCommand(name: String, command: Command) {
        commands[name.lowercase(Locale.ROOT)] = command
    }

    fun getCommand(name: String): Command? {
        return commands[name.lowercase(Locale.ROOT)]
    }

    /**
     * Parses arguments from both JSON block and CLI-like double dash --key=val format.
     * JSON parameters have priority and overwrite CLI parameters.
     */
    fun parseArgsAndFlags(input: String): Pair<Map<String, String>, List<String>> {
        val args = mutableMapOf<String, String>()
        val flags = mutableListOf<String>()

        var trimmed = input.trim()

        // Match CLI arguments first as baseline
        val cliArgs = mutableMapOf<String, String>()
        val cliFlags = mutableListOf<String>()
        val regex = """--([a-zA-Z0-9_-]+)(?:=(?:["']([^"']*)["']|([^\s"']*)))?""".toRegex()
        val matches = regex.findAll(trimmed)
        for (match in matches) {
            val key = match.groupValues[1]
            val valueInQuotes = match.groupValues[2]
            val valueWithoutQuotes = match.groupValues[3]
            
            val value = if (valueInQuotes.isNotEmpty() || (match.value.contains("=") && (match.groupValues[0].contains("\"\"") || match.groupValues[0].contains("''")))) {
                valueInQuotes
            } else {
                valueWithoutQuotes
            }
            if (value.isEmpty() && !match.value.contains("=")) {
                cliFlags.add(key)
            } else {
                cliArgs[key] = value
            }
        }

        // Strip markdown backticks if any
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.removePrefix("```json")
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.removeSuffix("```")
            }
            trimmed = trimmed.trim()
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.removePrefix("```")
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.removeSuffix("```")
            }
            trimmed = trimmed.trim()
        }

        // Robust JSON detection block
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        var jsonParsed = false

        if (firstBrace != -1 && lastBrace > firstBrace) {
            val jsonPossible = trimmed.substring(firstBrace, lastBrace + 1)
            try {
                val json = JSONObject(jsonPossible)
                val jsonArgs = mutableMapOf<String, String>()
                val jsonFlags = mutableListOf<String>()

                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.opt(key)
                    if (value is Boolean) {
                        if (value) jsonFlags.add(key)
                    } else {
                        jsonArgs[key] = value?.toString() ?: ""
                    }
                }

                // JSON takes priority over CLI
                args.putAll(cliArgs)
                args.putAll(jsonArgs)

                flags.addAll(cliFlags)
                for (jf in jsonFlags) {
                    if (!flags.contains(jf)) {
                        flags.add(jf)
                    }
                }
                jsonParsed = true
            } catch (e: Exception) {
                // fall back to CLI if JSON parsing fails
            }
        }

        if (!jsonParsed) {
            args.putAll(cliArgs)
            flags.addAll(cliFlags)
        }
        
        return Pair(args, flags)
    }
}

// =====================================================================
// 1. SCAN Command
// =====================================================================
class ScanCommand : Command {
    override suspend fun execute(context: CommandContext): CommandResult {
        val pathArg = context.args["path"]
        val targetDir = context.getFile(pathArg)
        val recursive = context.flags.contains("recursive") || context.args["recursive"] == "true"
        val format = context.args["format"] ?: "json"

        if (!targetDir.exists() || !targetDir.isDirectory) {
            return CommandResult(false, "❌ المجلد غير موجود أو ليس مجلداً صالحاً: ${targetDir.absolutePath}")
        }

        val ignoreDirs = BuilderEngine.IGNORE_DIRS
        val fileList = mutableListOf<File>()

        try {
            if (recursive) {
                targetDir.walkTopDown().forEach { file ->
                    if (file.absolutePath == targetDir.absolutePath) return@forEach
                    val relative = file.relativeTo(targetDir).path
                    val parts = relative.split(File.separator)
                    if (parts.none { it in ignoreDirs }) {
                        fileList.add(file)
                    }
                }
            } else {
                targetDir.listFiles()?.forEach { file ->
                    if (!ignoreDirs.contains(file.name)) {
                        fileList.add(file)
                    }
                }
            }
        } catch (e: Exception) {
            return CommandResult(false, "❌ فشل مسح المجلد: ${e.message}")
        }

        // Generate JSON output
        val jsonArray = JSONArray()
        val textBuilder = java.lang.StringBuilder()
        textBuilder.appendLine("📁 مسح المجلد: ${targetDir.name} (${targetDir.absolutePath})")
        textBuilder.appendLine("=".repeat(50))

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        for (file in fileList) {
            val isDir = file.isDirectory
            val size = if (isDir) 0L else file.length()
            val mtime = file.lastModified()
            val relPath = file.relativeTo(targetDir).path

            val obj = JSONObject().apply {
                put("name", file.name)
                put("path", relPath)
                put("isDirectory", isDir)
                put("size", size)
                put("lastModified", sdf.format(Date(mtime)))
            }
            jsonArray.put(obj)

            val typeIcon = if (isDir) "📁" else "📄"
            val sizeStr = if (isDir) "[Folder]" else "${String.format(Locale.US, "%.2f", size / 1024.0)} KB"
            textBuilder.appendLine("$typeIcon $relPath - $sizeStr (${sdf.format(Date(mtime))})")
        }

        val outputString = if (format.lowercase(Locale.ROOT) == "text") {
            textBuilder.toString()
        } else {
            jsonArray.toString(4)
        }

        // Copy to clipboard
        withContext(Dispatchers.Main) {
            val clipboard = context.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Scan Results", outputString))
        }

        context.log("⚡ تنفيذ Scan بنجاح لمجلد ${targetDir.name}", "عدد الملفات المستخرجة: ${fileList.size}")

        return CommandResult(
            success = true,
            message = "✅ تم مسح مجلد (${targetDir.name}) بنجاح! تم استخراج ${fileList.size} ملفات ونُسخت النتائج إلى الحافظة.",
            output = outputString
        )
    }

    override suspend fun dryRun(context: CommandContext): String {
        val pathArg = context.args["path"]
        val targetDir = context.getFile(pathArg)
        val format = context.args["format"] ?: "json"
        val recursive = context.flags.contains("recursive") || context.args["recursive"] == "true"
        return "[محاكاة (Dry-Run)] جاري مسح مجلد (${targetDir.name}) بالمسار (${targetDir.absolutePath}) - الصيغة المطلوبة: $format - التكرارية: $recursive"
    }
}

// =====================================================================
// 2. MOVE Command
// =====================================================================
class MoveCommand : Command {
    override suspend fun execute(context: CommandContext): CommandResult {
        val sPath = context.args["path"] ?: return CommandResult(false, "❌ معامل المسار المصدر `--path` مطلوب.")
        val dPath = context.args["dest"] ?: return CommandResult(false, "❌ معامل المسار المستهدف `--dest` مطلوب.")

        val srcFile = context.getFile(sPath)
        val destFile = context.getFile(dPath)

        if (!srcFile.exists()) {
            return CommandResult(false, "❌ الملف المصدر غير موجود: ${srcFile.absolutePath}")
        }

        var finalDest = destFile
        if (destFile.exists() && destFile.isDirectory) {
            finalDest = File(destFile, srcFile.name)
        }

        val parent = finalDest.parentFile
        if (parent == null || !parent.exists()) {
            return CommandResult(false, "❌ المجلد الوجهة غير موجود. استخدم @executor:mkdir أولاً")
        }

        val overwrite = context.flags.contains("overwrite") || context.args["overwrite"] == "true"
        if (finalDest.exists() && !overwrite) {
            val parentDir = finalDest.parentFile ?: context.baseDir
            val baseName = finalDest.nameWithoutExtension
            val ext = finalDest.extension
            var index = 1
            var candidate = finalDest
            while (candidate.exists()) {
                val suffix = if (ext.isNotEmpty()) ".$ext" else ""
                candidate = File(parentDir, "${baseName}_$index$suffix")
                index++
            }
            finalDest = candidate
        }

        // Automatic Backup
        val backupMsg = BackupManager.createBackup(context.context, listOf(srcFile), context.baseDir)
        context.log("🛡️ [نسخ احتياطي تلقائي]", backupMsg)

        val success = srcFile.renameTo(finalDest)
        if (success) {
            context.log("✈️ نقل ملف", "موفق: من ${srcFile.name} إلى ${finalDest.absolutePath}")
            return CommandResult(true, "✅ تم نقل الملف بنجاح إلى: ${finalDest.absolutePath}. ($backupMsg)")
        } else {
            // Fallback to copy and delete
            try {
                val copied = if (srcFile.isDirectory) {
                    srcFile.copyRecursively(finalDest, overwrite = true)
                } else {
                    srcFile.copyTo(finalDest, overwrite = true)
                    true
                }

                if (copied) {
                    srcFile.deleteRecursively()
                    context.log("✈️ نقل ملف (عبر النسخ والحذف)", "موفق: من ${srcFile.name} إلى ${finalDest.absolutePath}")
                    return CommandResult(true, "✅ تم نقل الملف بنجاح إلى: ${finalDest.absolutePath}. ($backupMsg)")
                }
            } catch (e: Exception) {
                return CommandResult(false, "❌ فشل نقل الملف: ${e.message}")
            }
        }
        return CommandResult(false, "❌ فشل عملية النقل للمسار: ${finalDest.absolutePath}")
    }

    override suspend fun dryRun(context: CommandContext): String {
        val sPath = context.args["path"] ?: "غير محدد"
        val dPath = context.args["dest"] ?: "غير محدد"
        val srcFile = context.getFile(sPath)
        val destFile = context.getFile(dPath)
        return "[محاكاة (Dry-Run)] جاري نقل الملف (موجود: ${srcFile.exists()}) من (${srcFile.name}) إلى وجهة (${destFile.absolutePath}). سيتم تفعيل النسخ الاحتياطي التلقائي."
    }
}

// =====================================================================
// 3. RENAME Command
// =====================================================================
class RenameCommand : Command {
    override suspend fun execute(context: CommandContext): CommandResult {
        val sPath = context.args["path"] ?: return CommandResult(false, "❌ معامل المسار `--path` مطلوب.")
        val newName = context.args["new-name"] ?: context.args["newName"] ?: return CommandResult(false, "❌ معامل الاسم الجديد `--new-name` مطلوب.")

        val file = context.getFile(sPath)
        if (!file.exists()) {
            return CommandResult(false, "❌ الملف أو المجلد غير موجود: ${file.absolutePath}")
        }

        val parent = file.parentFile
        val dest = File(parent, newName)

        if (dest.exists()) {
            return CommandResult(false, "❌ الاسم الجديد موجود بالفعل في هذا المجلد: $newName")
        }

        // Automatic Backup
        val backupMsg = BackupManager.createBackup(context.context, listOf(file), context.baseDir)
        context.log("🛡️ [نسخ احتياطي تلقائي]", backupMsg)

        val success = file.renameTo(dest)
        if (success) {
            context.log("✏️ إعادة تسمية", "موفق: من ${file.name} إلى $newName")
            return CommandResult(true, "✅ تم إعادة تسمية الملف بنجاح إلى: $newName. ($backupMsg)")
        }
        return CommandResult(false, "❌ فشل إعادة تسمية الملف.")
    }

    override suspend fun dryRun(context: CommandContext): String {
        val sPath = context.args["path"] ?: "غير محدد"
        val newName = context.args["new-name"] ?: context.args["newName"] ?: "غير محدد"
        val file = context.getFile(sPath)
        return "[محاكاة (Dry-Run)] جاري إعادة تسمية الملف (موجود: ${file.exists()}) من (${file.name}) إلى اسم ($newName). سيتم تفعيل النسخ الاحتياطي التلقائي."
    }
}

// =====================================================================
// 4. COPY-SAFE Command
// =====================================================================
class CopySafeCommand : Command {
    override suspend fun execute(context: CommandContext): CommandResult {
        val sPath = context.args["path"] ?: return CommandResult(false, "❌ معامل المسار المصدر `--path` مطلوب.")
        val dPath = context.args["dest"] ?: return CommandResult(false, "❌ معامل المسار المستهدف `--dest` مطلوب.")

        val srcFile = context.getFile(sPath)
        val destFile = context.getFile(dPath)

        if (!srcFile.exists()) {
            return CommandResult(false, "❌ الملف المصدر غير موجود: ${srcFile.absolutePath}")
        }

        var finalDest = destFile
        if (destFile.exists() && destFile.isDirectory) {
            finalDest = File(destFile, srcFile.name)
        }

        val parent = finalDest.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        try {
            if (srcFile.isDirectory) {
                srcFile.copyRecursively(finalDest, overwrite = true)
            } else {
                srcFile.copyTo(finalDest, overwrite = true)
            }
            context.log("📋 نسخ آمن", "موفق: من ${srcFile.name} إلى ${finalDest.absolutePath}")
            return CommandResult(true, "✅ تم نسخ الملف بنجاح إلى: ${finalDest.absolutePath}")
        } catch (e: Exception) {
            return CommandResult(false, "❌ فشل نسخ الملف: ${e.message}")
        }
    }

    override suspend fun dryRun(context: CommandContext): String {
        val sPath = context.args["path"] ?: "غير محدد"
        val dPath = context.args["dest"] ?: "غير محدد"
        val srcFile = context.getFile(sPath)
        return "[محاكاة (Dry-Run)] جاري نسخ الملف (موجود: ${srcFile.exists()}) من (${srcFile.name}) إلى مسار (${dPath})."
    }
}

// =====================================================================
// 5. DELETE Command
// =====================================================================
class DeleteCommand : Command {
    override suspend fun execute(context: CommandContext): CommandResult {
        val sPath = context.args["path"] ?: return CommandResult(false, "❌ معامل المسار `--path` مطلوب للحذف.")
        val force = context.flags.contains("force") || context.args["force"] == "true"

        val file = context.getFile(sPath)
        if (!file.exists()) {
            return CommandResult(true, "⚠️ الملف غير موجود بالفعل.")
        }

        if (file.isDirectory) {
            val list = file.list()
            if (list != null && list.isNotEmpty() && !force) {
                return CommandResult(
                    false,
                    "⚠️ المجلد يحتوي على ملفات! لتأكيد الحذف التام أضف معامل `--force` (أو force = true) تجنباً لفقدان البيانات."
                )
            }
        }

        // Automatic Backup
        val backupMsg = BackupManager.createBackup(context.context, listOf(file), context.baseDir)
        context.log("🛡️ [نسخ احتياطي تلقائي]", backupMsg)

        try {
            val deleted = file.deleteRecursively()
            if (deleted) {
                context.log("🗑️ حذف آمن", "تم حذف الملف: ${file.name}")
                return CommandResult(true, "✅ تم حذف: ${file.name} بنجاح. ($backupMsg)")
            }
        } catch (e: Exception) {
            return CommandResult(false, "❌ فشل الحذف: ${e.message}")
        }

        return CommandResult(false, "❌ فشل عملية الحذف.")
    }

    override suspend fun dryRun(context: CommandContext): String {
        val sPath = context.args["path"] ?: "غير محدد"
        val file = context.getFile(sPath)
        return "[محاكاة (Dry-Run)] جاري حذف الملف (موجود: ${file.exists()}) بمسار (${file.absolutePath}). سيتم تفعيل النسخ الاحتياطي التلقائي."
    }
}

// =====================================================================
// 6. REPORT Command
// =====================================================================
class ReportCommand : Command {
    override suspend fun execute(context: CommandContext): CommandResult {
        val pathArg = context.args["path"]
        val targetDir = context.getFile(pathArg)
        val format = context.args["format"] ?: "html"

        if (!targetDir.exists() || !targetDir.isDirectory) {
            targetDir.mkdirs()
        }

        val ignoreDirs = BuilderEngine.IGNORE_DIRS
        val fileList = mutableListOf<File>()

        try {
            targetDir.walkTopDown().forEach { file ->
                if (file.absolutePath == targetDir.absolutePath) return@forEach
                val relative = file.relativeTo(targetDir).path
                val parts = relative.split(File.separator)
                if (parts.none { it in ignoreDirs }) {
                    fileList.add(file)
                }
            }
        } catch (e: Exception) {
            return CommandResult(false, "❌ فشل لجمع قائمة الملفات: ${e.message}")
        }

        val reportOutput: String
        val filename: String

        when (format.lowercase(Locale.ROOT)) {
            "json" -> {
                filename = "tree_report.json"
                val rootArray = JSONArray()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                for (file in fileList) {
                    val obj = JSONObject().apply {
                        put("name", file.name)
                        put("path", file.relativeTo(targetDir).path)
                        put("type", if (file.isDirectory) "directory" else "file")
                        put("size_bytes", if (file.isDirectory) 0 else file.length())
                        put("last_modified", sdf.format(Date(file.lastModified())))
                    }
                    rootArray.put(obj)
                }
                reportOutput = rootArray.toString(4)
            }
            "text", "md", "markdown" -> {
                filename = "tree_report.md"
                val sb = StringBuilder()
                sb.appendLine("# 📂 تقرير شجرة المشروع: ${targetDir.name}")
                sb.appendLine("- **تاريخ التوليد:** ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                sb.appendLine("- **عدد الملفات الإجمالي:** ${fileList.count { !it.isDirectory }} file(s)")
                sb.appendLine("- **عدد المجلدات الإجمالي:** ${fileList.count { it.isDirectory }} folder(s)")
                sb.appendLine()
                sb.appendLine("```")
                for (file in fileList) {
                    val isDir = file.isDirectory
                    val relPath = file.relativeTo(targetDir).path
                    val indent = "  ".repeat(relPath.count { it == File.separatorChar })
                    val prefix = if (isDir) "📁" else "📄"
                    val sizeStr = if (isDir) "" else " [${String.format(Locale.US, "%.1f", file.length() / 1024.0)} KB]"
                    sb.appendLine("$indent$prefix ${file.name}$sizeStr")
                }
                sb.appendLine("```")
                reportOutput = sb.toString()
            }
            else -> { // HTML (Interactive Styled Report)
                filename = "tree_report.html"
                val sb = java.lang.StringBuilder()
                sb.append("""
                    <!DOCTYPE html>
                    <html lang="ar" dir="rtl">
                    <head>
                        <meta charset="UTF-8">
                        <title>تقرير مشروع المراقب الذكي - ${targetDir.name}</title>
                        <style>
                            body {
                                background-color: #0B0F19;
                                color: #E2E8F0;
                                font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                                margin: 0;
                                padding: 24px;
                            }
                            .card {
                                background: linear-gradient(135deg, #111827 0%, #1F2937 100%);
                                border: 1px solid #D97706;
                                border-radius: 12px;
                                padding: 24px;
                                max-width: 900px;
                                margin: 0 auto;
                                box-shadow: 0 10px 30px rgba(0,0,0,0.5);
                            }
                            h1 {
                                color: #F59E0B;
                                margin-top: 0;
                                font-size: 24px;
                                border-bottom: 2px solid #D97706;
                                padding-bottom: 12px;
                            }
                            .meta {
                                color: #94A3B8;
                                font-size: 14px;
                                margin-bottom: 20px;
                            }
                            .item {
                                background-color: #111827;
                                padding: 10px 14px;
                                border-radius: 6px;
                                margin-bottom: 6px;
                                border-right: 3px solid #D97706;
                                display: flex;
                                justify-content: space-between;
                                align-items: center;
                            }
                            .item.folder {
                                border-right: 3px solid #3B82F6;
                                background-color: #1E293B;
                            }
                            .name {
                                font-weight: bold;
                                color: #F1F5F9;
                            }
                            .path {
                                color: #64748B;
                                font-size: 12px;
                                font-family: monospace;
                            }
                            .size {
                                color: #F59E0B;
                                font-size: 13px;
                            }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <h1>📊 تقرير الأتمتة واستكشاف شجرة المشروع</h1>
                            <div class="meta">
                                <strong>المجلد المستهدف:</strong> ${targetDir.absolutePath}<br>
                                <strong>تاريخ إنشاء الملف:</strong> ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}<br>
                                <strong>إجمالي الملفات:</strong> ${fileList.size} ملف ومجلد
                            </div>
                            <div class="tree-list">
                """.trimIndent())

                for (file in fileList) {
                    val isDir = file.isDirectory
                    val relPath = file.relativeTo(targetDir).path
                    val typeClass = if (isDir) "folder" else ""
                    val prefix = if (isDir) "📁" else "📄"
                    val sizeText = if (isDir) "مجلد" else "${String.format(Locale.US, "%.1f", file.length() / 1024.0)} KB"
                    
                    sb.append("""
                        <div class="item $typeClass">
                            <div>
                                <span class="name">$prefix ${file.name}</span>
                                <div class="path">$relPath</div>
                            </div>
                            <div class="size">$sizeText</div>
                        </div>
                    """.trimIndent())
                }

                sb.append("""
                            </div>
                        </div>
                    </body>
                    </html>
                """.trimIndent())
                reportOutput = sb.toString()
            }
        }

        // Save report output to disk
        try {
            val reportFile = File(targetDir, filename)
            val parentFolder = reportFile.parentFile
            if (parentFolder != null && !parentFolder.exists()) {
                parentFolder.mkdirs()
            }
            reportFile.writeText(reportOutput, Charsets.UTF_8)
            
            // Copy to Clipboard as requested
            withContext(Dispatchers.Main) {
                val clipboard = context.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TreeDoc $format Report", reportOutput))
            }

            context.log("📊 إنشاء تقرير", "موفق: صيغة $format، تم حفظه بـ ${reportFile.name} ونسخه للحافظة بنجاح.")
            return CommandResult(
                true,
                "✅ تم توليد تقرير المشروع بنجاح بصيغة $format وحفظه كملف (${reportFile.name})، كما تم نسخ التقرير التام للحافظة ليسهل استخدامه مع المساعد الذكي."
            )
        } catch (e: Exception) {
            return CommandResult(false, "❌ فشل حفظ التقرير: ${e.message}")
        }
    }

    override suspend fun dryRun(context: CommandContext): String {
        val pathArg = context.args["path"]
        val targetDir = context.getFile(pathArg)
        val format = context.args["format"] ?: "html"
        return "[محاكاة (Dry-Run)] جاري توليد تقرير استكشافي للمجلد (${targetDir.name}) بصيغة $format."
    }
}

// =====================================================================
// 7. OPEN Command
// =====================================================================
class OpenCommand : Command {
    override suspend fun execute(context: CommandContext): CommandResult {
        val sPath = context.args["target"] ?: context.args["path"] ?: return CommandResult(false, "❌ معامل المسار `--target` أو `--path` مطلوب لفتح الملف.")
        
        if (sPath.startsWith("http://") || sPath.startsWith("https://") || sPath.startsWith("ftp://")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sPath)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    context.context.startActivity(intent)
                }
                context.log("👁️ فتح رابط", "موفق: فتح الرابط الإلكتروني $sPath")
                return CommandResult(true, "✅ تم فتح الرابط بنجاح: $sPath")
            } catch (e: Exception) {
                return CommandResult(false, "❌ فشل فتح الرابط الإلكتروني: ${e.message}")
            }
        }

        val file = context.getFile(sPath)
        if (!file.exists()) {
            return CommandResult(false, "❌ الملف أو المجلد غير موجود لفتحه: ${file.absolutePath}")
        }

        try {
            val authority = "${context.context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context.context, authority, file)
            val ext = file.extension.lowercase(Locale.ROOT)
            val mimeType = if (file.isDirectory) {
                "resource/folder"
            } else {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            withContext(Dispatchers.Main) {
                context.context.startActivity(intent)
            }
            val typeStr = if (file.isDirectory) "المجلد" else "الملف"
            context.log("👁️ فتح $typeStr", "موفق: فتح ${file.name}")
            return CommandResult(true, "✅ تم تشغيل المعالج الافتراضي لفتح $typeStr: ${file.name}")
        } catch (e: Exception) {
            return CommandResult(false, "❌ فشل فتح ${if (file.isDirectory) "المجلد" else "الملف"}: ${e.message}")
        }
    }

    override suspend fun dryRun(context: CommandContext): String {
        val sPath = context.args["target"] ?: context.args["path"] ?: "غير محدد"
        if (sPath.startsWith("http://") || sPath.startsWith("https://") || sPath.startsWith("ftp://")) {
            return "[محاكاة (Dry-Run)] جاري طلب فتح الرابط ($sPath) بواسطة متصفح النظام الافتراضي."
        }
        val file = context.getFile(sPath)
        val typeStr = if (file.isDirectory) "المجلد" else "الملف"
        return "[محاكاة (Dry-Run)] جاري طلب فتح $typeStr (موجود: ${file.exists()}) باسم (${file.name}) بواسطة مشغل النظام الافتراضي."
    }
}

// =====================================================================
// 8. MKDIR Command
// =====================================================================
class MkdirCommand : Command {
    override suspend fun execute(context: CommandContext): CommandResult {
        val sPath = context.args["path"] ?: return CommandResult(false, "❌ معامل المسار `--path` مطلوب لإنشاء مجلت.")
        val parents = context.flags.contains("parents") || context.args["parents"] == "true"

        val file = context.getFile(sPath)
        if (file.exists()) {
            if (file.isDirectory) {
                return CommandResult(true, "⚠️ المجلد موجود بالفعل: ${file.absolutePath}")
            } else {
                return CommandResult(false, "❌ يوجد ملف بنفس الاسم بالفعل بمسار: ${file.absolutePath}")
            }
        }

        try {
            val success = if (parents) {
                file.mkdirs()
            } else {
                file.mkdir()
            }

            if (success) {
                context.log("📁 إنشاء مجلد", "موفق: ${file.absolutePath} (parents=$parents)")
                return CommandResult(true, "✅ تم إنشاء المجلد بنجاح: ${file.absolutePath}")
            } else {
                val parent = file.parentFile
                if (parent != null && !parent.exists() && !parents) {
                    return CommandResult(false, "❌ تعذر إنشاء المجلد. الآباء غير موجودين بالمسار، يرجى تفعيل `--parents` لإنشاء المجلدات الأبوية.")
                }
                return CommandResult(false, "❌ فشل إنشاء المجلد بالمسار: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            return CommandResult(false, "❌ فشل إنشاء المجلد: ${e.message}")
        }
    }

    override suspend fun dryRun(context: CommandContext): String {
        val sPath = context.args["path"] ?: "غير محدد"
        val parents = context.flags.contains("parents") || context.args["parents"] == "true"
        return "[محاكاة (Dry-Run)] جاري إنشاء مجلد جديد بمسار ($sPath) مع ميزة الآباء (parents) = $parents"
    }
}

// =====================================================================
// 9. SELFTEST Command
// =====================================================================
class SelfTestCommand : Command {
    override suspend fun execute(context: CommandContext): CommandResult {
        val sysContext = context.context
        val timestamp = System.currentTimeMillis()
        val tempDir = File(sysContext.filesDir, "test_env_$timestamp")
        tempDir.mkdirs()

        try {
            // Create dummy file(s)
            val dummyFile = File(tempDir, "file.txt")
            dummyFile.writeText("محتوى وهمي للاختبار الذاتي.", Charsets.UTF_8)

            // Read the assets file
            val jsonString = try {
                sysContext.assets.open("test_scenarios.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                return CommandResult(false, "❌ تعذر قراءة ملف السيناريوهات من الأصول: ${e.message}")
            }

            val jsonArray = JSONArray(jsonString)
            val reportBuilder = java.lang.StringBuilder()
            reportBuilder.appendLine("=== تقرير نظام الاختبار الذاتي (Self-Test Lab) ===")
            reportBuilder.appendLine("التاريخ: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            reportBuilder.appendLine("مجلد الاختبار الآمن: ${tempDir.absolutePath}")
            reportBuilder.appendLine("--------------------------------------------------")

            val settings = mapOf<String, Any>(
                "absolute_path_handling" to "relative",
                "base_dir" to tempDir.absolutePath
            )
            val builderEngine = BuilderEngine(sysContext, settings)

            val totalTests = jsonArray.length()
            var passedCount = 0

            for (i in 0 until totalTests) {
                val sc = jsonArray.getJSONObject(i)
                val testName = sc.getString("name")
                val expected = sc.getString("expected")

                // Handle both single command and commands array
                val cmdText = if (sc.has("command")) {
                    sc.getString("command")
                } else if (sc.has("commands")) {
                    val arr = sc.getJSONArray("commands")
                    val sbCmd = java.lang.StringBuilder()
                    for (j in 0 until arr.length()) {
                        sbCmd.appendLine(arr.getString(j))
                    }
                    sbCmd.toString()
                } else {
                    ""
                }

                // Run processText on the scenario input
                val results = try {
                    builderEngine.processText(cmdText)
                } catch (e: Exception) {
                    emptyList()
                }

                val hasFailure = results.any { it.message.contains("❌") || it.message.contains("فشل") }
                val overallSuccess = results.isNotEmpty() && !hasFailure

                val actual = if (overallSuccess) {
                    if (expected == "multi_success") "multi_success" else "success"
                } else {
                    "failed"
                }

                val isPassed = actual == expected

                if (isPassed) {
                    passedCount++
                    reportBuilder.appendLine("✅ [$testName]")
                    reportBuilder.appendLine("   - النتيجة المتوقعة: $expected | الفعلية: $actual")
                    if (results.isNotEmpty()) {
                        reportBuilder.appendLine("   - التفاصيل: ${results.joinToString("; ") { it.message }}")
                    }
                } else {
                    reportBuilder.appendLine("❌ [$testName] - النص: $cmdText")
                    reportBuilder.appendLine("   - النتيجة المتوقعة: $expected | الفعلية: $actual")
                    if (results.isNotEmpty()) {
                        reportBuilder.appendLine("   - التفاصيل: ${results.joinToString("; ") { it.message }}")
                    }
                }
                reportBuilder.appendLine("--------------------------------------------------")
            }

            val overallPassed = passedCount == totalTests
            val overallMsg = if (overallPassed) {
                "🎉 نجاح تام لمختبر الاختبار الذاتي! تم اجتياز $passedCount من $totalTests اختبارات بنجاح."
            } else {
                "⚠️ فشل بعض الاختبارات! تم اجتياز $passedCount من $totalTests اختبارات فقط."
            }

            reportBuilder.appendLine("النتيجة الإجمالية:")
            reportBuilder.appendLine(overallMsg)

            val reportString = reportBuilder.toString()

            // Copy to clipboard
            withContext(Dispatchers.Main) {
                val clipboard = sysContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Self-Test Report", reportString))
                android.widget.Toast.makeText(sysContext, overallMsg, android.widget.Toast.LENGTH_LONG).show()
            }

            context.log("🧪 نظام الاختبار الذاتي", overallMsg)

            return CommandResult(overallPassed, overallMsg, reportString)

        } catch (e: Exception) {
            return CommandResult(false, "❌ فشل غير متوقع في نظام الاختبار الذاتي: ${e.message}")
        } finally {
            // Cleanup safe folder recursion
            tempDir.deleteRecursively()
        }
    }

    override suspend fun dryRun(context: CommandContext): String {
        return "[محاكاة (Dry-Run)] جاري تشغيل مختبر بيئة الاختبار الذاتي والتحقق من صحة محرك الأوامر."
    }
}

package com.example.engine

import android.content.Context
import android.os.Environment
import java.io.File
import org.json.JSONArray

object ProjectContextManager {

    var pendingText: String? = null

    private val blacklistedKeywords = setOf(
        "storage", "emulated", "0", "data", "user", "files", "download", "document", "android",
        "html", "body", "div", "head", "style", "script", "meta", "link", "class", "id", "href", "src", "doctype", "font", "color", "background",
        "function", "const", "var", "let", "return", "import", "export", "public", "private", "void", "int", "string", "boolean",
        "في", "من", "على", "كان", "هذا", "هذه", "الذي", "عن", "إلى", "أو", "ثم", "حيث", "كما", "مع", "ما", "لا", "قد", "إن", "أن", "لم", "لن", "كل", "بعض", "بين", "بعد", "قبل"
    )

    fun getBaseDir(context: Context): File {
        val path = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getString("base_dir_path", null)
        if (!path.isNullOrBlank()) {
            return File(path).also { it.mkdirs() }
        }
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            File(context.getExternalFilesDir(null), "SmartPlatform")
        } else {
            File(context.filesDir, "SmartPlatform")
        }.also { it.mkdirs() }
    }

    fun getCurrentProjectPath(context: Context): String {
        val prefs = context.getSharedPreferences("SmartCapturePrefs", Context.MODE_PRIVATE)
        return prefs.getString("current_project_path", "SmartInbox") ?: "SmartInbox"
    }

    fun getCurrentProjectDir(context: Context): File {
        return getProjectDir(getCurrentProjectPath(context), context)
    }

    fun setCurrentProjectPath(context: Context, path: String) {
        val prefs = context.getSharedPreferences("SmartCapturePrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("current_project_path", path).apply()
    }

    fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "في", "من", "على", "كان", "كانت", "عن", "إلى", "الى", "أن", "ان", "هو", "هي", "تم", "قبل",
            "بعد", "كل", "أو", "أم", "ثم", "حتى", "لا", "ما", "لم", "لن", "إذا", "كيف", "لماذا", "هذا",
            "هذه", "التي", "الذي", "الذين", "مع", "معنا", "لكن", "لقد", "وقد", "انه", "أنها", "صديقي",
            "the", "and", "a", "of", "to", "in", "is", "that", "it", "for", "on", "with", "as", "this"
        )
        val cleaned = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), " ")
        val words = cleaned.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 3 && !stopWords.contains(it) && !blacklistedKeywords.contains(it) }
        return words.distinct()
    }

    fun getProjectDir(projectPath: String, context: Context): File {
        val baseDir = getBaseDir(context)
        val file = File(projectPath)
        return if (file.isAbsolute) file else File(baseDir, projectPath)
    }

    fun reloadActiveTemplateKeywords(context: Context, projectPath: String) {
        val projectDir = getProjectDir(projectPath, context)
        val configFile = File(projectDir, "project_config.json")
        if (configFile.exists()) {
            try {
                val configContent = configFile.readText()
                val configObj = org.json.JSONObject(configContent)
                val foldersArray = configObj.optJSONArray("folders")
                val mergedKeywords = mutableListOf<String>()
                if (foldersArray != null) {
                    for (i in 0 until foldersArray.length()) {
                        val folderObj = foldersArray.getJSONObject(i)
                        val kwArray = folderObj.optJSONArray("keywords")
                        if (kwArray != null) {
                            for (j in 0 until kwArray.length()) {
                                val kw = kwArray.getString(j).lowercase().trim()
                                if (kw.length >= 3 && !blacklistedKeywords.contains(kw)) {
                                    mergedKeywords.add(kw)
                                }
                            }
                        }
                    }
                }
                val finalMerged = mergedKeywords.distinct()
                val file = File(projectDir, "keywords.json")
                val jsonArray = JSONArray()
                finalMerged.forEach { jsonArray.put(it) }
                file.writeText(jsonArray.toString(4))
            } catch (e: Exception) {
                // silent
            }
        }
    }

    fun loadProjectKeywords(projectPath: String, context: Context): List<String> {
        reloadActiveTemplateKeywords(context, projectPath)
        val file = File(getProjectDir(projectPath, context), "keywords.json")
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            val jsonArray = JSONArray(content)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val kw = jsonArray.getString(i).lowercase().trim()
                if (kw.length >= 3 && !blacklistedKeywords.contains(kw)) {
                    list.add(kw)
                }
            }
            list.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun updateProjectKeywords(projectPath: String, newKeywords: List<String>, context: Context) {
        val oldKeywords = loadProjectKeywords(projectPath, context)
        val cleanedNew = newKeywords.map { it.lowercase().trim() }
            .filter { it.length >= 3 && !blacklistedKeywords.contains(it) }
        val merged = (oldKeywords + cleanedNew).distinct()
        val file = File(getProjectDir(projectPath, context), "keywords.json")
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val jsonArray = JSONArray()
            merged.forEach { jsonArray.put(it) }
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            // silent catch
        }
    }

    fun calculateSimilarity(newText: String, projectKeywords: List<String>): Double {
        val cleanedProjKeywords = projectKeywords.map { it.lowercase().trim() }
            .filter { it.length >= 3 && !blacklistedKeywords.contains(it) }
        if (cleanedProjKeywords.isEmpty()) return 0.0
        val newKeywords = extractKeywords(newText)
        if (newKeywords.isEmpty()) return 0.0
        val common = newKeywords.filter { cleanedProjKeywords.contains(it) }.size
        return common.toDouble() / newKeywords.size.toDouble()
    }

    var isBypassed: Boolean = false

    fun shouldAskForContext(newText: String, context: Context): Boolean {
        if (isBypassed) return false
        val prefs = context.getSharedPreferences("SmartCapturePrefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enable_context_manager", true)
        if (!enabled) return false

        val currentProj = getCurrentProjectPath(context)
        val projKeywords = loadProjectKeywords(currentProj, context)
        if (projKeywords.isEmpty()) {
            // Auto learn initial keywords
            val keywords = extractKeywords(newText)
            if (keywords.isNotEmpty()) {
                updateProjectKeywords(currentProj, keywords, context)
            }
            return false
        }

        val similarity = calculateSimilarity(newText, projKeywords)
        return similarity < 0.2
    }

    fun suggestFolderName(text: String, context: Context): String {
        val prefs = context.getSharedPreferences("SmartCapturePrefs", Context.MODE_PRIVATE)
        val namingMode = prefs.getString("folder_naming_mode", "SMART") ?: "SMART"
        return when (namingMode) {
            "FIRST_LINE" -> {
                val lines = text.trim().lines()
                val firstLine = lines.firstOrNull { it.isNotBlank() } ?: "مجلد_جديد"
                var folderName = if (firstLine.length > 50) firstLine.take(47) + "..." else firstLine
                folderName = folderName.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), "_").trim()
                if (folderName.isEmpty()) "مجلد_جديد" else folderName
            }
            "MANUAL" -> {
                "MANUAL"
            }
            else -> { // SMART
                val keywords = extractKeywords(text)
                if (keywords.isNotEmpty()) {
                    keywords.take(2).joinToString("_")
                } else {
                    "مجلد_جديد"
                }
            }
        }
    }
}

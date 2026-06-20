package com.example.engine

import android.content.Context
import android.os.Environment
import java.io.File
import org.json.JSONArray

object ProjectContextManager {

    var pendingText: String? = null

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
            .filter { it.length > 2 && !stopWords.contains(it) }
        return words.distinct()
    }

    fun getProjectDir(projectPath: String, context: Context): File {
        val baseDir = getBaseDir(context)
        val file = File(projectPath)
        return if (file.isAbsolute) file else File(baseDir, projectPath)
    }

    fun loadProjectKeywords(projectPath: String, context: Context): List<String> {
        val file = File(getProjectDir(projectPath, context), "keywords.json")
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            val jsonArray = JSONArray(content)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun updateProjectKeywords(projectPath: String, newKeywords: List<String>, context: Context) {
        val oldKeywords = loadProjectKeywords(projectPath, context)
        val merged = (oldKeywords + newKeywords).distinct()
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
        if (projectKeywords.isEmpty()) return 0.0
        val newKeywords = extractKeywords(newText)
        if (newKeywords.isEmpty()) return 0.0
        val common = newKeywords.filter { projectKeywords.contains(it) }.size
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
}

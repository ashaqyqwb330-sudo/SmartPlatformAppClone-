package com.example.engine

import org.json.JSONObject

object TemplateParser {
    fun parse(jsonString: String): Result<ProjectTemplate> {
        return try {
            val trimmed = jsonString.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                return Result.failure(IllegalArgumentException("محتوى JSON غير صالح: يجب أن يبدأ بـ { وينتهي بـ }"))
            }
            val obj = JSONObject(trimmed)
            val projectName = when {
                obj.has("projectName") -> obj.getString("projectName")
                obj.has("project_name") -> obj.getString("project_name")
                else -> return Result.failure(IllegalArgumentException("اسم المشروع مفقود (projectName)"))
            }
            if (projectName.isBlank()) {
                return Result.failure(IllegalArgumentException("اسم المشروع لا يمكن أن يكون فارغاً"))
            }

            val foldersKey = when {
                obj.has("folders") -> "folders"
                obj.has("folders_list") -> "folders_list"
                else -> null
            }

            if (foldersKey == null) {
                return Result.failure(IllegalArgumentException("قائمة المجلدات مفقودة (folders)"))
            }

            val foldersArray = obj.getJSONArray(foldersKey)
            val folders = mutableListOf<FolderTemplate>()

            for (i in 0 until foldersArray.length()) {
                val folderObj = foldersArray.getJSONObject(i)
                val fName = if (folderObj.has("name")) folderObj.getString("name") else ""
                val fPath = if (folderObj.has("path")) folderObj.getString("path") else ""
                
                if (fName.isBlank() || fPath.isBlank()) {
                    return Result.failure(IllegalArgumentException("بيانات المجلد رقم ${i + 1} غير مكتملة (الاسم والمسار مطلوبان)"))
                }

                val fileTypes = mutableListOf<String>()
                val fileTypesKey = when {
                    folderObj.has("fileTypes") -> "fileTypes"
                    folderObj.has("file_types") -> "file_types"
                    else -> null
                }
                if (fileTypesKey != null) {
                    val ftArray = folderObj.getJSONArray(fileTypesKey)
                    for (j in 0 until ftArray.length()) {
                        fileTypes.add(ftArray.getString(j))
                    }
                }

                val keywords = mutableListOf<String>()
                val keywordsKey = when {
                    folderObj.has("keywords") -> "keywords"
                    folderObj.has("keyword_list") -> "keyword_list"
                    else -> null
                }
                if (keywordsKey != null) {
                    val kwArray = folderObj.getJSONArray(keywordsKey)
                    for (j in 0 until kwArray.length()) {
                        keywords.add(kwArray.getString(j))
                    }
                }

                folders.add(FolderTemplate(fName, fPath, fileTypes, keywords))
            }

            val templateVersion = when {
                obj.has("templateVersion") -> obj.getString("templateVersion")
                obj.has("template_version") -> obj.getString("template_version")
                else -> "1.0"
            }

            val settings = mutableMapOf<String, String>()
            if (obj.has("settings")) {
                val settingsObj = obj.getJSONObject("settings")
                val keys = settingsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    settings[key] = settingsObj.optString(key, "")
                }
            }

            Result.success(ProjectTemplate(templateVersion, projectName, folders, settings))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

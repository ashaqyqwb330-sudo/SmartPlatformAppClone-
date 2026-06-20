package com.example.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ProjectBuilder {
    fun build(template: ProjectTemplate, basePath: String): Result<String> {
        return try {
            // Sanitize project name for directory name
            val safeProjectName = template.projectName.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), "_").trim()
            val finalProjectName = if (safeProjectName.isEmpty()) "Project" else safeProjectName
            
            val projectDir = File(basePath, finalProjectName)
            if (!projectDir.exists()) {
                projectDir.mkdirs()
            }

            // Create each folder and its keywords.json
            for (folder in template.folders) {
                // Folder path is relative to project directory
                val fDir = File(projectDir, folder.path)
                fDir.mkdirs()

                // Create keywords.json for this folder
                val kwFile = File(fDir, "keywords.json")
                val kwArray = JSONArray()
                for (kw in folder.keywords) {
                    kwArray.put(kw)
                }
                kwFile.writeText(kwArray.toString(4))
            }

            // Create project_config.json in projectDir
            val configObj = JSONObject().apply {
                put("templateVersion", template.templateVersion)
                put("projectName", template.projectName)
                
                val foldersArray = JSONArray()
                for (folder in template.folders) {
                    val fObj = JSONObject().apply {
                        put("name", folder.name)
                        put("path", folder.path)
                        
                        val ftArray = JSONArray()
                        for (ft in folder.fileTypes) {
                            ftArray.put(ft)
                        }
                        put("fileTypes", ftArray)

                        val kwArray = JSONArray()
                        for (kw in folder.keywords) {
                            kwArray.put(kw)
                        }
                        put("keywords", kwArray)
                    }
                    foldersArray.put(fObj)
                }
                put("folders", foldersArray)

                template.settings?.let { settingsMap ->
                    val sObj = JSONObject()
                    for ((k, v) in settingsMap) {
                        sObj.put(k, v)
                    }
                    put("settings", sObj)
                }
            }

            val configFile = File(projectDir, "project_config.json")
            configFile.writeText(configObj.toString(4))

            Result.success(projectDir.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

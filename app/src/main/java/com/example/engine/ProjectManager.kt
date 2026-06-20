package com.example.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ProjectManager {
    private const val PREFS_NAME = "SmartCapturePrefs"
    private const val PROJECTS_KEY = "imported_projects_list"

    fun addProject(context: Context, path: String, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listJson = prefs.getString(PROJECTS_KEY, "[]") ?: "[]"
        val arr = try { JSONArray(listJson) } catch (e: Exception) { JSONArray() }
        
        // Remove existing with same path to avoid duplication
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("path") != path) {
                newArr.put(obj)
            }
        }
        
        val newObj = JSONObject().apply {
            put("path", path)
            put("name", name)
        }
        newArr.put(newObj)
        
        prefs.edit().putString(PROJECTS_KEY, newArr.toString()).apply()
    }

    fun removeProject(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listJson = prefs.getString(PROJECTS_KEY, "[]") ?: "[]"
        val arr = try { JSONArray(listJson) } catch (e: Exception) { JSONArray() }
        
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("path") != path) {
                newArr.put(obj)
            }
        }
        prefs.edit().putString(PROJECTS_KEY, newArr.toString()).apply()
    }

    fun getAllProjects(context: Context): List<Pair<String, String>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listJson = prefs.getString(PROJECTS_KEY, "[]") ?: "[]"
        val list = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(listJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val path = obj.optString("path", "")
                val name = obj.optString("name", "")
                if (path.isNotEmpty() && name.isNotEmpty()) {
                    list.add(Pair(path, name))
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        // Always add default "SmartInbox" if not present
        if (list.none { it.first == "SmartInbox" || it.first.endsWith("SmartInbox") }) {
            list.add(0, Pair("SmartInbox", "صندوق المذكرات الذكي (افتراضي)"))
        }
        return list
    }

    fun setActiveProject(context: Context, path: String) {
        ProjectContextManager.setCurrentProjectPath(context, path)
        ProjectContextManager.reloadActiveTemplateKeywords(context, path)
    }
}

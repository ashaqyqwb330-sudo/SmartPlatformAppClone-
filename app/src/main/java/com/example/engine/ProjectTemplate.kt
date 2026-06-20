package com.example.engine

data class FolderTemplate(
    val name: String,           // الاسم العربي: "دروس نظرية"
    val path: String,           // المسار الإنجليزي: "theory"
    val fileTypes: List<String>,// الأنواع: ["نظري"]
    val keywords: List<String>  // الكلمات المفتاحية
)

data class ProjectTemplate(
    val templateVersion: String = "1.0",
    val projectName: String,
    val folders: List<FolderTemplate>,
    val settings: Map<String, String>? = null  // إعدادات افتراضية
)

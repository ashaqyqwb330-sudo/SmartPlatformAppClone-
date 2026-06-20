package com.example.engine

data class SavedFileInfo(
    val fileName: String,
    val filePath: String,
    val fileType: String  // "HTML", "CODE", "TEXT"
)

data class CaptureResult(
    val savedFiles: List<SavedFileInfo> = emptyList(),
    val ignoredCodes: Int = 0,
    val ignoredShortTexts: Int = 0,
    val ignoredDuplicates: Int = 0,
    val errors: List<String> = emptyList()
)

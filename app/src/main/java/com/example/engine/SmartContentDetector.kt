package com.example.engine

object SmartContentDetector {
    /**
     * Determines whether the given text is raw HTML (INDEX_ONLY) or plain text to be wrapped (CONVERT).
     */
    fun detectContentMode(text: String): String {
        val lower = text.lowercase()
        return if (lower.contains("<html") || 
            lower.contains("<body") || 
            lower.contains("<head") || 
            lower.contains("<div") || 
            lower.contains("<table")
        ) {
            "INDEX_ONLY"
        } else {
            "CONVERT"
        }
    }
}

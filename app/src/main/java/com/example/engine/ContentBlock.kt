package com.example.engine

data class ContentBlock(
    val type: ContentType,
    val content: String,
    val language: String = ""
)

enum class ContentType {
    CODE,
    HTML,
    TEXT
}

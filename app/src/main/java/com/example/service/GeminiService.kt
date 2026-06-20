package com.example.service

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class GeminiService(private val context: Context) {

    companion object {
        private const val TAG = "GeminiService"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    }

    private val client = OkHttpClient()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    /**
     * Retrieves the Gemini API Key from either BuildConfig, System environment, or Shared Preferences.
     */
    fun getApiKey(): String {
        // Attempt 1: BuildConfig key
        val buildConfigKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Throwable) {
            ""
        }
        if (buildConfigKey.isNotBlank() && buildConfigKey != "MY_GEMINI_API_KEY") {
            return buildConfigKey
        }

        // Attempt 2: Settings preferences
        val sharedPrefs = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val customKey = sharedPrefs.getString("custom_gemini_api_key", "") ?: ""
        if (customKey.isNotBlank()) {
            return customKey
        }

        // Attempt 3: Environment var fallback
        return System.getenv("GEMINI_API_KEY") ?: ""
    }

    /**
     * Sends a request to Gemini API and returns the generated text.
     */
    suspend fun generateContent(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("API Key is missing. Please configure it in Settings or the Secrets panel."))
        }

        val customSystemInstruction = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
            .getString("ai_system_instruction", "") ?: ""

        val systemPrompt = (if (customSystemInstruction.isNotBlank()) customSystemInstruction + "\n\n" else "") +
                "You are the AI assistant inside Smart Platform Engine (المنصة الذكية). " +
                "You can generate structural text containing builder commands to write files or execute commands. " +
                "Always format your instructions clearly. " +
                "Example response structure:\n" +
                "Here is the code you requested:\n" +
                "// @builder:file sample.py\n" +
                "print('Hello from Gemini!')\n" +
                "// @builder:end\n" +
                "You can use @builder:file, @builder:mode, @executor:run, and @treedoc:report commands in Arabic or English."

        val jsonRequest = """
            {
              "contents": [
                {
                  "parts": [
                    { "text": "${escapeJson(systemPrompt + "\n\nUser request: " + prompt)}" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val requestBody = jsonRequest.toRequestBody("application/json".toMediaType())
        val url = "$BASE_URL?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "Request failed: $errorBody")
                    return@withContext Result.failure(Exception("HTTP Error ${response.code}: $errorBody"))
                }

                val body = response.body?.string() ?: ""
                val text = parseGeminiResponse(body)
                if (text != null) {
                    Result.success(text)
                } else {
                    Result.failure(Exception("Failed to parse response code or candidates block from Gemini API result."))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network exception: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun parseGeminiResponse(json: String): String? {
        return try {
            val adapter = moshi.adapter(Map::class.java)
            val root = adapter.fromJson(json) ?: return null
            val candidates = root["candidates"] as? List<*> ?: return null
            val firstCandidate = candidates.firstOrNull() as? Map<*, *> ?: return null
            val content = firstCandidate["content"] as? Map<*, *> ?: return null
            val parts = content["parts"] as? List<*> ?: return null
            val firstPart = parts.firstOrNull() as? Map<*, *> ?: return null
            firstPart["text"] as? String
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing error: ${e.message}")
            null
        }
    }
}

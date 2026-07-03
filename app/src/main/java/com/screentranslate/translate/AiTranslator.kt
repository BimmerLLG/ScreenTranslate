package com.screentranslate.translate

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AiTranslator(
    private var endpoint: String = "https://api.openai.com/v1/chat/completions",
    private var apiKey: String = "",
    private var model: String = "gpt-4o-mini"
) : Translator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun updateConfig(endpoint: String, apiKey: String, model: String) {
        this.endpoint = endpoint
        this.apiKey = apiKey
        this.model = model
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        return withContext(Dispatchers.IO) {
            val sourceHint = if (sourceLang.isBlank()) "" else " from $sourceLang"
            val systemPrompt = "You are a translator. Translate the following text$sourceHint to $targetLang. Only return the translated text, no explanations, no quotes."

            val messages = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to text)
            )

            val requestBody = mapOf(
                "model" to model,
                "messages" to messages,
                "temperature" to 0.3
            )

            val jsonBody = gson.toJson(requestBody)
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                throw RuntimeException("API error ${response.code}: $responseBody")
            }

            val jsonResponse = gson.fromJson(responseBody, Map::class.java)
            val choices = jsonResponse["choices"] as? List<Map<String, Any>>
            val message = choices?.firstOrNull()?.get("message") as? Map<String, String>
            message?.get("content")?.trim() ?: throw RuntimeException("Unexpected API response: $responseBody")
        }
    }

    override suspend fun batchTranslate(
        texts: List<String>,
        sourceLang: String,
        targetLang: String
    ): List<String> {
        val results = mutableListOf<String>()
        for (text in texts) {
            results.add(translate(text, sourceLang, targetLang))
        }
        return results
    }
}

package com.screentranslate.translate

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.screentranslate.logger.L
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
    private var model: String = "gpt-4o-mini",
    private var customPrompt: String = "",
    private var thinkingEnabled: Boolean = false,
    private var reasoningEffort: String = "medium"
) : Translator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun updateConfig(
        endpoint: String,
        apiKey: String,
        model: String,
        customPrompt: String = this.customPrompt,
        thinkingEnabled: Boolean = this.thinkingEnabled,
        reasoningEffort: String = this.reasoningEffort
    ) {
        L.d("AiTrans", "配置更新: endpoint=${endpoint.take(50)}..., model=$model, prompt=${customPrompt.length}字, thinking=$thinkingEnabled, effort=$reasoningEffort")
        this.endpoint = endpoint
        this.apiKey = apiKey
        this.model = model
        this.customPrompt = customPrompt
        this.thinkingEnabled = thinkingEnabled
        this.reasoningEffort = reasoningEffort
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        return withContext(Dispatchers.IO) {
            val sourceHint = if (sourceLang.isBlank()) "" else " from $sourceLang"
            val systemContent = if (customPrompt.isNotBlank()) {
                customPrompt
            } else {
                "You are a translator. Translate the following text$sourceHint to $targetLang. Only return the translated text, no explanations, no quotes."
            }

            val messages = listOf(
                mapOf("role" to "system", "content" to systemContent),
                mapOf("role" to "user", "content" to text)
            )

            val body = mutableMapOf<String, Any>(
                "model" to model,
                "messages" to messages,
                "temperature" to 0.3
            )

            if (thinkingEnabled) {
                body["thinking"] = mapOf("type" to "enabled")
                body["reasoning_effort"] = reasoningEffort
            }

            val jsonBody = gson.toJson(body)
            L.d("AiTrans", "HTTP POST: ${endpoint.take(50)}..., body=${jsonBody.length}字节, model=$model")
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                L.e("AiTrans", "翻译请求失败", e)
                throw e
            }
            val responseBody = response.body?.string()
            L.d("AiTrans", "HTTP 响应: status=${response.code}, body=${(responseBody?.length ?: 0)}字节")

            if (!response.isSuccessful) {
                throw RuntimeException("API error ${response.code}: $responseBody")
            }

            L.d("AiTrans", "解析响应 JSON")
            val jsonResponse = gson.fromJson(responseBody, Map::class.java)
                ?: throw RuntimeException("响应为空")

            @Suppress("UNCHECKED_CAST")
            val choices = jsonResponse["choices"] as? List<Map<String, Any>>
                ?: throw RuntimeException("无 choices 字段: $responseBody")

            val message = choices.firstOrNull()?.get("message") as? Map<String, Any>
                ?: throw RuntimeException("无 message 字段: $responseBody")

            val content = (message["content"] as? String)?.trim()
                ?: throw RuntimeException("无 content 字段: $responseBody")

            L.d("AiTrans", "翻译成功: \"${content.take(50)}\"")
            content
        }
    }

    suspend fun batchTranslateJson(
        texts: List<String>, sourceLang: String, targetLang: String
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val sourceHint = if (sourceLang.isBlank()) "" else " from $sourceLang"
            val systemContent = if (customPrompt.isNotBlank()) {
                "$customPrompt\nReturn ONLY a JSON array of translated strings. No extra text."
            } else {
                "You are a translator. Translate each text$sourceHint to $targetLang. Return ONLY a JSON array of translations in exact order. No extra text, no markdown, no explanation."
            }

            val jsonArr = gson.toJson(texts)
            val messages = listOf(
                mapOf("role" to "system", "content" to systemContent),
                mapOf("role" to "user", "content" to jsonArr)
            )

            val body = mutableMapOf<String, Any>(
                "model" to model,
                "messages" to messages,
                "temperature" to 0.3
            )

            if (thinkingEnabled) {
                body["thinking"] = mapOf("type" to "enabled")
                body["reasoning_effort"] = reasoningEffort
            }

            val jsonBody = gson.toJson(body)
            L.d("AiTrans", "批量翻译: ${texts.size}条 ${jsonBody.length}字节")

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                L.e("AiTrans", "批量翻译失败", e)
                throw e
            }
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                throw RuntimeException("API error ${response.code}: $responseBody")
            }

            val jsonResponse = gson.fromJson(responseBody, Map::class.java)
                ?: throw RuntimeException("响应为空")

            @Suppress("UNCHECKED_CAST")
            val choices = jsonResponse["choices"] as? List<Map<String, Any>>
                ?: throw RuntimeException("无 choices")

            val message = choices.firstOrNull()?.get("message") as? Map<String, Any>
                ?: throw RuntimeException("无 message")

            var content = (message["content"] as? String)?.trim()
                ?: throw RuntimeException("无 content")

            // 清理 AI 可能包裹的 markdown 或多余文字
            content = content.trim()
            if (content.startsWith("```")) {
                content = content.removePrefix("```json").removePrefix("```").trimEnd('`').trim()
            }

            val result: List<String> = try {
                gson.fromJson(content, object : TypeToken<List<String>>() {}.type)
            } catch (e: Exception) {
                L.e("AiTrans", "批量翻译 JSON 解析失败: $content", e)
                // 回退：假装逐条翻译失败
                throw RuntimeException("批量翻译 JSON 解析失败: ${e.message}")
            }

            L.d("AiTrans", "批量翻译成功: ${result.size}/${texts.size}条")
            result
        }
    }

    override suspend fun batchTranslate(
        texts: List<String>,
        sourceLang: String,
        targetLang: String
    ): List<String> {
        return texts.map { translate(it, sourceLang, targetLang) }
    }
}

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

/**
 * BiliIndexTranslator - 哔哩哔哩 Index-Translate 免费翻译
 *
 * 直接调用 B 站公开的免费翻译接口（https://index-translate.bilibili.com/v1），
 * 不需要 API Key。关键点：
 * - 必须注入 chat_template_kwargs={"enable_thinking": false}，否则模型会输出
 *   思维链而不是译文。
 * - temperature=0 保证输出稳定。
 * - 上游免费网关在长时间静默后会返回 504，所以请求始终走流式并在这里拼接结果。
 */
class BiliIndexTranslator(
    private var endpoint: String = DEFAULT_ENDPOINT,
    private var model: String = DEFAULT_MODEL
) : Translator {

    companion object {
        const val DEFAULT_ENDPOINT = "https://index-translate.bilibili.com/v1/chat/completions"
        const val DEFAULT_MODEL = "Index-Translate-35B-A3B"

        val MODELS = listOf(
            "Index-Translate-35B-A3B",
            "Index-Translate-9B",
            "Index-Translate-2B"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun updateConfig(endpoint: String = this.endpoint, model: String = this.model) {
        L.d("BiliTrans", "配置更新: endpoint=${endpoint.take(60)}..., model=$model")
        this.endpoint = endpoint
        this.model = model
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return text

        return withContext(Dispatchers.IO) {
            val systemContent = buildSystemPrompt(sourceLang, targetLang)
            val messages = listOf(
                mapOf("role" to "system", "content" to systemContent),
                mapOf("role" to "user", "content" to text)
            )
            val result = requestCompletion(messages)
            L.d("BiliTrans", "翻译成功: \"${text.take(20)}\" -> \"${result.take(20)}\"")
            result
        }
    }

    /**
     * 一次请求翻译多条文本，要求模型返回 JSON 数组。
     * 批量失败时由上层回退到逐条翻译。
     */
    suspend fun batchTranslateJson(
        texts: List<String>, sourceLang: String, targetLang: String
    ): List<String> {
        if (texts.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            val systemContent = buildSystemPrompt(sourceLang, targetLang) +
                "\nReturn ONLY a JSON array of translated strings in exact order. " +
                "No extra text, no markdown, no explanation."
            val messages = listOf(
                mapOf("role" to "system", "content" to systemContent),
                mapOf("role" to "user", "content" to gson.toJson(texts))
            )

            var content = requestCompletion(messages).trim()
            if (content.startsWith("```")) {
                content = content.removePrefix("```json").removePrefix("```").trimEnd('`').trim()
            }
            val result: List<String> = try {
                gson.fromJson(content, object : TypeToken<List<String>>() {}.type)
            } catch (e: Exception) {
                L.e("BiliTrans", "批量翻译 JSON 解析失败: $content", e)
                throw RuntimeException("批量翻译 JSON 解析失败: ${e.message}")
            }
            L.d("BiliTrans", "批量翻译成功: ${result.size}/${texts.size}条")
            result
        }
    }

    override suspend fun batchTranslate(
        texts: List<String>, sourceLang: String, targetLang: String
    ): List<String> {
        return texts.map { translate(it, sourceLang, targetLang) }
    }

    private fun buildSystemPrompt(sourceLang: String, targetLang: String): String {
        val target = targetLang.ifBlank { "中文" }
        val hint = if (sourceLang.isBlank()) "" else " from $sourceLang"
        return "You are a professional translator. Translate the text$hint to $target. " +
            "Only return the translated text, no explanations, no quotes."
    }

    /** 以流式请求上游并拼接 delta 文本，返回完整译文。 */
    private fun requestCompletion(messages: List<Map<String, String>>): String {
        val body = mapOf(
            "model" to model,
            "messages" to messages,
            "temperature" to 0.0,
            "stream" to true,
            "chat_template_kwargs" to mapOf("enable_thinking" to false)
        )
        val jsonBody = gson.toJson(body)

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "ScreenTranslate/1.0")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            L.e("BiliTrans", "翻译请求失败", e)
            throw e
        }

        response.use { resp ->
            val responseBody = resp.body ?: throw RuntimeException("响应为空")
            if (!resp.isSuccessful) {
                throw RuntimeException("API error ${resp.code}: ${responseBody.string().take(200)}")
            }

            val sb = StringBuilder()
            val source = responseBody.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val obj = try {
                    gson.fromJson(data, Map::class.java)
                } catch (_: Exception) {
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val choices = obj["choices"] as? List<Map<String, Any>> ?: continue
                @Suppress("UNCHECKED_CAST")
                val delta = (choices.firstOrNull()?.get("delta") as? Map<String, Any>)
                    ?.get("content") as? String
                if (!delta.isNullOrEmpty()) sb.append(delta)
            }

            val result = sb.toString().trim()
            if (result.isEmpty()) throw RuntimeException("上游返回空译文")
            return result
        }
    }
}

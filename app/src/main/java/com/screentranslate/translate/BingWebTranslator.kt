package com.screentranslate.translate

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.screentranslate.logger.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class BingWebTranslator : Translator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return text

        return withContext(Dispatchers.IO) {
            try {
                val sl = mapLanguageCode(sourceLang).ifBlank { "auto-detect" }
                val tl = mapLanguageCode(targetLang)

                val body = FormBody.Builder()
                    .add("fromLang", sl)
                    .add("toLang", tl)
                    .add("text", text)
                    .build()

                val request = Request.Builder()
                    .url("https://www.bing.com/ttranslatev3")
                    .header("User-Agent", "Mozilla/5.0")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful || responseBody == null) {
                    throw RuntimeException("Bing Web API error: ${response.code}")
                }

                val jsonArray = gson.fromJson(responseBody, Array::class.java) as Array<Map<String, Any>>
                val translations = jsonArray[0]["translations"] as? List<Map<String, Any>>
                val result = translations?.get(0)?.get("text") as? String ?: ""

                L.d("BingWebTrans", "翻译成功: \"${text.take(20)}\" -> \"${result.take(20)}\"")
                result
            } catch (e: Exception) {
                L.e("BingWebTrans", "翻译失败: \"${text.take(20)}\"", e)
                throw e
            }
        }
    }

    override suspend fun batchTranslate(texts: List<String>, sourceLang: String, targetLang: String): List<String> {
        return texts.map { translate(it, sourceLang, targetLang) }
    }

    private fun mapLanguageCode(lang: String): String {
        return when (lang.lowercase()) {
            "中文", "chinese", "zh" -> "zh-Hans"
            "英语", "english", "en" -> "en"
            "日语", "japanese", "ja" -> "ja"
            "韩语", "韩文", "korean", "ko" -> "ko"
            "法语", "french", "fr" -> "fr"
            "德语", "german", "de" -> "de"
            "俄语", "russian", "ru" -> "ru"
            "西班牙语", "spanish", "es" -> "es"
            "意大利语", "italian", "it" -> "it"
            "葡萄牙语", "portuguese", "pt" -> "pt"
            "泰语", "thai", "th" -> "th"
            "越南语", "vietnamese", "vi" -> "vi"
            "阿拉伯语", "arabic", "ar" -> "ar"
            "繁体中文", "zh-TW" -> "zh-Hant"
            else -> lang.ifBlank { "auto-detect" }
        }
    }
}

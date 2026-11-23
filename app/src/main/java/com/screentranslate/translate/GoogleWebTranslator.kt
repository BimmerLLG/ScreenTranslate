package com.screentranslate.translate

import com.google.gson.Gson
import com.screentranslate.logger.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google 网页翻译器
 * 使用 Google Translate 的非官方 API，无需 API Key
 */
class GoogleWebTranslator : Translator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (text.isBlank()) return text

        return withContext(Dispatchers.IO) {
            try {
                val sl = mapLanguageCode(sourceLang).ifBlank { "auto" }
                val tl = mapLanguageCode(targetLang)

                val encodedText = URLEncoder.encode(text, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encodedText"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful || responseBody == null) {
                    throw RuntimeException("Google Web API error: ${response.code}")
                }

                // 解析响应
                val jsonArray = gson.fromJson(responseBody, Array<Array<Array<String?>>>::class.java)
                val translatedText = StringBuilder()

                jsonArray[0]?.forEach { sentence ->
                    sentence?.get(0)?.let { translatedText.append(it) }
                }

                val result = translatedText.toString()
                L.d("GoogleWebTrans", "翻译成功: \"${text.take(20)}\" -> \"${result.take(20)}\"")
                result
            } catch (e: Exception) {
                L.e("GoogleWebTrans", "翻译失败: \"${text.take(20)}\"", e)
                throw e
            }
        }
    }

    override suspend fun batchTranslate(texts: List<String>, sourceLang: String, targetLang: String): List<String> {
        return texts.map { translate(it, sourceLang, targetLang) }
    }

    /**
     * 将语言名称映射为 Google Translate API 的语言代码
     */
    private fun mapLanguageCode(lang: String): String {
        return when (lang.lowercase()) {
            "中文", "chinese", "zh" -> "zh-CN"
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
            "繁体中文", "zh-TW" -> "zh-TW"
            else -> lang.ifBlank { "auto" }
        }
    }
}

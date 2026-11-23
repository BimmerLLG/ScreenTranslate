package com.screentranslate.translate

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.screentranslate.logger.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BaiduWebTranslator : Translator {

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
                val url = "https://fanyi.baidu.com/transapi?from=$sl&to=$tl&query=$encodedText"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://fanyi.baidu.com/")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful || responseBody == null) {
                    throw RuntimeException("Baidu Web API error: ${response.code}")
                }

                val jsonMap = gson.fromJson(responseBody, Map::class.java)
                val data = jsonMap["data"] as? List<Map<String, Any>>
                val result = StringBuilder()
                data?.forEach { item ->
                    val dst = item["dst"] as? String ?: ""
                    if (result.isNotEmpty()) result.append("\n")
                    result.append(dst)
                }

                val finalResult = result.toString()
                L.d("BaiduWebTrans", "翻译成功: \"${text.take(20)}\" -> \"${finalResult.take(20)}\"")
                finalResult
            } catch (e: Exception) {
                L.e("BaiduWebTrans", "翻译失败: \"${text.take(20)}\"", e)
                throw e
            }
        }
    }

    override suspend fun batchTranslate(texts: List<String>, sourceLang: String, targetLang: String): List<String> {
        return texts.map { translate(it, sourceLang, targetLang) }
    }

    private fun mapLanguageCode(lang: String): String {
        return when (lang.lowercase()) {
            "中文", "chinese", "zh" -> "zh"
            "英语", "english", "en" -> "en"
            "日语", "japanese", "ja" -> "jp"
            "韩语", "韩文", "korean", "ko" -> "kor"
            "法语", "french", "fr" -> "fra"
            "德语", "german", "de" -> "de"
            "俄语", "russian", "ru" -> "ru"
            "西班牙语", "spanish", "es" -> "spa"
            "意大利语", "italian", "it" -> "it"
            "葡萄牙语", "portuguese", "pt" -> "pt"
            "泰语", "thai", "th" -> "th"
            "越南语", "vietnamese", "vi" -> "vie"
            "阿拉伯语", "arabic", "ar" -> "ara"
            "繁体中文", "zh-TW" -> "cht"
            else -> lang.ifBlank { "auto" }
        }
    }
}

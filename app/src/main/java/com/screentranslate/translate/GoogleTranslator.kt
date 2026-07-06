package com.screentranslate.translate

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.screentranslate.logger.L
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GoogleTranslator - Google ML Kit 离线翻译实现
 * 使用 Google 的本地翻译能力，无需网络和 API Key
 * 首次使用需要下载语言包
 */
class GoogleTranslator(private val context: Context) : Translator {

    // 翻译器缓存，避免重复创建
    private val translatorCache = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()

    /**
     * 语言名称到语言代码的映射
     * 支持中文、英文、日文、韩文等多种语言
     */
    private fun getLangCode(language: String): String {
        return when (language.lowercase()) {
            "中文", "zh", "chinese" -> TranslateLanguage.CHINESE
            "英语", "en", "english" -> TranslateLanguage.ENGLISH
            "日语", "ja", "japanese" -> TranslateLanguage.JAPANESE
            "韩语", "ko", "korean" -> TranslateLanguage.KOREAN
            "法语", "fr", "french" -> TranslateLanguage.FRENCH
            "德语", "de", "german" -> TranslateLanguage.GERMAN
            "俄语", "ru", "russian" -> TranslateLanguage.RUSSIAN
            "西班牙语", "es", "spanish" -> TranslateLanguage.SPANISH
            "意大利语", "it", "italian" -> TranslateLanguage.ITALIAN
            "葡萄牙语", "pt", "portuguese" -> TranslateLanguage.PORTUGUESE
            "泰语", "th", "thai" -> TranslateLanguage.THAI
            "越南语", "vi", "vietnamese" -> TranslateLanguage.VIETNAMESE
            "阿拉伯语", "ar", "arabic" -> TranslateLanguage.ARABIC
            else -> language  // 如果没有匹配，返回原值
        }
    }

    /**
     * 获取或创建翻译器实例
     * 使用缓存避免重复创建
     */
    private fun getOrCreateTranslator(source: String, target: String): com.google.mlkit.nl.translate.Translator {
        val key = "$source->$target"
        return translatorCache.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            Translation.getClient(options)
        }
    }

    /**
     * 使用 Google ML Kit 进行翻译
     * 会自动下载语言包（需要 WiFi）
     */
    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        val source = getLangCode(sourceLang)
        val target = getLangCode(targetLang)

        L.d("GoogleTrans", "翻译: \"${text.take(30)}\" ${sourceLang}→${targetLang}")

        if (source == target) {
            L.d("GoogleTrans", "源语言==目标语言, 直接返回原文")
            return text
        }

        if (source.isBlank()) {
            throw IllegalArgumentException("源语言未设置，请在设置中配置源语言")
        }

        val translator = getOrCreateTranslator(source, target)

        return suspendCancellableCoroutine { cont ->
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    L.d("GoogleTrans", "模型准备就绪, 开始翻译")
                    translator.translate(text)
                        .addOnSuccessListener { result ->
                            L.d("GoogleTrans", "翻译结果: \"${result.take(50)}\"")
                            cont.resume(result)
                        }
                        .addOnFailureListener { e ->
                            L.e("GoogleTrans", "Google翻译失败", e)
                            cont.resumeWithException(e)
                        }
                }
                .addOnFailureListener { e ->
                    L.e("GoogleTrans", "Google翻译失败", e)
                    cont.resumeWithException(e)
                }
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

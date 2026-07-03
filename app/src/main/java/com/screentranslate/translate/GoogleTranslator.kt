package com.screentranslate.translate

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GoogleTranslator(private val context: Context) : Translator {

    private val translatorCache = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()

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
            else -> language
        }
    }

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

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        val source = getLangCode(sourceLang)
        val target = getLangCode(targetLang)

        if (source == target) return text

        val translator = getOrCreateTranslator(source, target)

        return suspendCancellableCoroutine { cont ->
            val conditions = DownloadConditions.Builder().requireWifi().build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    translator.translate(text)
                        .addOnSuccessListener { result ->
                            cont.resume(result)
                        }
                        .addOnFailureListener { e ->
                            cont.resumeWithException(e)
                        }
                }
                .addOnFailureListener { e ->
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

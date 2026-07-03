package com.screentranslate.translate

import android.content.Context
import android.content.SharedPreferences
import com.screentranslate.collector.TextNode

class TranslationManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("translate_prefs", Context.MODE_PRIVATE)

    val googleTranslator = GoogleTranslator(context)
    val aiTranslator = AiTranslator()

    private val cache = LinkedHashMap<String, String>(200, 0.75f, true)

    data class TranslationResult(
        val original: String,
        val translated: String,
        val bounds: android.graphics.Rect
    )

    init {
        loadAiConfig()
    }

    fun loadAiConfig() {
        val endpoint = prefs.getString("ai_endpoint", "https://api.openai.com/v1/chat/completions") ?: ""
        val apiKey = prefs.getString("ai_api_key", "") ?: ""
        val model = prefs.getString("ai_model", "gpt-4o-mini") ?: ""
        aiTranslator.updateConfig(endpoint, apiKey, model)
    }

    fun getSourceLang(): String = prefs.getString("source_lang", "") ?: ""
    fun getTargetLang(): String = prefs.getString("target_lang", "中文") ?: "中文"
    fun getTranslationMode(): String = prefs.getString("translation_mode", "ai") ?: "ai"

    suspend fun translateNodes(nodes: List<TextNode>): List<TranslationResult> {
        val results = mutableListOf<TranslationResult>()
        val sourceLang = getSourceLang()
        val targetLang = getTargetLang()
        val mode = getTranslationMode()

        for (node in nodes) {
            val cacheKey = "${node.text}|$sourceLang|$targetLang|$mode"
            val translated = cache.getOrPut(cacheKey) {
                try {
                    when (mode) {
                        "ai" -> aiTranslator.translate(node.text, sourceLang, targetLang)
                        "google" -> googleTranslator.translate(node.text, sourceLang, targetLang)
                        else -> aiTranslator.translate(node.text, sourceLang, targetLang)
                    }
                } catch (e: Exception) {
                    "[翻译失败] ${node.text}"
                }
            }
            results.add(TranslationResult(node.text, translated, node.bounds))
        }

        return results
    }

    suspend fun testTranslation(text: String, mode: String): String {
        val sourceLang = getSourceLang()
        val targetLang = getTargetLang()
        return try {
            when (mode) {
                "ai" -> aiTranslator.translate(text, sourceLang, targetLang)
                "google" -> googleTranslator.translate(text, sourceLang, targetLang)
                else -> aiTranslator.translate(text, sourceLang, targetLang)
            }
        } catch (e: Exception) {
            "错误: ${e.message}"
        }
    }
}

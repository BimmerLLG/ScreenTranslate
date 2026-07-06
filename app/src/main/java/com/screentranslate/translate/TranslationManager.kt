package com.screentranslate.translate

import android.content.Context
import android.content.SharedPreferences
import com.screentranslate.collector.TextNode
import com.screentranslate.logger.L
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        val customPrompt = prefs.getString("custom_prompt", "") ?: ""
        val thinkingEnabled = prefs.getBoolean("thinking_enabled", false)
        val reasoningEffort = prefs.getString("reasoning_effort", "medium") ?: "medium"
        aiTranslator.updateConfig(endpoint, apiKey, model, customPrompt, thinkingEnabled, reasoningEffort)
        L.d("TranslateMgr", "AI配置已加载: model=$model, thinking=$thinkingEnabled")
    }

    fun getSourceLang(): String = prefs.getString("source_lang", "") ?: ""
    fun getTargetLang(): String = prefs.getString("target_lang", "中文") ?: "中文"
    fun getTranslationMode(): String = prefs.getString("translation_mode", "ai") ?: "ai"
    fun getBatchMode(): String = prefs.getString("batch_mode", "batch") ?: "batch"
    fun getBatchMaxChars(): Int = prefs.getInt("batch_max_chars", 2000).coerceIn(500, 3000)

    fun saveEnabledState(enabled: Boolean) {
        prefs.edit().putBoolean("service_enabled", enabled).apply()
    }

    fun isEnabled(): Boolean = prefs.getBoolean("service_enabled", false)

    suspend fun translateNodes(nodes: List<TextNode>): List<TranslationResult> {
        val sourceLang = getSourceLang()
        val targetLang = getTargetLang()
        val mode = getTranslationMode()
        val batchMode = getBatchMode()

        // 过滤中文
        val targetNodes = nodes.filter { !it.text.any { c -> c in '\u4E00'..'\u9FFF' } }
        if (targetNodes.isEmpty()) return emptyList()

        L.d("TranslateMgr", "翻译 ${targetNodes.size}/${nodes.size} 个节点, 模式=$mode, 策略=$batchMode")

        // 分离缓存命中/未命中
        val cached = mutableListOf<TranslationResult>()
        val uncached = mutableListOf<TextNode>()
        for (node in targetNodes) {
            val cacheKey = "${node.text}|$sourceLang|$targetLang|$mode"
            val cachedText = cache[cacheKey]
            if (cachedText != null) {
                cached.add(TranslationResult(node.text, cachedText, node.bounds))
            } else {
                uncached.add(node)
            }
        }

        val fresh = if (uncached.isEmpty()) {
            emptyList()
        } else {
            L.d("TranslateMgr", "缓存命中=${cached.size}, 需翻译=${uncached.size}")
            when {
                mode == "ai" && batchMode == "batch" -> translateBatch(uncached, sourceLang, targetLang)
                else -> translateParallel(uncached, sourceLang, targetLang, mode)
            }
        }

        return cached + fresh
    }

    /** 打包模式：分组后每组建 JSON 一次发送，多组并行 */
    private suspend fun translateBatch(
        nodes: List<TextNode>, sourceLang: String, targetLang: String
    ): List<TranslationResult> = coroutineScope {
        val maxChars = getBatchMaxChars()
        val maxItems = 10 // 每批最多10条，避免API超时
        val groups = mutableListOf<MutableList<TextNode>>(mutableListOf())
        var currentChars = 0
        for (node in nodes) {
            val textLen = node.text.length
            val group = groups.last()
            if ((currentChars + textLen > maxChars || group.size >= maxItems) && group.isNotEmpty()) {
                groups.add(mutableListOf(node))
                currentChars = textLen
            } else {
                group.add(node)
                currentChars += textLen
            }
        }

        L.d("TranslateMgr", "打包模式: ${groups.size}组, 每组≤${maxChars}字/${maxItems}条")

        groups.map { group ->
            async {
                val texts = group.map { it.text }
                try {
                    val translations = aiTranslator.batchTranslateJson(texts, sourceLang, targetLang)
                    group.zip(translations).map { (node, translated) ->
                        val cacheKey = "${node.text}|$sourceLang|$targetLang|ai"
                        cache[cacheKey] = translated
                        TranslationResult(node.text, translated, node.bounds)
                    }
                } catch (e: Exception) {
                    L.e("TranslateMgr", "批量翻译失败，回退逐条", e)
                    texts.mapIndexed { i, text ->
                        val fallback = try {
                            aiTranslator.translate(text, sourceLang, targetLang)
                        } catch (e2: Exception) { "[失败] $text" }
                        TranslationResult(text, fallback, group[i].bounds)
                    }
                }
            }
        }.awaitAll().flatten()
    }

    /** 逐条并行模式 */
    private suspend fun translateParallel(
        nodes: List<TextNode>, sourceLang: String, targetLang: String, mode: String
    ): List<TranslationResult> = coroutineScope {
        nodes.map { node ->
            async {
                val cacheKey = "${node.text}|$sourceLang|$targetLang|$mode"
                val translated = try {
                    when (mode) {
                        "ai" -> aiTranslator.translate(node.text, sourceLang, targetLang)
                        "google" -> googleTranslator.translate(node.text, sourceLang, targetLang)
                        else -> aiTranslator.translate(node.text, sourceLang, targetLang)
                    }
                } catch (e: Exception) {
                    L.e("TranslateMgr", "翻译失败: \"${node.text.take(20)}\"", e)
                    "[翻译失败] ${node.text}"
                }
                cache[cacheKey] = translated
                TranslationResult(node.text, translated, node.bounds)
            }
        }.awaitAll()
    }

    /** 翻译单个节点（用于渐进式显示） */
    suspend fun translateOne(node: TextNode): TranslationResult {
        val sourceLang = getSourceLang()
        val targetLang = getTargetLang()
        val mode = getTranslationMode()

        // 跳过中文
        if (node.text.any { c -> c in '\u4E00'..'\u9FFF' }) {
            return TranslationResult(node.text, node.text, node.bounds)
        }

        val cacheKey = "${node.text}|$sourceLang|$targetLang|$mode"
        val cachedText = cache[cacheKey]
        if (cachedText != null) {
            return TranslationResult(node.text, cachedText, node.bounds)
        }

        val translated = try {
            when (mode) {
                "ai" -> aiTranslator.translate(node.text, sourceLang, targetLang)
                "google" -> googleTranslator.translate(node.text, sourceLang, targetLang)
                else -> aiTranslator.translate(node.text, sourceLang, targetLang)
            }
        } catch (e: Exception) {
            L.e("TranslateMgr", "翻译失败: \"${node.text.take(20)}\"", e)
            "[翻译失败] ${node.text}"
        }
        cache[cacheKey] = translated
        return TranslationResult(node.text, translated, node.bounds)
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

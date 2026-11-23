package com.screentranslate.translate

import android.content.Context
import android.content.SharedPreferences
import com.screentranslate.collector.TextNode
import com.screentranslate.logger.L
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

class TranslationManager(private val context: Context) {

    companion object {
        /** 缓存上限：超出后按最久未使用淘汰，避免长时间运行内存无界增长。 */
        private const val CACHE_SIZE = 500
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("translate_prefs", Context.MODE_PRIVATE)

    val googleTranslator = GoogleTranslator(context)
    val googleWebTranslator = GoogleWebTranslator()
    val bingWebTranslator = BingWebTranslator()
    val baiduWebTranslator = BaiduWebTranslator()
    val yandexWebTranslator = YandexWebTranslator()
    val aiTranslator = AiTranslator()
    val biliTranslator = BiliIndexTranslator()

    /**
     * 错误回调：一次翻译任务里只上报一次，避免整页翻译失败时 Toast 刷屏。
     * 由无障碍服务挂到主线程上弹 Toast。
     */
    @Volatile
    var onError: ((String) -> Unit)? = null

    private val errorReported = AtomicBoolean(false)

    private fun beginOperation() = errorReported.set(false)

    private fun reportError(message: String) {
        if (errorReported.compareAndSet(false, true)) {
            L.w("TranslateMgr", "上报错误: $message")
            onError?.invoke(message)
        }
    }

    /** 把底层异常翻译成用户能看懂的一句话（带引擎名，便于判断是哪家出问题）。 */
    private fun friendlyError(e: Exception, mode: String): String {
        val raw = e.message ?: e.javaClass.simpleName
        val label = modeLabel(mode)
        return when {
            e is UnknownHostException || e is ConnectException ->
                "网络连接失败，请检查网络或代理"
            e is SocketTimeoutException || e is java.util.concurrent.TimeoutException ->
                "翻译超时，请稍后重试"
            mode == "ai" && prefs.getString("ai_api_key", "").isNullOrBlank() ->
                "请先在设置中填写 API Key"
            raw.contains("API error 401") || raw.contains("API error 403") ->
                "鉴权失败（401/403），请检查 API Key"
            raw.contains("API error 429") ->
                "请求过于频繁（429），请降低并发或稍后再试"
            raw.contains("API error 5") ->
                "$label 服务暂时不可用（${raw.take(20)}）"
            raw.contains("API error 4") ->
                "$label 请求被拒绝：${raw.take(30)}"
            e is IOException -> "网络异常：${raw.take(30)}"
            else -> "$label 翻译失败：${raw.take(40)}"
        }
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "ai" -> "AI"
        "google" -> "Google 离线"
        "google_web" -> "Google 网页"
        "bing_web" -> "Bing"
        "baidu_web" -> "百度"
        "yandex_web" -> "Yandex"
        "bili" -> "B站"
        else -> mode
    }

    private fun isFailure(result: String): Boolean =
        result.startsWith("[翻译失败]") || result.startsWith("[失败]")

    // ---- 线程安全的有界 LRU 缓存 ----
    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true)
    private fun cacheGet(key: String): String? = synchronized(cacheLock) { cache[key] }

    private fun cachePut(key: String, value: String) {
        synchronized(cacheLock) {
            cache[key] = value
            // accessOrder=true 时迭代器首个元素即最久未使用项
            while (cache.size > CACHE_SIZE) {
                val eldest = cache.keys.iterator()
                if (!eldest.hasNext()) break
                eldest.next()
                eldest.remove()
            }
        }
    }

    data class TranslationResult(
        val original: String,
        val translated: String,
        val bounds: android.graphics.Rect
    )

    init {
        loadAiConfig()
        loadBiliConfig()
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

    fun loadBiliConfig() {
        val endpoint = prefs.getString("bili_endpoint", BiliIndexTranslator.DEFAULT_ENDPOINT)
            ?: BiliIndexTranslator.DEFAULT_ENDPOINT
        val model = prefs.getString("bili_model", BiliIndexTranslator.DEFAULT_MODEL)
            ?: BiliIndexTranslator.DEFAULT_MODEL
        biliTranslator.updateConfig(endpoint, model)
        L.d("TranslateMgr", "B站翻译配置已加载: model=$model")
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
            val cachedText = cacheGet(cacheKey)
            if (cachedText != null) {
                cached.add(TranslationResult(node.text, cachedText, node.bounds))
            } else {
                uncached.add(node)
            }
        }

        beginOperation()

        val fresh = if (uncached.isEmpty()) {
            emptyList()
        } else {
            L.d("TranslateMgr", "缓存命中=${cached.size}, 需翻译=${uncached.size}")
            when {
                mode == "ai" && batchMode == "batch" -> translateBatch(uncached, sourceLang, targetLang)
                mode == "bili" && batchMode == "batch" -> translateBatchBili(uncached, sourceLang, targetLang)
                else -> translateParallel(uncached, sourceLang, targetLang, mode)
            }
        }

        return cached + fresh
    }

    /** 打包模式：分组后每组建 JSON 一次发送，多组并行 */
    private suspend fun translateBatch(
        nodes: List<TextNode>, sourceLang: String, targetLang: String
    ): List<TranslationResult> = coroutineScope {
        val groups = groupNodes(nodes)

        groups.map { group ->
            async {
                val texts = group.map { it.text }
                try {
                    val translations = aiTranslator.batchTranslateJson(texts, sourceLang, targetLang)
                    group.zip(translations).map { (node, translated) ->
                        val cacheKey = "${node.text}|$sourceLang|$targetLang|ai"
                        if (!isFailure(translated)) cachePut(cacheKey, translated)
                        TranslationResult(node.text, translated, node.bounds)
                    }
                } catch (e: Exception) {
                    L.e("TranslateMgr", "批量翻译失败，回退逐条", e)
                    reportError(friendlyError(e, "ai"))
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

    /** B站打包模式：与 AI 打包相同的分组策略，失败时回退逐条 */
    private suspend fun translateBatchBili(
        nodes: List<TextNode>, sourceLang: String, targetLang: String
    ): List<TranslationResult> = coroutineScope {
        val groups = groupNodes(nodes)

        groups.map { group ->
            async {
                val texts = group.map { it.text }
                try {
                    val translations = biliTranslator.batchTranslateJson(texts, sourceLang, targetLang)
                    group.zip(translations).map { (node, translated) ->
                        val cacheKey = "${node.text}|$sourceLang|$targetLang|bili"
                        if (!isFailure(translated)) cachePut(cacheKey, translated)
                        TranslationResult(node.text, translated, node.bounds)
                    }
                } catch (e: Exception) {
                    L.e("TranslateMgr", "B站批量翻译失败，回退逐条", e)
                    reportError(friendlyError(e, "bili"))
                    texts.mapIndexed { i, text ->
                        val fallback = try {
                            biliTranslator.translate(text, sourceLang, targetLang)
                        } catch (e2: Exception) { "[失败] $text" }
                        TranslationResult(text, fallback, group[i].bounds)
                    }
                }
            }
        }.awaitAll().flatten()
    }

    /** 按字数和条数上限把节点切成多组，供打包模式并行发送 */
    private fun groupNodes(nodes: List<TextNode>): List<List<TextNode>> {
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
        L.d("TranslateMgr", "打包模式: ${groups.size}组, 每组≤${maxChars}字/$maxItems 条")
        return groups
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
                        "google_web" -> googleWebTranslator.translate(node.text, sourceLang, targetLang)
                        "bing_web" -> bingWebTranslator.translate(node.text, sourceLang, targetLang)
                        "baidu_web" -> baiduWebTranslator.translate(node.text, sourceLang, targetLang)
                        "yandex_web" -> yandexWebTranslator.translate(node.text, sourceLang, targetLang)
                        "bili" -> biliTranslator.translate(node.text, sourceLang, targetLang)
                        else -> aiTranslator.translate(node.text, sourceLang, targetLang)
                    }
                } catch (e: Exception) {
                    L.e("TranslateMgr", "翻译失败: \"${node.text.take(20)}\"", e)
                    reportError(friendlyError(e, mode))
                    "[翻译失败] ${node.text}"
                }
                if (!isFailure(translated)) cachePut(cacheKey, translated)
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
        val cachedText = cacheGet(cacheKey)
        if (cachedText != null) {
            return TranslationResult(node.text, cachedText, node.bounds)
        }

        beginOperation()
        val translated = try {
            when (mode) {
                "ai" -> aiTranslator.translate(node.text, sourceLang, targetLang)
                "google" -> googleTranslator.translate(node.text, sourceLang, targetLang)
                "google_web" -> googleWebTranslator.translate(node.text, sourceLang, targetLang)
                "bing_web" -> bingWebTranslator.translate(node.text, sourceLang, targetLang)
                "baidu_web" -> baiduWebTranslator.translate(node.text, sourceLang, targetLang)
                "yandex_web" -> yandexWebTranslator.translate(node.text, sourceLang, targetLang)
                "bili" -> biliTranslator.translate(node.text, sourceLang, targetLang)
                else -> aiTranslator.translate(node.text, sourceLang, targetLang)
            }
        } catch (e: Exception) {
            L.e("TranslateMgr", "翻译失败: \"${node.text.take(20)}\"", e)
            reportError(friendlyError(e, mode))
            "[翻译失败] ${node.text}"
        }
        if (!isFailure(translated)) cachePut(cacheKey, translated)
        return TranslationResult(node.text, translated, node.bounds)
    }

    suspend fun testTranslation(text: String, mode: String): String {
        val sourceLang = getSourceLang()
        val targetLang = getTargetLang()
        return try {
            when (mode) {
                "ai" -> aiTranslator.translate(text, sourceLang, targetLang)
                "google" -> googleTranslator.translate(text, sourceLang, targetLang)
                "google_web" -> googleWebTranslator.translate(text, sourceLang, targetLang)
                "bing_web" -> bingWebTranslator.translate(text, sourceLang, targetLang)
                "baidu_web" -> baiduWebTranslator.translate(text, sourceLang, targetLang)
                "yandex_web" -> yandexWebTranslator.translate(text, sourceLang, targetLang)
                "bili" -> biliTranslator.translate(text, sourceLang, targetLang)
                else -> aiTranslator.translate(text, sourceLang, targetLang)
            }
        } catch (e: Exception) {
            "错误: ${e.message}"
        }
    }
}

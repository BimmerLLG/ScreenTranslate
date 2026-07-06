package com.screentranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.screentranslate.collector.TextNode
import com.screentranslate.collector.TextSorter
import com.screentranslate.logger.L
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

sealed class CollectResult {
    data class Success(val nodes: List<TextNode>) : CollectResult()
    data object NoFrame : CollectResult()
    data class NoText(val reason: String) : CollectResult()
}

class OcrTextCollector(context: Context) {

    private val screenCapture = ScreenCapture(context)
    private val ocrEngine = OcrEngine()
    private val modelManager = ModelManager(context)
    private val prefs = context.getSharedPreferences("translate_prefs", Context.MODE_PRIVATE)

    private var initialized = false
    private var lastLangUsed = "en"

    // 中文识别开关（从设置读取，默认关闭以提速）
    private val enableZhRecognition: Boolean
        get() = prefs.getBoolean("enable_zh_recognition", false)

    suspend fun initIfNeeded(): Boolean {
        if (initialized) {
            L.d("OcrCollector", "initIfNeeded: 已初始化，跳过")
            return true
        }
        L.d("OcrCollector", "initIfNeeded: 开始初始化")
        modelManager.ensureModels()
        if (!modelManager.allModelsReady()) {
            L.d("OcrCollector", "initIfNeeded: 模型未就绪，开始下载...")
            modelManager.downloadMissingModels()
            if (!modelManager.allModelsReady()) {
                L.w("OcrCollector", "initIfNeeded: 模型下载未完成")
                return false
            }
        }
        L.d("OcrCollector", "initIfNeeded: 开始加载模型")
        val ok = ocrEngine.loadModels(
            detModel = modelManager.getModelFile(ModelManager.DETECTION_MODEL),
            enRecModel = modelManager.getModelFile(ModelManager.EN_REC_MODEL),
            koRecModel = modelManager.getModelFile(ModelManager.KO_REC_MODEL),
            zhRecModel = modelManager.getModelFile(ModelManager.ZH_REC_MODEL),
            enDictFile = modelManager.getModelFile(ModelManager.EN_DICT),
            koDictFile = modelManager.getModelFile(ModelManager.KO_DICT),
            zhDictFile = modelManager.getModelFile(ModelManager.ZH_DICT),
            enConfigFile = modelManager.getModelFile(ModelManager.EN_CONFIG),
            koConfigFile = modelManager.getModelFile(ModelManager.KO_CONFIG),
            zhConfigFile = modelManager.getModelFile(ModelManager.ZH_CONFIG)
        )
        initialized = ok
        L.d("OcrCollector", "initIfNeeded: 模型加载${if (ok) "成功" else "失败"}")
        return ok
    }

    fun initScreenCapture(data: android.content.Intent) {
        L.d("OcrCollector", "initScreenCapture: 传入截屏授权 intent")
        screenCapture.initFromActivityResult(data)
    }

    fun isScreenCaptureReady(): Boolean {
        val ready = screenCapture.isReady()
        L.d("OcrCollector", "isScreenCaptureReady: $ready")
        return ready
    }

    suspend fun collectText(): CollectResult {
        L.d("OcrCollector", "collectText: 开始")
        val initOk = initialized
        val captureReady = screenCapture.isReady()
        L.d("OcrCollector", "collectText: init=$initOk, capture=$captureReady")
        if (!initOk || !captureReady) {
            L.w("OcrCollector", "collectText: 未就绪 (init=$initOk, capture=$captureReady)")
            if (!captureReady) return CollectResult.NoFrame
            return CollectResult.NoText("engine not ready")
        }

        val bitmap = screenCapture.capture()
        if (bitmap == null) {
            L.w("OcrCollector", "collectText: 截图为 null")
            return CollectResult.NoFrame
        }
        L.d("OcrCollector", "collectText: 截屏成功 ${bitmap.width}x${bitmap.height}")

        L.d("OcrCollector", "collectText: 开始文字检测")
        val boxes = ocrEngine.detect(bitmap)
        if (boxes.isEmpty()) {
            L.w("OcrCollector", "collectText: 未检测到文字区域")
            return CollectResult.NoText("no text regions detected")
        }
        L.d("OcrCollector", "collectText: 检测到 ${boxes.size} 个文字区域")

        // EN + KO 并行识别（中文默认关闭以提速）
        val (enResults, koResults, zhResults) = coroutineScope {
            val en = async { ocrEngine.recognize(bitmap, boxes, "en") }
            val ko = async { ocrEngine.recognize(bitmap, boxes, "ko") }
            val zh = if (enableZhRecognition) async { ocrEngine.recognize(bitmap, boxes, "zh") } else null
            Triple(en.await(), ko.await(), zh?.await() ?: emptyList())
        }
        L.d("OcrCollector", "collectText: 识别完成 en=${enResults.size} ko=${koResults.size} zh=${zhResults.size}")

        val merged = mergeResults(enResults, koResults, zhResults)
        L.d("OcrCollector", "collectText: 合并后 ${merged.size} 个结果")

        val nodes = merged.map { r ->
            TextNode(
                text = r.text.trim(),
                bounds = r.bounds,
                packageName = "",
                isEditable = false,
                nodeId = r.text.hashCode() xor r.bounds.hashCode()
            )
        }

        // 合并相邻段落，减少翻译次数并保持上下文
        val mergedNodes = TextSorter.mergeAdjacentText(nodes)
        L.d("OcrCollector", "collectText: 完成，返回 ${mergedNodes.size} 个文本块")
        return CollectResult.Success(mergedNodes)
    }

    private fun mergeResults(
        en: List<OcrEngine.OcrResult>,
        ko: List<OcrEngine.OcrResult>,
        zh: List<OcrEngine.OcrResult>
    ): List<OcrEngine.OcrResult> {
        val koUsed = BooleanArray(ko.size)
        val zhUsed = BooleanArray(zh.size)
        val merged = mutableListOf<OcrEngine.OcrResult>()

        for (eResult in en) {
            val zhIdx = findBestByIoU(eResult.bounds, zh, zhUsed)
            val koIdx = findBestByIoU(eResult.bounds, ko, koUsed)

            // 三语平等：各语言有效得1分，无效得0分，同分比置信度
            data class Candidate(val result: OcrEngine.OcrResult, val score: Int)

            val candidates = mutableListOf<Candidate>()
            candidates.add(Candidate(eResult, 0)) // 英文不计分，作为兜底

            if (zhIdx >= 0) {
                zhUsed[zhIdx] = true
                val score = if (isValidChinese(zh[zhIdx].text)) 1 else 0
                candidates.add(Candidate(zh[zhIdx], score))
            }
            if (koIdx >= 0) {
                koUsed[koIdx] = true
                val score = if (isValidKorean(ko[koIdx].text)) 1 else 0
                candidates.add(Candidate(ko[koIdx], score))
            }

            val best = candidates.maxWithOrNull(compareBy({ it.score }, { it.result.confidence }))!!
            merged.add(best.result)
            lastLangUsed = best.result.lang
        }

        for (i in ko.indices) {
            if (!koUsed[i]) merged.add(ko[i])
        }
        for (i in zh.indices) {
            if (!zhUsed[i]) merged.add(zh[i])
        }

        return merged.sortedBy { it.bounds.top }
    }

    private fun findBestByIoU(
        target: android.graphics.Rect,
        candidates: List<OcrEngine.OcrResult>,
        used: BooleanArray
    ): Int {
        var bestIdx = -1
        var bestOverlap = 0f
        for (i in candidates.indices) {
            if (used[i]) continue
            val overlap = iou(target, candidates[i].bounds)
            if (overlap > 0.3f && overlap > bestOverlap) {
                bestOverlap = overlap
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun isValidChinese(text: String): Boolean {
        val cjk = text.count { it in '\u4E00'..'\u9FFF' }
        return cjk >= 2
    }

    private fun isValidKorean(text: String): Boolean {
        val syllables = text.count { it in '\uAC00'..'\uD7AF' }
        if (syllables < 1) return false
        val jamo = text.count { it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' }
        return syllables > jamo
    }

    private fun iou(a: Rect, b: Rect): Float {
        val intersectLeft = maxOf(a.left, b.left)
        val intersectTop = maxOf(a.top, b.top)
        val intersectRight = minOf(a.right, b.right)
        val intersectBottom = minOf(a.bottom, b.bottom)
        if (intersectLeft >= intersectRight || intersectTop >= intersectBottom) return 0f
        val intersectArea = (intersectRight - intersectLeft).toFloat() * (intersectBottom - intersectTop).toFloat()
        val aArea = (a.width() * a.height()).toFloat()
        val bArea = (b.width() * b.height()).toFloat()
        return intersectArea / (aArea + bArea - intersectArea)
    }

    fun getLastLang(): String = lastLangUsed

    fun destroy() {
        ocrEngine.close()
        screenCapture.destroy()
        L.d("OcrCollector", "OCR Collector 已销毁")
    }
}

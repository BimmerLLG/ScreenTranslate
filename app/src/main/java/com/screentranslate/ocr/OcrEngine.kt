package com.screentranslate.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.screentranslate.logger.L
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession.Result
import java.io.File
import java.nio.FloatBuffer
import org.json.JSONObject

class OcrEngine {

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var enRecSession: OrtSession? = null
    private var koRecSession: OrtSession? = null
    private var zhRecSession: OrtSession? = null
    private var enDict: Map<Int, String> = emptyMap()
    private var koDict: Map<Int, String> = emptyMap()
    private var zhDict: Map<Int, String> = emptyMap()
    private var enReplaceQmark = false
    private var koReplaceQmark = false
    private var zhReplaceQmark = false
    private var enMean = floatArrayOf(0.5f, 0.5f, 0.5f)
    private var enStd = floatArrayOf(0.5f, 0.5f, 0.5f)
    private var koMean = floatArrayOf(0.5f, 0.5f, 0.5f)
    private var koStd = floatArrayOf(0.5f, 0.5f, 0.5f)
    private var zhMean = floatArrayOf(0.5f, 0.5f, 0.5f)
    private var zhStd = floatArrayOf(0.5f, 0.5f, 0.5f)

    data class OcrResult(val text: String, val bounds: Rect, val confidence: Float, val lang: String)

    fun loadModels(
        detModel: File,
        enRecModel: File,
        koRecModel: File,
        zhRecModel: File,
        enDictFile: File,
        koDictFile: File,
        zhDictFile: File,
        enConfigFile: File? = null,
        koConfigFile: File? = null,
        zhConfigFile: File? = null
    ): Boolean {
        return try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            detSession = env?.createSession(detModel.absolutePath, opts)
            enRecSession = env?.createSession(enRecModel.absolutePath, opts)
            koRecSession = env?.createSession(koRecModel.absolutePath, opts)
            try {
                zhRecSession = env?.createSession(zhRecModel.absolutePath, opts)
            } catch (e: Exception) {
                L.w("OcrEngine", "中文模型加载失败（将继续使用英韩识别）:" + e.message)
            }
            enDict = loadDict(enDictFile)
            koDict = loadDict(koDictFile)
            zhDict = loadDict(zhDictFile)
            loadRecConfig(enConfigFile)?.let { enMean = it.first; enStd = it.second }
            loadRecConfig(koConfigFile)?.let { koMean = it.first; koStd = it.second }
            loadRecConfig(zhConfigFile)?.let { zhMean = it.first; zhStd = it.second }
            L.d("OcrEngine", "模型加载成功: det=${detModel.name}, en=${enRecModel.name}, ko=${koRecModel.name}, zh=${zhRecSession != null}")
            true
        } catch (e: Exception) {
            L.e("OcrEngine", "模型加载失败", e)
            false
        }
    }

    fun isReady(): Boolean = detSession != null && enRecSession != null

    fun detect(bitmap: Bitmap): List<Rect> {
        val session = detSession ?: return emptyList()
        val ortEnv = env ?: return emptyList()
        val start = System.currentTimeMillis()

        logBitmapSample(bitmap, "detect输入")

        val targetH = 640
        val targetW = 640
        val (input, scaleX, scaleY) = preprocessDet(bitmap, targetH, targetW)

        val inputShape = longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), inputShape)

        val inputName = getInputName(session)
        var result: Result? = null
        try {
            result = session.run(mapOf(inputName to tensor))
            tensor.close()

            val probMap = extractFirstFloatArray(result, targetH * targetW) ?: return emptyList()
            logProbMapStats(probMap)
            val boxes = postprocessDet(probMap, targetH, targetW, scaleX, scaleY)
            L.d("OcrEngine", "detect: 耗时${System.currentTimeMillis() - start}ms, 输出${boxes.size}个文字区域")
            return boxes
        } finally {
            result?.close()
        }
    }

    fun recognize(bitmap: Bitmap, boxes: List<Rect>, lang: String): List<OcrResult> {
        val session = when (lang) {
            "ko" -> koRecSession
            "zh" -> zhRecSession
            else -> enRecSession
        }
        val dict = when (lang) {
            "ko" -> koDict
            "zh" -> zhDict
            else -> enDict
        }
        if (session == null || dict.isEmpty()) return emptyList()
        val ortEnv = env ?: return emptyList()
        val start = System.currentTimeMillis()

        val results = mutableListOf<OcrResult>()
        val inputName = getInputName(session)
        val outputNames = session.outputNames.joinToString()
        val inputInfo = session.inputInfo[inputName]
        val expectedShape = inputInfo?.info?.let {
            if (it is ai.onnxruntime.TensorInfo) it.shape?.joinToString("x") else "?"
        } ?: "?"
        L.d("OcrEngine", "识别[$lang]: 输入=\"$inputName\" 期望形状=[$expectedShape] 输出=[$outputNames] boxes=${boxes.size}")
        val firstBox = boxes.firstOrNull()
        var firstError: String? = null

        for ((idx, box) in boxes.withIndex()) {
            try {
                val pad = 3
                val safeRect = Rect(
                    (box.left - pad).coerceAtLeast(0), (box.top - pad).coerceAtLeast(0),
                    (box.right + pad).coerceAtMost(bitmap.width), (box.bottom + pad).coerceAtMost(bitmap.height)
                )
                if (safeRect.width() < 4 || safeRect.height() < 4) {
                    L.d("OcrEngine", "跳过box[$idx]: 尺寸太小 ${safeRect.width()}x${safeRect.height()}")
                    continue
                }

                val crop = Bitmap.createBitmap(
                    bitmap, safeRect.left, safeRect.top,
                    safeRect.width(), safeRect.height()
                )
                val inverted = autoInvertIfNeeded(crop)
                val mean = when (lang) { "ko" -> koMean; "zh" -> zhMean; else -> enMean }
                val std = when (lang) { "ko" -> koStd; "zh" -> zhStd; else -> enStd }
                val useBGR = lang == "zh"
                val (input, _) = preprocessRec(inverted, mean, std, useBGR)
                if (inverted !== crop) inverted.recycle()
                crop.recycle()

                val h = 48L
                val w = input.size / 3 / h.toInt()
                val inputShape = longArrayOf(1, 3, h, w.toLong())
                val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), inputShape)

                var result: Result? = null
                try {
                    result = session.run(mapOf(inputName to tensor))
                    tensor.close()

                    val logits = extractFirstFloatArray(result)
                    if (logits != null) {
                        val logMin = logits.minOrNull() ?: 0f
                        val logMax = logits.maxOrNull() ?: 0f
                        L.d("OcrEngine", "logits[$idx/$lang]: size=${logits.size} range=[$logMin,$logMax]")
                        val replaceQmark = when (lang) { "ko" -> koReplaceQmark; "zh" -> zhReplaceQmark; else -> enReplaceQmark }
                        val (text, conf) = ctcDecode(logits, dict, replaceQmark)
                        L.d("OcrEngine", "识别[$idx/$lang]: \"$text\" conf=$conf")
                        if (text.isNotBlank()) {
                            results.add(OcrResult(text, safeRect, conf, lang))
                        }
                    } else {
                        L.w("OcrEngine", "logits[$idx/$lang]: extractFirstFloatArray 返回 null")
                    }
                } finally {
                    result?.close()
                }
            } catch (e: Exception) {
                if (firstError == null) {
                    firstError = e.message
                    L.w("OcrEngine", "识别失败 [$lang]: 输入名=$inputName 形状=[1,3,48,?] 错误=${e.message?.take(200)}")
                }
            }
        }

        val elapsed = System.currentTimeMillis() - start
        L.d("OcrEngine", "识别完成 ($lang): ${results.size} 个, ${elapsed}ms")
        return results
    }

    private fun getInputName(session: OrtSession): String {
        return session.inputNames.firstOrNull { it == "x" } ?: session.inputNames.first()
    }

    private fun extractFirstFloatArray(result: Result, minSize: Int = 0): FloatArray? {
        for (i in 0 until result.size()) {
            val value = result.get(i) as? OnnxTensor ?: continue
            val buf = value.floatBuffer
            if (buf.remaining() >= minSize) {
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                L.d("OcrEngine", "extract输出: idx=$i size=${arr.size}")
                return arr
            }
        }
        L.w("OcrEngine", "extractFirstFloatArray: 未找到足够大的张量 (minSize=$minSize, numOutputs=${result.size()})")
        return null
    }

    private fun preprocessDet(bmp: Bitmap, targetH: Int, targetW: Int): Triple<FloatArray, Float, Float> {
        val scaleX = targetW.toFloat() / bmp.width
        val scaleY = targetH.toFloat() / bmp.height
        val resized = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        resized.recycle()

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val input = FloatArray(3 * targetH * targetW)

        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val pixel = pixels[y * targetW + x]
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                input[y * targetW + x] = (r - mean[0]) / std[0]
                input[targetH * targetW + y * targetW + x] = (g - mean[1]) / std[1]
                input[2 * targetH * targetW + y * targetW + x] = (b - mean[2]) / std[2]
            }
        }
        return Triple(input, 1f / scaleX, 1f / scaleY)
    }

    private fun preprocessRec(crop: Bitmap, mean: FloatArray, std: FloatArray, useBGR: Boolean = false): Pair<FloatArray, Int> {
        val targetH = 48
        val ratio = targetH.toFloat() / crop.height
        val targetW = (crop.width * ratio).toInt().coerceIn(4, 480)
        val resized = Bitmap.createScaledBitmap(crop, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        resized.recycle()

        val input = FloatArray(3 * targetH * targetW)
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val pixel = pixels[y * targetW + x]
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val c1 = if (useBGR) b else r
                val c2 = g
                val c3 = if (useBGR) r else b
                input[y * targetW + x] = (c1 - mean[0]) / std[0]
                input[targetH * targetW + y * targetW + x] = (c2 - mean[1]) / std[1]
                input[2 * targetH * targetW + y * targetW + x] = (c3 - mean[2]) / std[2]
            }
        }

        var sumVal = 0.0
        for (i in input.indices) sumVal += input[i]
        val chMode = if (useBGR) "BGR" else "RGB"
        L.d("OcrEngine", "输入张量($chMode,H=$targetH): ${targetH}x$targetW mean=(${mean[0]},${mean[1]},${mean[2]}) std=(${std[0]},${std[1]},${std[2]}) min=${input.min()} max=${input.max()} avg=${sumVal / input.size}")

        return Pair(input, targetW)
    }

    private fun logBitmapSample(bmp: Bitmap, tag: String) {
        val w = bmp.width
        val h = bmp.height
        val samplePoints = listOf(
            Pair(w / 3, h / 3),
            Pair(w / 10, h / 10),
            Pair(w / 2, h / 2),
            Pair(w * 9 / 10, h * 9 / 10),
            Pair(w / 2, h / 10),
            Pair(w / 10, h / 2),
        )
        val samples = samplePoints.map { (x, y) ->
            val px = bmp.getPixel(x, y)
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            "($x,$y)=[$r,$g,$b]"
        }
        val pixels = IntArray(64)
        bmp.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        for (p in pixels) {
            rSum += (p shr 16) and 0xFF
            gSum += (p shr 8) and 0xFF
            bSum += p and 0xFF
        }
        val n = pixels.size
        val avgDesc = "avg8x8=[${rSum / n},${gSum / n},${bSum / n}]"
        L.d("OcrEngine", "$tag: ${w}x${h} $avgDesc 采样:${samples.joinToString(" ")}")
    }

    private fun logProbMapStats(probMap: FloatArray) {
        val min = probMap.minOrNull() ?: 0f
        val max = probMap.maxOrNull() ?: 0f
        val avg = probMap.sum() / probMap.size
        val threshold = 0.3f
        val aboveThreshold = probMap.count { it > threshold }
        val aboveHigh = probMap.count { it > 0.6f }
        val ratio = aboveThreshold.toFloat() / probMap.size * 100f
        L.d("OcrEngine", "probMap统计: size=${probMap.size} min=${"%.4f".format(min)} max=${"%.4f".format(max)} avg=${"%.4f".format(avg)}")
        L.d("OcrEngine", "probMap阈值: >$threshold → ${aboveThreshold}像素(${"%.1f".format(ratio)}%) >0.6 → ${aboveHigh}像素")
        // 打印概率值分布: 取10个区间统计
        val buckets = IntArray(10)
        for (v in probMap) {
            val idx = (v * 10).toInt().coerceIn(0, 9)
            buckets[idx]++
        }
        L.d("OcrEngine", "probMap分布(0.0-1.0): ${buckets.joinToString(",")}")
    }

    private fun postprocessDet(
        probMap: FloatArray, h: Int, w: Int,
        scaleX: Float, scaleY: Float
    ): List<Rect> {
        val threshold = 0.3f
        val binary = ByteArray(h * w) { i -> if (probMap[i] > threshold) 1 else 0 }
        val positiveCount = binary.count { it.toInt() != 0 }
        L.d("OcrEngine", "postprocessDet: 阈值后正像素=$positiveCount/${h * w}")

        val components = findConnectedComponents(binary, w, h)
        L.d("OcrEngine", "postprocessDet: 连通分量=${components.size}个")
        if (components.isNotEmpty()) {
            val sizes = components.map { "${it.width()}x${it.height()}" }.take(20)
            L.d("OcrEngine", "postprocessDet: 前20分量尺寸=[${sizes.joinToString(" ")}]")
        }

        val merged = mergeComponents(components)
        L.d("OcrEngine", "postprocessDet: 合并后=${merged.size}个")
        if (merged.isNotEmpty()) {
            val mSizes = merged.map { "${it.width()}x${it.height()}" }.take(20)
            L.d("OcrEngine", "postprocessDet: 合并后前20尺寸=[${mSizes.joinToString(" ")}]")
        }

        val result = merged.map { rect ->
            Rect(
                (rect.left * scaleX).toInt(),
                (rect.top * scaleY).toInt(),
                (rect.right * scaleX).toInt(),
                (rect.bottom * scaleY).toInt()
            )
        }.filter { it.width() > 20 && it.height() > 8 }

        L.d("OcrEngine", "postprocessDet: 尺寸过滤(w>20 && h>8)后=${result.size}个")
        return result
    }

    private fun findConnectedComponents(binary: ByteArray, w: Int, h: Int): List<Rect> {
        val visited = BooleanArray(w * h)
        val components = mutableListOf<Rect>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (binary[idx].toInt() == 0 || visited[idx]) continue

                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.add(x to y)
                visited[idx] = true
                var minX = x; var maxX = x; var minY = y; var maxY = y

                while (queue.isNotEmpty()) {
                    val (cx, cy) = queue.removeFirst()
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx; val ny = cy + dy
                            if (nx in 0 until w && ny in 0 until h) {
                                val nIdx = ny * w + nx
                                if (binary[nIdx].toInt() != 0 && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    queue.add(nx to ny)
                                }
                            }
                        }
                    }
                }

                val rect = Rect(minX, minY, maxX + 1, maxY + 1)
                if (rect.width() >= 5 && rect.height() >= 3) {
                    components.add(rect)
                }
            }
        }
        return components
    }

    private fun mergeComponents(components: List<Rect>): List<Rect> {
        if (components.isEmpty()) return components
        val sorted = components.sortedBy { it.top }
        val merged = mutableListOf<Rect>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val yOverlap = next.top < current.bottom + 5
            val xGap = next.left - current.right
            if (yOverlap && xGap < 20) {
                current = Rect(
                    minOf(current.left, next.left),
                    minOf(current.top, next.top),
                    maxOf(current.right, next.right),
                    maxOf(current.bottom, next.bottom)
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun ctcDecode(logits: FloatArray, dict: Map<Int, String>, replaceQmark: Boolean): Pair<String, Float> {
        val blankIdx = 0
        // 模型输出类别数可能比 dict.size+1 多 1-3 个（ONNX 模型有额外 padding 类）
        // 通过寻找能在 [dict.size+1, dict.size+5] 内整除 logits.size 的值来确定真实 numClasses
        var numClasses = dict.size + 1
        for (tryN in (dict.size + 1)..(dict.size + 5)) {
            if (logits.size % tryN == 0) {
                numClasses = tryN
                break
            }
        }
        if (numClasses <= 1) return Pair("", 0f)

        val timesteps = logits.size / numClasses
        if (timesteps == 0) return Pair("", 0f)

        val result = StringBuilder()
        var prevIdx = -1
        var totalConf = 0f
        var count = 0
        val diagPairs = mutableListOf<String>()

        for (t in 0 until timesteps) {
            val offset = t * numClasses
            var maxVal = -1e10f
            var maxIdx = blankIdx
            for (c in 0 until numClasses) {
                val v = logits[offset + c]
                if (v > maxVal) {
                    maxVal = v
                    maxIdx = c
                }
            }
            if (maxIdx != blankIdx && maxIdx != prevIdx) {
                val char = dict[maxIdx - 1] ?: "?"
                result.append(char)
                totalConf += maxVal
                count++
                if (diagPairs.size < 8) diagPairs.add("${maxIdx}->$char")
            }
            prevIdx = maxIdx
        }

        val avgConf = if (count > 0) totalConf / count else 0f
        val text = if (replaceQmark) result.toString().replace("?", " ") else result.toString()
        if (diagPairs.isNotEmpty()) L.d("OcrEngine", "ctcDecode class->char: ${diagPairs.joinToString(" ")}")
        L.d("OcrEngine", "ctcDecode: timesteps=$timesteps classes=$numClasses 解码=${count}字 conf=$avgConf 文本=\"$text\"")
        if (text.isNotEmpty()) {
            L.d("OcrEngine", "ctcDecode: 前3字=\"${text.take(3)}\"")
        }
        return Pair(text, avgConf)
    }

    private fun loadDict(file: File): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        try {
            var idx = 0
            file.readLines().forEach { line ->
                if (line.isNotBlank()) {
                    // 兼容 "index\tchar" 和纯字符两种格式
                    val char = if (line.contains('\t')) line.substringAfter('\t') else line
                    map[idx] = char
                    idx++
                }
            }
            L.d("OcrEngine", "字典加载: ${file.name}, ${map.size} 字符")
            // 检测是否误解析了 YAML(inference.yml 下载会存为 txt)
            val multiCharCount = map.values.count { it.length > 1 }
            if (map.size > 0 && multiCharCount > map.size / 2) {
                L.d("OcrEngine", "${file.name}: 检测到YAML格式，重新解析...")
                map.clear()
                try {
                    val yamlText = file.readText(Charsets.UTF_8)
                    var inDict = false
                    var idx = 0
                    yamlText.lines().forEach { line ->
                        val trimmed = line.trimStart()
                        if (!inDict) {
                            if (trimmed.startsWith("character_dict:")) inDict = true
                            return@forEach
                        }
                        if (trimmed.startsWith("- ") || trimmed.startsWith("-　")) {
                            val ch = line.substringAfter("- ")
                            map[idx] = ch
                            idx++
                        } else if (trimmed.isNotEmpty() && trimmed[0] != '-') {
                            inDict = false
                        }
                    }
                    L.d("OcrEngine", "YAML字典: ${file.name}, ${idx} 字符")
                } catch (e2: Exception) {
                    L.e("OcrEngine", "YAML字典解析失败: ${file.name}", e2)
                }
            }
            val entries = map.entries.toList()
            val first20 = entries.take(20).joinToString { "${it.key}->${it.value}" }
            val last5 = entries.takeLast(5).joinToString { "${it.key}->${it.value}" }
            L.d("OcrEngine", "字典前20: $first20")
            L.d("OcrEngine", "字典后5: $last5")
            // 检测空格占位符：字典有 ? 但没有真实空格 → ? 当作空格
            val hasSpace = map.values.any { it == " " }
            if (map.values.any { it == "?" } && !hasSpace) {
                when {
                    file.name.startsWith("en") -> enReplaceQmark = true
                    file.name.startsWith("ko") -> koReplaceQmark = true
                    file.name.startsWith("zh") -> zhReplaceQmark = true
                }
                L.d("OcrEngine", "${file.name}: ? 将替换为空格")
            }
        } catch (e: Exception) {
            L.e("OcrEngine", "字典加载失败: ${file.name}", e)
        }
        return map
    }

    private fun loadRecConfig(file: File?): Pair<FloatArray, FloatArray>? {
        if (file == null || !file.exists()) return null
        try {
            val json = JSONObject(file.readText())
            val h = json.optInt("image_shape", -1).let {
                if (it > 0) it else null
            }
            val meanArr = json.optJSONArray("mean")
            val stdArr = json.optJSONArray("std")
            if (meanArr == null || stdArr == null) return null
            val mean = FloatArray(3) { meanArr.optDouble(it, 0.5).toFloat() }
            val std = FloatArray(3) { stdArr.optDouble(it, 0.5).toFloat() }
            L.d("OcrEngine", "加载config: ${file.name} mean=[${mean.joinToString(",")}] std=[${std.joinToString(",")}] h=$h")
            return Pair(mean, std)
        } catch (e: Exception) {
            L.w("OcrEngine", "config.json 解析失败: ${file.name} - ${e.message}")
            return null
        }
    }

    private fun autoInvertIfNeeded(crop: Bitmap): Bitmap {
        val w = crop.width
        val h = crop.height
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)
        var total = 0L
        for (p in pixels) {
            total += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
        }
        val avgBrightness = total / (3.0 * pixels.size)
        if (avgBrightness < 128.0) {
            for (i in pixels.indices) {
                pixels[i] = pixels[i] xor 0x00FFFFFF.toInt()
            }
            val inverted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            inverted.setPixels(pixels, 0, w, 0, 0, w, h)
            L.d("OcrEngine", "反转: ${w}x$h avgBrightness=$avgBrightness")
            return inverted
        }
        L.d("OcrEngine", "不反转: ${w}x$h avgBrightness=$avgBrightness")
        return crop
    }

    fun close() {
        detSession?.close(); detSession = null
        enRecSession?.close(); enRecSession = null
        koRecSession?.close(); koRecSession = null
        zhRecSession?.close(); zhRecSession = null
        L.d("OcrEngine", "推理引擎已关闭")
    }
}

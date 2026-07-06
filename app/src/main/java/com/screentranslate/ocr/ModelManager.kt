package com.screentranslate.ocr

import android.content.Context
import com.screentranslate.logger.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class ModelManager(private val context: Context) {

        companion object {
        private const val MODEL_DIR = "ocr_models"
        private const val HF_BASE = "https://huggingface.co/monkt/paddleocr-onnx/resolve/main"
        private const val PP_BASE = "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/main"

        data class ModelInfo(
            val name: String, val url: String, val fileName: String, val expectedSize: Long
        )

        val DETECTION_MODEL = ModelInfo(
            "detection", "$HF_BASE/detection/v5/det.onnx", "det.onnx", 84_000_000
        )
        val EN_REC_MODEL = ModelInfo(
            "english_recognition", "$HF_BASE/languages/english/rec.onnx", "en_rec.onnx", 7_500_000
        )
        val KO_REC_MODEL = ModelInfo(
            "korean_recognition", "$HF_BASE/languages/korean/rec.onnx", "ko_rec.onnx", 7_500_000
        )
        val ZH_REC_MODEL = ModelInfo(
            "chinese_recognition", "$PP_BASE/inference.onnx", "zh_rec.onnx", 16_500_000
        )
        val EN_DICT = ModelInfo(
            "english_dict", "$HF_BASE/languages/english/dict.txt", "en_dict.txt", 1_000
        )
        val KO_DICT = ModelInfo(
            "korean_dict", "$HF_BASE/languages/korean/dict.txt", "ko_dict.txt", 1_000
        )
        val ZH_DICT = ModelInfo(
            "chinese_dict", "$PP_BASE/inference.yml", "zh_dict.txt", 150_000
        )
        val EN_CONFIG = ModelInfo(
            "english_config", "$HF_BASE/languages/english/config.json", "en_config.json", 300
        )
        val KO_CONFIG = ModelInfo(
            "korean_config", "$HF_BASE/languages/korean/config.json", "ko_config.json", 300
        )
        val ZH_CONFIG = ModelInfo(
            "chinese_config", "$HF_BASE/languages/chinese/config.json", "zh_config.json", 300
        )

        val allModels = listOf(DETECTION_MODEL, EN_REC_MODEL, KO_REC_MODEL, ZH_REC_MODEL,
            EN_DICT, KO_DICT, ZH_DICT, EN_CONFIG, KO_CONFIG, ZH_CONFIG)
        val TOTAL_SIZE = allModels.sumOf { it.expectedSize }
    }

    private val modelDir: File get() = File(context.filesDir, MODEL_DIR)

    fun ensureModels(): Boolean {
        copyFromAssetsIfNeeded()
        return allModelsReady()
    }

    private fun copyFromAssetsIfNeeded() {
        try {
            val assets = context.assets.list(MODEL_DIR) ?: return
            modelDir.mkdirs()
            for (name in assets) {
                val target = File(modelDir, name)
                if (!target.exists()) {
                    context.assets.open("$MODEL_DIR/$name").use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                    L.d("ModelManager", "从 assets 复制: $name")
                }
            }
        } catch (_: Exception) {
            L.d("ModelManager", "assets 中无模型文件")
        }
    }

    fun getModelFile(model: ModelInfo): File = File(modelDir, model.fileName)

    fun isModelDownloaded(model: ModelInfo): Boolean {
        val file = getModelFile(model)
        return file.exists() && file.length() > model.expectedSize * 0.8
    }

    fun allModelsReady(): Boolean = allModels.all { isModelDownloaded(it) }

    suspend fun downloadMissingModels(
        progress: (downloaded: Long, total: Long, speedBps: Long) -> Unit = { _, _, _ -> }
    ): Boolean {
        withContext(Dispatchers.IO) {
            modelDir.mkdirs()
            var downloaded = 0L
            val total = allModels.sumOf { it.expectedSize }

            for (model in allModels) {
                if (isModelDownloaded(model)) {
                    downloaded += getModelFile(model).length()
                    progress(downloaded, total, 0)
                    continue
                }
                try {
                    L.d("ModelManager", "下载: ${model.fileName}")
                    val fileStartBytes = getModelFile(model).length()
                    var chunkBytes = 0L
                    var chunkStart = System.currentTimeMillis()
                    URL(model.url).openStream().use { input ->
                        FileOutputStream(getModelFile(model), true).use { output ->
                            val buf = ByteArray(32768)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                chunkBytes += read
                                val elapsed = System.currentTimeMillis() - chunkStart
                                if (elapsed >= 200) {
                                    val speedBps = if (elapsed > 0) chunkBytes * 1000 / elapsed else 0
                                    progress(downloaded + fileStartBytes + chunkBytes, total, speedBps)
                                    chunkBytes = 0L
                                    chunkStart = System.currentTimeMillis()
                                }
                            }
                        }
                    }
                    downloaded += getModelFile(model).length()
                    progress(downloaded, total, 0)
                    L.d("ModelManager", "下载完成: ${model.fileName}")
                } catch (e: Exception) {
                    L.e("ModelManager", "下载失败: ${model.fileName}", e)
                    getModelFile(model).delete()
                }
            }
        }
        return allModelsReady()
    }

    fun deleteModels() {
        modelDir.deleteRecursively()
        L.d("ModelManager", "模型已删除")
    }
}

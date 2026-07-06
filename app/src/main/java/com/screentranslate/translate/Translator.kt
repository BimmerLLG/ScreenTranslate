package com.screentranslate.translate

/**
 * Translator - 翻译器接口
 * 定义了翻译器的标准协议，所有翻译实现都必须实现此接口
 * 使用接口抽象可以方便地切换不同的翻译引擎
 */
interface Translator {
    /**
     * 翻译单个文本
     * @param text 要翻译的文本
     * @param sourceLang 源语言（留空则自动检测）
     * @param targetLang 目标语言
     * @return 翻译后的文本
     */
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String

    /**
     * 批量翻译多个文本
     * @param texts 要翻译的文本列表
     * @param sourceLang 源语言
     * @param targetLang 目标语言
     * @return 翻译后的文本列表
     */
    suspend fun batchTranslate(texts: List<String>, sourceLang: String, targetLang: String): List<String>
}

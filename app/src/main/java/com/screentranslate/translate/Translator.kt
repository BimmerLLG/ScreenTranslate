package com.screentranslate.translate

interface Translator {
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String
    suspend fun batchTranslate(texts: List<String>, sourceLang: String, targetLang: String): List<String>
}

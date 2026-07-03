package com.screentranslate.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.screentranslate.R
import com.screentranslate.translate.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var translationManager: TranslationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("translate_prefs", Context.MODE_PRIVATE)
        translationManager = TranslationManager(this)

        loadSettings()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnTestTranslate).setOnClickListener {
            val mode = "ai"
            saveSettings()
            translationManager.loadAiConfig()

            val testText = "Hello, welcome to Screen Translate!"
            CoroutineScope(Dispatchers.Main).launch {
                findViewById<TextView>(R.id.tvTestResult).text = "翻译中..."
                val result = withContext(Dispatchers.Default) {
                    translationManager.testTranslation(testText, mode)
                }
                findViewById<TextView>(R.id.tvTestResult).text =
                    "原文: $testText\n译文: $result"
            }
        }
    }

    private fun loadSettings() {
        findViewById<EditText>(R.id.etEndpoint).setText(
            prefs.getString("ai_endpoint", "https://api.openai.com/v1/chat/completions")
        )
        findViewById<EditText>(R.id.etApiKey).setText(
            prefs.getString("ai_api_key", "")
        )
        findViewById<EditText>(R.id.etModel).setText(
            prefs.getString("ai_model", "gpt-4o-mini")
        )
        findViewById<EditText>(R.id.etSourceLang).setText(
            prefs.getString("source_lang", "")
        )
        findViewById<EditText>(R.id.etTargetLang).setText(
            prefs.getString("target_lang", "中文")
        )
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putString("ai_endpoint", findViewById<EditText>(R.id.etEndpoint).text.toString())
            putString("ai_api_key", findViewById<EditText>(R.id.etApiKey).text.toString())
            putString("ai_model", findViewById<EditText>(R.id.etModel).text.toString())
            putString("source_lang", findViewById<EditText>(R.id.etSourceLang).text.toString())
            putString("target_lang", findViewById<EditText>(R.id.etTargetLang).text.toString())
            apply()
        }
    }
}

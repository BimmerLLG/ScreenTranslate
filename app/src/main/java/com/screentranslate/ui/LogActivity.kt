package com.screentranslate.ui

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.screentranslate.R
import com.screentranslate.logger.L

class LogActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        tvLog = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollView)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshLogs() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { L.shareLogs(this) }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            L.clearLogs()
            tvLog.text = "日志已清空"
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }

        refreshLogs()
    }

    private fun refreshLogs() {
        val lines = L.getRecentLines(300)
        tvLog.text = if (lines.isEmpty()) "暂无日志" else lines.joinToString("\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}

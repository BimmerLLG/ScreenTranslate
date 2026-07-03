package com.screentranslate.logger

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object L {

    private const val MAX_BUFFER_LINES = 500
    private const val MAX_LOG_SIZE = 512 * 1024

    private val buffer = ArrayDeque<String>(MAX_BUFFER_LINES)
    private var logDir: File? = null
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs")
        logDir?.mkdirs()
        rollLogFile()
    }

    private fun rollLogFile() {
        val date = fileDateFormat.format(Date())
        logFile = File(logDir, "translate_$date.log")
        if (logFile?.exists() == true && logFile?.length() ?: 0 > MAX_LOG_SIZE) {
            val seq = (1..999).firstOrNull { n ->
                val f = File(logDir, "translate_${date}_$n.log")
                !f.exists() || f.length() < MAX_LOG_SIZE
            } ?: 1
            logFile = File(logDir, "translate_${date}_$seq.log")
        }
    }

    fun d(tag: String, msg: String) {
        log('D', tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val full = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        log('E', tag, full)
    }

    fun i(tag: String, msg: String) {
        log('I', tag, msg)
    }

    fun w(tag: String, msg: String) {
        log('W', tag, msg)
    }

    private fun log(level: Char, tag: String, msg: String) {
        val time = dateFormat.format(Date())
        val line = "$time $level/$tag: $msg"

        Log.println(level.toInt(), tag, msg)

        synchronized(buffer) {
            buffer.addLast(line)
            if (buffer.size > MAX_BUFFER_LINES) buffer.removeFirst()
        }

        writeToFile(line)
    }

    private fun writeToFile(line: String) {
        try {
            val file = logFile ?: return
            if (file.length() > MAX_LOG_SIZE) rollLogFile()
            val fw = FileWriter(file, true)
            fw.write("$line\n")
            fw.close()
        } catch (_: Exception) {}
    }

    fun getRecentLines(count: Int = 100): List<String> {
        synchronized(buffer) {
            return buffer.takeLast(count).toList()
        }
    }

    fun getLogFiles(): List<File> {
        return logDir?.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun shareLogs(context: Context) {
        val files = getLogFiles()
        if (files.isEmpty()) return

        val latest = files.first()
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            latest
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享日志"))
    }

    fun clearLogs() {
        getLogFiles().forEach { it.delete() }
        synchronized(buffer) { buffer.clear() }
    }
}

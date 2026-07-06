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

/**
 * L - 日志工具类
 * 提供统一的日志记录功能，支持：
 * - 内存缓冲区（快速查看最近日志）
 * - 文件日志（持久化存储，自动滚动切割）
 * - 日志分享和清除功能
 */
object L {

    private const val MAX_BUFFER_LINES = 500     // 内存缓冲区最大行数
    private const val MAX_LOG_SIZE = 512 * 1024  // 单个日志文件最大512KB

    private val buffer = ArrayDeque<String>(MAX_BUFFER_LINES)  // 双端队列实现环形缓冲区
    private var logDir: File? = null    // 日志目录
    private var logFile: File? = null   // 当前日志文件
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * 初始化日志系统
     * 创建日志目录并初始化日志文件
     */
    fun init(context: Context) {
        logDir = File(context.filesDir, "logs")  // 日志存储在应用私有目录
        logDir?.mkdirs()
        rollLogFile()
    }

    /**
     * 日志文件滚动切割
     * 当文件超过大小限制时，自动创建新文件（带序号）
     */
    private fun rollLogFile() {
        val date = fileDateFormat.format(Date())
        logFile = File(logDir, "translate_$date.log")
        if (logFile?.exists() == true && logFile?.length() ?: 0 > MAX_LOG_SIZE) {
            // 查找可用的序号
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

    /**
     * 核心日志记录方法
     * 同时写入内存缓冲区和文件
     */
    private fun log(level: Char, tag: String, msg: String) {
        val time = dateFormat.format(Date())
        val line = "$time $level/$tag: $msg"

        Log.println(level.toInt(), tag, msg)  // 输出到 Android Logcat

        synchronized(buffer) {
            buffer.addLast(line)
            // 超出容量时移除最旧的日志
            if (buffer.size > MAX_BUFFER_LINES) buffer.removeFirst()
        }

        writeToFile(line)
    }

    /**
     * 将日志写入文件
     * 如果文件超过大小限制，自动滚动到新文件
     */
    private fun writeToFile(line: String) {
        try {
            val file = logFile ?: return
            if (file.length() > MAX_LOG_SIZE) rollLogFile()
            val fw = FileWriter(file, true)  // 追加模式
            fw.write("$line\n")
            fw.close()
        } catch (_: Exception) {}
    }

    /**
     * 获取最近的N条日志
     * @param count 要获取的日志行数，默认100行
     */
    fun getRecentLines(count: Int = 100): List<String> {
        synchronized(buffer) {
            return buffer.takeLast(count).toList()
        }
    }

    /**
     * 获取所有日志文件列表
     * 按修改时间倒序排列（最新的在前面）
     */
    fun getLogFiles(): List<File> {
        return logDir?.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * 分享最新日志文件
     * 使用 FileProvider 生成安全的文件 URI
     */
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

    /**
     * 清除所有日志（文件和内存缓冲区）
     */
    fun clearLogs() {
        getLogFiles().forEach { it.delete() }
        synchronized(buffer) { buffer.clear() }
    }
}

package com.screentranslate.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.screentranslate.collector.AccessibilityCollector
import com.screentranslate.collector.TextSorter
import com.screentranslate.logger.L
import com.screentranslate.ocr.CollectResult
import com.screentranslate.ocr.OcrTextCollector
import com.screentranslate.ocr.ScreenCapture
import com.screentranslate.overlay.OverlayManager
import com.screentranslate.translate.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TranslateAccessibilityService - 屏幕翻译核心服务
 * 这是一个 Android 无障碍服务，负责：
 * 1. 监听屏幕内容变化事件
 * 2. 采集屏幕上的文本
 * 3. 调用翻译引擎翻译文本
 * 4. 显示翻译结果悬浮窗
 */
class TranslateAccessibilityService : AccessibilityService() {

    // 协程作用域，用于异步处理翻译任务
    private val scope = CoroutineScope(Dispatchers.Default)
    private val collector = AccessibilityCollector()       // 文本采集器
    private lateinit var translationManager: TranslationManager
    private lateinit var overlayManager: OverlayManager
    private lateinit var ocrTextCollector: OcrTextCollector

    @Volatile
    var enabled = false       // 翻译服务是否启用
        private set

    private var currentJob: Job? = null          // 当前正在执行的翻译任务
    private var lastProcessedHash = 0            // 上次处理的内容哈希值（用于去重）
    private var debounceTimer: Long = 0L         // 防抖计时器

    override fun onCreate() {
        super.onCreate()
        translationManager = TranslationManager(this)
        ocrTextCollector = OcrTextCollector(this)
        // 截屏权限不在 onCreate 处理，只在 ACTION_INIT_OCR 的 onStartCommand 中处理
        // 因为 Android 14+ 要求先调用 startForeground(MEDIA_PROJECTION) 后才能创建 MediaProjection
        overlayManager = OverlayManager(this).apply {
            onRequestTranslate = {
                scope.launch {
                    lastProcessedHash = 0
                    processOcrContent()
                }
            }
        }
        createNotificationChannel()
    }

    /**
     * 无障碍服务连接时调用
     * 配置服务监听的事件类型和标志
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK  // 监听所有类型的事件
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        // 添加标志以获取更完整的视图信息
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 500  // 事件通知间隔500毫秒
        serviceInfo = info
    }

    /**
     * 收到无障碍事件时调用
     * 使用防抖机制避免频繁触发翻译（600毫秒内只处理一次）
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!enabled) return

        val pkg = event.packageName ?: return
        L.d(TAG, "Event: ${event.eventType} pkg=$pkg")

        // 防抖：600毫秒内的重复事件会被忽略
        val now = System.currentTimeMillis()
        if (now - debounceTimer < 600) return
        debounceTimer = now

        // 取消之前的任务，延迟200毫秒后执行新的翻译
        // 这样可以等待屏幕内容稳定后再采集
        currentJob?.cancel()
        currentJob = scope.launch {
            delay(200)
            processScreenContent()
        }
    }

    /**
     * 处理屏幕内容的核心方法
     * 流程：采集文本 → 合并相邻文本 → 去重检查 → 显示结果
     */
    private suspend fun processScreenContent() {
        val root = rootInActiveWindow ?: run {
            L.w(TAG, "rootInActiveWindow is null")
            return
        }
        try {
            // 采集屏幕上所有可见文本
            val rawNodes = collector.collectVisibleText(root)
            if (rawNodes.isEmpty()) {
                L.d(TAG, "No visible text nodes found")
                return
            }

            // 合并相邻文本，减少翻译次数
            val merged = TextSorter.mergeAdjacentText(rawNodes)

            // 内容哈希去重：如果屏幕内容没有变化，跳过翻译
            val contentHash = merged.sumOf { it.text.hashCode() }
            if (contentHash == lastProcessedHash) {
                L.d(TAG, "Content unchanged, skip")
                return
            }
            lastProcessedHash = contentHash

            L.d(TAG, "Detected ${merged.size} text blocks: ${merged.map { it.text.take(30) }}")

            // 批量翻译所有文本（一次 API 调用）
            withContext(Dispatchers.Main) {
                overlayManager.showOverlay()
                overlayManager.setLoading(true)
            }
            try {
                val results = translationManager.translateNodes(merged)
                withContext(Dispatchers.Main) {
                    if (results.isNotEmpty()) {
                        overlayManager.showTranslationBubbles(results)
                    }
                }
                L.d(TAG, "批量翻译完成，共 ${results.size} 个气泡")
            } finally {
                withContext(Dispatchers.Main) {
                    overlayManager.setLoading(false)
                }
            }
        } catch (e: Exception) {
            L.e(TAG, "processScreenContent error", e)
        }
    }

    override fun onInterrupt() {
        L.d(TAG, "Service interrupted")
    }

    /**
     * 处理 OCR 屏幕内容
     * 通过 MediaProjection 截屏 + PP-OCR 识别，解决无障碍模式采集不到文本的问题
     */
    private suspend fun processOcrContent() {
        try {
            if (!ocrTextCollector.initIfNeeded()) {
                L.w(TAG, "OCR 模型未就绪")
                withContext(Dispatchers.Main) { overlayManager.setLoading(false) }
                return
            }
            // 检查是否有待处理的截屏授权
            ScreenCapture.pendingIntent?.let {
                ocrTextCollector.initScreenCapture(it)
                ScreenCapture.pendingIntent = null
            }
            if (!ocrTextCollector.isScreenCaptureReady()) {
                L.w(TAG, "截屏权限未获取")
                withContext(Dispatchers.Main) { overlayManager.setLoading(false) }
                return
            }
            L.d(TAG, "开始 OCR 采集")
            when (val result = ocrTextCollector.collectText()) {
                is CollectResult.NoFrame -> {
                    L.w(TAG, "无缓存帧，轮询超时，提示用户切换屏幕")
                    withContext(Dispatchers.Main) {
                        overlayManager.setLoading(false)
                        android.widget.Toast.makeText(
                            this@TranslateAccessibilityService,
                            "尚未捕获到屏幕画面，请先上下滑动屏幕再试",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }
                is CollectResult.NoText -> {
                    L.d(TAG, "OCR 未采集到文本: ${result.reason}")
                    withContext(Dispatchers.Main) { overlayManager.setLoading(false) }
                    return
                }
                is CollectResult.Success -> {
                    L.d(TAG, "OCR 采集到 ${result.nodes.size} 个文本块")
                    // 批量翻译所有文本（一次 API 调用）
                    withContext(Dispatchers.Main) {
                        overlayManager.showOverlay()
                    }
                    try {
                        val translationResults = translationManager.translateNodes(result.nodes)
                        withContext(Dispatchers.Main) {
                            overlayManager.showTranslationBubbles(translationResults)
                        }
                        L.d(TAG, "批量翻译完成，共 ${translationResults.size} 个气泡")
                    } finally {
                        withContext(Dispatchers.Main) {
                            overlayManager.setLoading(false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.e(TAG, "OCR 处理失败", e)
            withContext(Dispatchers.Main) { overlayManager.setLoading(false) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        overlayManager.hideOverlay()
        ocrTextCollector.destroy()
        super.onDestroy()
    }

    /**
     * 启动翻译服务
     * 显示悬浮窗并启动前台服务通知
     */
    private fun startTranslating() {
        enabled = true
        translationManager.saveEnabledState(true)
        overlayManager.showOverlay()
        val notification = buildNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        KeepAliveService.updateStatus(this, true)
        L.d(TAG, "Translation started")
    }

    /**
     * 停止翻译服务
     * 隐藏悬浮窗并移除前台通知
     */
    fun stopTranslating() {
        enabled = false
        translationManager.saveEnabledState(false)
        overlayManager.hideOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        KeepAliveService.updateStatus(this, false)
        L.d(TAG, "Translation stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTranslating()
            ACTION_STOP -> stopTranslating()
            ACTION_INIT_OCR -> {
                if (!enabled) {
                    startTranslating()
                }
                val pending = ScreenCapture.pendingIntent
                if (::ocrTextCollector.isInitialized && pending != null) {
                    ocrTextCollector.initScreenCapture(pending)
                    ScreenCapture.pendingIntent = null
                }
                L.d(TAG, "OCR 截屏授权已接收")
            }
            null -> {
                if (translationManager.isEnabled()) {
                    startTranslating()
                }
            }
        }
        return START_STICKY
    }

    /**
     * 创建通知渠道（Android 8.0 以上需要）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕翻译",
                NotificationManager.IMPORTANCE_LOW  // 低优先级，不发出声音
            ).apply { description = "屏幕翻译服务运行中" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建前台服务通知
     * 显示翻译服务的运行状态
     */
    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, TranslateAccessibilityService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕翻译")
            .setContentText(if (enabled) "翻译运行中，译标悬浮于屏幕" else "已暂停")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止翻译", stopPendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "TranslateService"
        private const val CHANNEL_ID = "screen_translate_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.screentranslate.ACTION_START"
        const val ACTION_STOP = "com.screentranslate.ACTION_STOP"
        const val ACTION_INIT_OCR = "com.screentranslate.ACTION_INIT_OCR"
    }
}

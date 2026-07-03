package com.screentranslate.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.screentranslate.collector.AccessibilityCollector
import com.screentranslate.collector.TextSorter
import com.screentranslate.logger.L
import com.screentranslate.overlay.OverlayManager
import com.screentranslate.translate.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TranslateAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val collector = AccessibilityCollector()
    private lateinit var translationManager: TranslationManager
    private lateinit var overlayManager: OverlayManager

    @Volatile
    var enabled = false
        private set

    private var currentJob: Job? = null
    private var lastProcessedHash = 0
    private var debounceTimer: Long = 0L

    override fun onCreate() {
        super.onCreate()
        translationManager = TranslationManager(this)
        overlayManager = OverlayManager(this)
        createNotificationChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 500
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!enabled) return

        val pkg = event.packageName ?: return
        L.d(TAG, "Event: ${event.eventType} pkg=$pkg")

        val now = System.currentTimeMillis()
        if (now - debounceTimer < 600) return
        debounceTimer = now

        currentJob?.cancel()
        currentJob = scope.launch {
            delay(200)
            processScreenContent()
        }
    }

    private suspend fun processScreenContent() {
        val root = rootInActiveWindow ?: run {
            L.w(TAG, "rootInActiveWindow is null")
            return
        }
        try {
            val rawNodes = collector.collectVisibleText(root)
            if (rawNodes.isEmpty()) {
                L.d(TAG, "No visible text nodes found")
                return
            }

            val merged = TextSorter.mergeAdjacentText(rawNodes)
            val contentHash = merged.sumOf { it.text.hashCode() }
            if (contentHash == lastProcessedHash) {
                L.d(TAG, "Content unchanged, skip")
                return
            }
            lastProcessedHash = contentHash

            L.d(TAG, "Detected ${merged.size} text blocks: ${merged.map { it.text.take(30) }}")

            val results = translationManager.translateNodes(merged)
            L.d(TAG, "Translated ${results.size} blocks")

            launch(Dispatchers.Main) {
                if (results.isNotEmpty()) {
                    overlayManager.showOverlay()
                    overlayManager.showTranslationBubbles(results)
                }
            }
        } catch (e: Exception) {
            L.e(TAG, "processScreenContent error", e)
        }
    }

    override fun onInterrupt() {
        L.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        overlayManager.hideOverlay()
        super.onDestroy()
    }

    fun startTranslating() {
        enabled = true
        overlayManager.showOverlay()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        L.d(TAG, "Translation started")
    }

    fun stopTranslating() {
        enabled = false
        overlayManager.hideOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        L.d(TAG, "Translation stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTranslating()
            ACTION_STOP -> stopTranslating()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕翻译",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "屏幕翻译服务运行中" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕翻译")
            .setContentText(if (enabled) "翻译运行中..." else "已暂停")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TranslateService"
        private const val CHANNEL_ID = "screen_translate_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.screentranslate.ACTION_START"
        const val ACTION_STOP = "com.screentranslate.ACTION_STOP"
    }
}

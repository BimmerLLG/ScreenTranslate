package com.screentranslate.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.screentranslate.translate.TranslationManager

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val activeBubbles = mutableMapOf<String, View>()

    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun showOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            PixelFormat.TRANSLUCENT
        )

        val view = View(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            layoutParams = params
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showTranslationBubbles(results: List<TranslationManager.TranslationResult>) {
        hideAllBubbles()
        val parent = overlayView ?: return

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        for (result in results) {
            val bubble = createBubble(result.translated, result.bounds, screenWidth, screenHeight)
            activeBubbles["${result.bounds.left}_${result.bounds.top}"] = bubble
        }
    }

    private fun createBubble(
        text: String,
        originalBounds: Rect,
        screenWidth: Int,
        screenHeight: Int
    ): View {
        val textView = TextView(context).apply {
            setText(text)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#CC2196F3"))
            textSize = 14f
            setPadding(8, 4, 8, 4)
            maxLines = 5
            includeFontPadding = false
            setOnClickListener {
                hideAllBubbles()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        val bubbleWidth = originalBounds.width().coerceAtLeast(100)
        var x = originalBounds.left
        var y = originalBounds.bottom + 4

        if (x + bubbleWidth > screenWidth) {
            x = screenWidth - bubbleWidth - 8
        }
        if (y > screenHeight - 100) {
            y = originalBounds.top - 4 - 80
        }
        if (y < 0) y = 4

        params.x = x.coerceAtLeast(4)
        params.y = y.coerceAtLeast(4)
        params.width = bubbleWidth.coerceAtMost(screenWidth - 16)

        try {
            windowManager.addView(textView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return textView
    }

    fun hideAllBubbles() {
        activeBubbles.values.forEach { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // ignore
            }
        }
        activeBubbles.clear()
    }

    fun hideOverlay() {
        hideAllBubbles()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        overlayView = null
    }
}

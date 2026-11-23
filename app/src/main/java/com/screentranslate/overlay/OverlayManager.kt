package com.screentranslate.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.TextView
import com.screentranslate.logger.L
import com.screentranslate.translate.TranslationManager

/**
 * OverlayManager - 悬浮窗管理器
 * 负责管理翻译结果的悬浮窗显示
 * 使用 WindowManager 在其他应用上层显示翻译结果
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("translate_prefs", Context.MODE_PRIVATE)
    private val activeBubbles = mutableMapOf<String, View>()

    private var overlayView: View? = null
    private var indicatorView: View? = null
    private var indicatorTextView: TextView? = null
    private var paragraphIndicator: TextView? = null
    private var paragraphIndicatorParams: WindowManager.LayoutParams? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isDragging = false
    private var hasMoved = false
    private var dismissView: View? = null
    private var isLoading = false
    private var plusOnLeft = false

    var onRequestTranslate: (() -> Unit)? = null
    var onParagraphTranslate: ((x: Int, y: Int) -> Unit)? = null

    fun showOverlay() {
        if (overlayView != null) {
            showIndicator()
            return
        }

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

        L.d("Overlay", "显示覆盖层: 类型=${params.type}, flags=${Integer.toHexString(params.flags)}")

        val view = View(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            layoutParams = params
        } catch (e: Exception) {
            L.e("Overlay", "Failed to add overlay", e)
        }
        showIndicator()
    }

    private fun showIndicator() {
        if (indicatorView != null) return

        val density = context.resources.displayMetrics.density
        val screenW = context.resources.displayMetrics.widthPixels
        val sizeDp = prefs.getInt("indicator_size", 56).coerceIn(32, 72)
        val size = (sizeDp * density).toInt()
        val opacity = prefs.getInt("indicator_opacity", 100).coerceIn(30, 100) / 100f
        val draggable = prefs.getBoolean("indicator_draggable", true)
        val shape = prefs.getString("indicator_shape", "circle") ?: "circle"
        val bgColor = try {
            android.graphics.Color.parseColor(
                prefs.getString("indicator_bg_color", "#2196F3") ?: "#2196F3"
            )
        } catch (_: Exception) { 0xFF2196F3.toInt() }

        val enableParagraph = prefs.getBoolean("enable_paragraph_translate", true)
        val paragraphSizeDp = prefs.getInt("paragraph_indicator_size", 24).coerceIn(16, 36)
        val paragraphSize = (paragraphSizeDp * density).toInt()

        val isPill = shape == "pill"
        val viewW = if (isPill) (sizeDp * 3.5f * density).toInt() else size
        val viewH = size
        val textSp = (sizeDp / 3.5f).coerceIn(10f, 24f)
        val plusMargin = (12 * density).toInt()
        val windowW = viewW + paragraphSize + plusMargin * 2
        val windowH = viewH + paragraphSize + plusMargin * 2

        val container = FrameLayout(context).apply {
            setPadding(0, 0, 0, 0)
        }

        val tv = TextView(context).apply {
            text = "译"
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSp)
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            gravity = Gravity.CENTER
            val drawable = GradientDrawable().apply {
                if (isPill) {
                    setShape(GradientDrawable.RECTANGLE)
                    cornerRadius = size / 2f
                } else {
                    setShape(GradientDrawable.OVAL)
                }
                setColor(bgColor)
            }
            background = drawable
            elevation = 8f
            alpha = opacity
            layoutParams = FrameLayout.LayoutParams(
                viewW, viewH,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = paragraphSize + plusMargin
                topMargin = paragraphSize + plusMargin
            }
            indicatorTextView = this

            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onLongPress(e: MotionEvent) {
                    if (!hasMoved) {
                        isDragging = true
                        vibrate()
                        setLoading(true)
                        onRequestTranslate?.invoke()
                        L.d("Overlay", "长按触发: 全屏翻译")
                    }
                }
            })

            var initialRawX = 0f
            var initialRawY = 0f
            var initialViewX = 0
            var initialViewY = 0
            val screenW = context.resources.displayMetrics.widthPixels

            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isDragging = false
                        hasMoved = false
                        initialRawX = event.rawX
                        initialRawY = event.rawY
                        val lp = indicatorView?.layoutParams as? WindowManager.LayoutParams
                        if (lp != null) {
                            initialViewX = lp.x
                            initialViewY = lp.y
                        }
                        if (enableParagraph) {
                            showParagraphIndicator()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!draggable) return@setOnTouchListener true
                        val dx = event.rawX - initialRawX
                        val dy = event.rawY - initialRawY
                        if (!hasMoved && (kotlin.math.abs(dx) > 8 * density || kotlin.math.abs(dy) > 8 * density)) {
                            hasMoved = true
                            isDragging = true
                        }
                        if (hasMoved) {
                            val lp = indicatorView?.layoutParams as? WindowManager.LayoutParams
                            if (lp != null) {
                                lp.x = initialViewX + dx.toInt()
                                lp.y = initialViewY + dy.toInt()
                                try {
                                    windowManager.updateViewLayout(indicatorView!!, lp)
                                } catch (_: Exception) {}
                                updateParagraphIndicatorPosition()
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (hasMoved && enableParagraph) {
                            val center = getParagraphIndicatorCenter()
                            if (center != null) {
                                L.d("Overlay", "段落翻译: 松手坐标 (${center.x}, ${center.y})")
                                onParagraphTranslate?.invoke(center.x, center.y)
                            }
                        }
                        hideParagraphIndicator()
                        if (hasMoved) {
                            val lp = indicatorView?.layoutParams as? WindowManager.LayoutParams
                            if (lp != null) {
                                val indicatorScreenX = lp.x + paragraphSize + plusMargin + viewW / 2
                                if (indicatorScreenX < screenW / 2) {
                                    plusOnLeft = true
                                    lp.x = (4 * density).toInt() - paragraphSize - plusMargin
                                } else {
                                    plusOnLeft = false
                                    lp.x = screenW - (4 * density).toInt() - paragraphSize - plusMargin - viewW
                                }
                                try {
                                    windowManager.updateViewLayout(indicatorView!!, lp)
                                } catch (_: Exception) {}
                                prefs.edit()
                                    .putInt("indicator_pos_x", lp.x)
                                    .putInt("indicator_pos_y", lp.y)
                                    .apply()
                                L.d("Overlay", "拖动结束: 位置已保存 (${lp.x}, ${lp.y})")
                            }
                        }
                        isDragging = false
                        hasMoved = false
                    }
                }
                true
            }
        }
        container.addView(tv)

        val plusTv = TextView(context).apply {
            text = "+"
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, paragraphSizeDp.toFloat())
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(2f, 0f, 0f, android.graphics.Color.BLACK)
            background = null
            elevation = 10f
            alpha = 0.95f
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams(paragraphSize, paragraphSize, Gravity.TOP or Gravity.END)
        }
        container.addView(plusTv)
        paragraphIndicator = plusTv

        val params = WindowManager.LayoutParams(
            windowW, windowH,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            PixelFormat.TRANSLUCENT
        )

        val savedX = prefs.getInt("indicator_pos_x", -1)
        val savedY = prefs.getInt("indicator_pos_y", -1)
        if (savedX >= 0 && savedY >= 0) {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = savedX
            params.y = savedY
            L.d("Overlay", "指示器使用保存位置: (${params.x}, ${params.y})")
        } else {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = screenW - viewW - dpToPx(12) - paragraphSize - plusMargin
            params.y = (context.resources.displayMetrics.heightPixels - viewH) / 2 - paragraphSize - plusMargin
            L.d("Overlay", "指示器默认位置: 右侧中部")
        }

        try {
            windowManager.addView(container, params)
            indicatorView = container
            val indicatorCenterX = params.x + paragraphSize + plusMargin + viewW / 2
            plusOnLeft = indicatorCenterX < screenW / 2
            updateParagraphIndicatorPosition()
        } catch (e: Exception) {
            L.e("Overlay", "Failed to show indicator", e)
        }
    }

    private fun showParagraphIndicator() {
        paragraphIndicator?.visibility = View.VISIBLE
        updateParagraphIndicatorPosition()
    }

    private fun hideParagraphIndicator() {
        paragraphIndicator?.visibility = View.INVISIBLE
    }

    private fun updateParagraphIndicatorPosition() {
        val indicator = paragraphIndicator ?: return
        val lp = indicator.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (plusOnLeft) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.END
        lp.leftMargin = 0
        lp.rightMargin = 0
        lp.topMargin = 0
        lp.bottomMargin = 0
        indicator.layoutParams = lp
    }

    private fun getParagraphIndicatorCenter(): android.graphics.Point? {
        val container = indicatorView ?: return null
        val containerLp = container.layoutParams as? WindowManager.LayoutParams ?: return null
        val indicatorSize = paragraphIndicator?.layoutParams?.width ?: 0
        val windowW = containerLp.width
        return if (plusOnLeft) {
            android.graphics.Point(
                containerLp.x + indicatorSize / 2,
                containerLp.y + indicatorSize / 2
            )
        } else {
            android.graphics.Point(
                containerLp.x + windowW - indicatorSize / 2,
                containerLp.y + indicatorSize / 2
            )
        }
    }

    private fun hideIndicator() {
        // paragraphIndicator 是 indicatorView 容器的子 View，随容器一起移除即可，
        // 单独 removeView 会抛异常。
        paragraphIndicator = null
        indicatorTextView = null
        indicatorView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        indicatorView = null
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    fun showTranslationBubbles(results: List<TranslationManager.TranslationResult>) {
        showBubblesInternal(results)
    }

    private fun showBubblesInternal(results: List<TranslationManager.TranslationResult>) {
        hideAllBubbles()
        if (overlayView == null) {
            L.w("Overlay", "overlayView is null, calling showOverlay()")
            showOverlay()
        }

        // 失败条目已在别处通过 Toast 提示，这里不再铺满"[翻译失败]"气泡
        val visible = results.filterNot {
            it.translated.startsWith("[翻译失败]") || it.translated.startsWith("[失败]")
        }
        if (visible.isEmpty()) {
            L.d("Overlay", "无成功译文，跳过气泡显示（失败数=${results.size}）")
            return
        }
        L.d("Overlay", "显示 ${visible.size} 个翻译气泡（失败已过滤 ${results.size - visible.size} 个）")

        // 先铺"点击空白关闭"层，再画气泡，保证气泡在关闭层之上可读可点
        showDismissLayer()

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val animate = prefs.getBoolean("mask_animation", true)
        for ((i, result) in visible.withIndex()) {
            val bubble = createBubble(result.translated, result.bounds, screenWidth, screenHeight)
            activeBubbles["bubble_$i"] = bubble
            if (animate) {
                bubble.alpha = 0f
                bubble.animate().alpha(1f).setDuration(160).start()
            }
        }
    }

    private fun showDismissLayer() {
        if (dismissView != null) return
        val view = View(context).apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    L.d("Overlay", "点击空白区域，取消气泡")
                    hideAllBubbles()
                }
                true
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(view, params)
            dismissView = view
            L.d("Overlay", "气泡取消层已添加")
        } catch (e: Exception) {
            L.e("Overlay", "添加取消层失败", e)
        }
    }

    private fun hideDismissLayer() {
        dismissView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        dismissView = null
    }

    private fun createBubble(
        text: String,
        originalBounds: Rect,
        screenWidth: Int,
        screenHeight: Int
    ): View {
        val scaledDensity = context.resources.displayMetrics.scaledDensity

        val fontColor = prefs.getString("mask_font_color", "#FFFFFF") ?: "#FFFFFF"
        val bgColorRaw = prefs.getString("mask_bg_color", "#DD2196F3") ?: "#DD2196F3"
        val cornerRadius = prefs.getInt("mask_corner_radius", 8).coerceIn(0, 24)
        val maxLinesStr = prefs.getString("mask_max_lines", "5") ?: "5"
        val maxLines = if (maxLinesStr == "0") Int.MAX_VALUE else maxLinesStr.toIntOrNull() ?: 5
        val useElevation = prefs.getBoolean("mask_elevation", true)
        // 遮罩透明度（10-100%）作用于背景色的 alpha 通道
        val opacityPct = prefs.getInt("mask_opacity", 80).coerceIn(10, 100)

        // 自适应字号：55% 原文高度 + 30% 用户偏好，范围 6-36sp
        val boxHeightSp = originalBounds.height() / scaledDensity
        val dynamicSize = (boxHeightSp * 0.55f).coerceIn(6f, 36f)
        val userPrefSize = prefs.getInt("mask_font_size", 14).coerceIn(12, 28).toFloat()
        val fontSize = (dynamicSize * 0.7f + userPrefSize * 0.3f).coerceIn(6f, 36f)

        val bgColorStr = bgColorRaw

        val padH = dpToPx(10)
        val padV = dpToPx(5)

        val textView = TextView(context).apply {
            setText(text)
            setTextColor(android.graphics.Color.parseColor(fontColor))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize)
            setPadding(padH, padV, padH, padV)
            includeFontPadding = false
            gravity = Gravity.CENTER
            this.maxLines = maxLines
            val shape = GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                val base = try {
                    android.graphics.Color.parseColor(bgColorStr)
                } catch (_: Exception) {
                    0xDD2196F3.toInt()
                }
                // 用设置里的透明度替换原有 alpha 通道
                val alpha = (opacityPct * 255 / 100)
                setColor((base and 0x00FFFFFF) or (alpha shl 24))
                this.cornerRadius = dpToPx(cornerRadius).toFloat()
            }
            background = shape
            if (useElevation) {
                elevation = dpToPx(4).toFloat()
            }
        }

        val minW = originalBounds.width().coerceAtLeast(dpToPx(40))
        val maxW = screenWidth - dpToPx(8)
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val measuredH = textView.measuredHeight
        val measuredW = textView.measuredWidth

        var bubbleW = maxOf(measuredW + padH, minW).coerceAtMost(maxW)
        var bubbleH = maxOf(measuredH, originalBounds.height())
        var x = originalBounds.left
        var y = originalBounds.top

        val params = WindowManager.LayoutParams(
            bubbleW, bubbleH,
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

        if (x + bubbleW > screenWidth) {
            x = (screenWidth - bubbleW - dpToPx(4)).coerceAtLeast(0)
        }
        if (y + bubbleH > screenHeight) {
            bubbleH = screenHeight - y - dpToPx(4)
            if (bubbleH < dpToPx(12)) {
                y = originalBounds.top - (originalBounds.height() - screenHeight + y + dpToPx(4))
                if (y < dpToPx(24)) y = dpToPx(24)
            }
        }
        if (y < dpToPx(24)) y = dpToPx(24)

        params.x = x.coerceAtLeast(0)
        params.y = y
        params.width = bubbleW.coerceAtMost(screenWidth - dpToPx(8))
        params.height = bubbleH

        L.d("Overlay", "创建气泡: 位置=(${params.x},${params.y}) 尺寸=(${params.width},${params.height}) font=${"%.1f".format(fontSize)}sp \"${text.take(20)}\"")

        try {
            windowManager.addView(textView, params)
        } catch (e: Exception) {
            L.e("Overlay", "Failed to add bubble", e)
        }

        return textView
    }

    fun hideAllBubbles() {
        hideDismissLayer()
        if (activeBubbles.isNotEmpty()) {
            L.d("Overlay", "Hiding ${activeBubbles.size} bubbles")
        }
        activeBubbles.values.forEach { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
        activeBubbles.clear()
    }

    fun isShowingOverlay(): Boolean = overlayView != null

    fun hideOverlay() {
        L.d("Overlay", "Hiding overlay")
        hideAllBubbles()
        hideIndicator()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    fun triggerDisplayUpdate() {
        val view = indicatorView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val savedX = lp.x
        lp.x -= 1
        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
        lp.x = savedX
        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
        L.d("Overlay", "triggerDisplayUpdate: 触发屏幕重绘")
    }

    fun setLoading(loading: Boolean) {
        isLoading = loading
        val tv = indicatorTextView ?: return
        if (loading) {
            tv.clearAnimation()
            tv.text = ""
            val rotate = RotateAnimation(
                0f, 360f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 800
                repeatCount = RotateAnimation.INFINITE
                interpolator = LinearInterpolator()
            }
            tv.startAnimation(rotate)
            L.d("Overlay", "setLoading: 开始旋转动画")
        } else {
            tv.clearAnimation()
            tv.text = "译"
            L.d("Overlay", "setLoading: 停止旋转动画")
        }
    }

    fun isLoading(): Boolean = isLoading

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }
}

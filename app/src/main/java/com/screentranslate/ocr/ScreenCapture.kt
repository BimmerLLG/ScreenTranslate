package com.screentranslate.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.screentranslate.logger.L
import kotlinx.coroutines.delay

class ScreenCapture(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentImageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var virtualDisplayReady = false
    private var frameCount = 0

    companion object {
        const val REQUEST_CODE = 10001
        var pendingIntent: Intent? = null
    }

    fun initFromActivityResult(data: Intent?) {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        if (data != null) {
            mediaProjection = manager.getMediaProjection(Activity.RESULT_OK, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection?.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            L.w("ScreenCapture", "MediaProjection.onStop: 系统停止了屏幕投影")
                            virtualDisplayReady = false
                            virtualDisplay?.release()
                            virtualDisplay = null
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }
            L.d("ScreenCapture", "initFromActivityResult: mediaProjection=${mediaProjection != null}")
            startBackgroundCapture()
        } else {
            L.w("ScreenCapture", "initFromActivityResult: data 为 null")
        }
    }

    private fun startBackgroundCapture() {
        if (virtualDisplayReady) {
            L.d("ScreenCapture", "startBackgroundCapture: 已在运行，跳过")
            return
        }

        val projection = mediaProjection
        if (projection == null) {
            L.w("ScreenCapture", "startBackgroundCapture: mediaProjection 为 null")
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        L.d("ScreenCapture", "startBackgroundCapture: ${screenWidth}x${screenHeight}, densityDpi=$screenDensity")

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        currentImageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenTranslateCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader.surface,
            object : VirtualDisplay.Callback() {
                override fun onPaused() { L.d("ScreenCapture", "VD: onPaused") }
                override fun onResumed() { L.d("ScreenCapture", "VD: onResumed") }
                override fun onStopped() { L.d("ScreenCapture", "VD: onStopped") }
            },
            Handler(Looper.getMainLooper())
        )
        virtualDisplayReady = true
        L.d("ScreenCapture", "startBackgroundCapture: 完成")
    }

    fun isReady(): Boolean {
        val ready = mediaProjection != null && virtualDisplayReady
        if (!ready) {
            L.d("ScreenCapture", "isReady: false (proj=${mediaProjection != null}, vd=$virtualDisplayReady)")
        }
        return ready
    }

    suspend fun capture(): Bitmap? {
        val reader = currentImageReader ?: return null

        var image = reader.acquireLatestImage()
        if (image != null) {
            frameCount++
            val bmp = decodeImage(image)
            if (bmp != null && frameCount % 10 == 1) {
                val sp = IntArray(9)
                bmp.getPixels(sp, 0, 3, bmp.width / 3, bmp.height / 3, 3, 3)
                val nb = sp.count { it != 0xFF000000.toInt() }
                L.d("ScreenCapture", "capture帧 #$frameCount: ${bmp.width}x${bmp.height} 3x3非黑=$nb/9")
            }
            return bmp
        }

        L.d("ScreenCapture", "capture: 无帧，强制刷新...")
        triggerFrameRefresh()
        delay(100)

        image = reader.acquireLatestImage()
        if (image != null) {
            frameCount++
            L.d("ScreenCapture", "capture: 刷新后获得帧 #$frameCount")
            return decodeImage(image)
        }

        delay(50)
        image = reader.acquireLatestImage()
        if (image != null) {
            frameCount++
            L.d("ScreenCapture", "capture: 延迟后获得帧 #$frameCount")
            return decodeImage(image)
        }

        L.w("ScreenCapture", "capture: 无可用帧")
        return null
    }

    private fun triggerFrameRefresh() {
        val vd = virtualDisplay ?: return
        if (screenWidth <= 1 || screenHeight <= 1) return
        try {
            vd.resize(screenWidth - 1, screenHeight, screenDensity)
            vd.resize(screenWidth, screenHeight, screenDensity)
        } catch (e: Exception) {
            L.e("ScreenCapture", "triggerFrameRefresh 异常", e)
        }
    }

    private fun decodeImage(image: Image): Bitmap? {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val fullWidth = screenWidth + rowPadding / pixelStride
            val src = Bitmap.createBitmap(fullWidth, screenHeight, Bitmap.Config.ARGB_8888)
            src.copyPixelsFromBuffer(buffer)
            image.close()

            val result = Bitmap.createBitmap(src, 0, 0, screenWidth, screenHeight)
            // 注意：不 recycle src，因为 result 与 src 共享 native 内存
            // src 会在 GC 时自动释放
            return result
        } catch (e: Exception) {
            L.e("ScreenCapture", "decodeImage 异常", e)
            image.close()
            return null
        }
    }

    fun destroy() {
        L.d("ScreenCapture", "destroy")
        virtualDisplay?.release()
        virtualDisplay = null
        virtualDisplayReady = false
        currentImageReader?.close()
        currentImageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}

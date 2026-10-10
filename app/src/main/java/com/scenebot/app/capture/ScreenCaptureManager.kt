package com.scenebot.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ScreenCaptureManager(
    private val context: Context,
    resultCode: Int,
    resultData: Intent
) {

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }

    private val mediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var width: Int = 1080
    private var height: Int = 1920
    private var density: Int = DisplayMetrics.DENSITY_DEFAULT
    private var isInitialized = false

    init {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            width = metrics.widthPixels
            height = metrics.heightPixels
            density = metrics.densityDpi

            // Cap dimensions to standard 1080p width to optimize memory and ML Kit processing
            if (width > 1080) {
                val scale = 1080f / width
                width = 1080
                height = (height * scale).toInt()
            }

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

            // CRITICAL FOR ANDROID 14 & ANDROID 15:
            // MediaProjection.Callback MUST be registered before calling createVirtualDisplay!
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.i(TAG, "MediaProjection stopped by system or user")
                    stop()
                }
            }, Handler(Looper.getMainLooper()))

            setupVirtualDisplay()
            isInitialized = true
            Log.i(TAG, "ScreenCaptureManager initialized: ${width}x${height} @ ${density}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ScreenCaptureManager: ${e.message}", e)
        }
    }

    @SuppressLint("WrongConstant")
    private fun setupVirtualDisplay() {
        val proj = mediaProjection ?: return
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = proj.createVirtualDisplay(
            "SceneBotCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    fun isReady(): Boolean = isInitialized && virtualDisplay != null

    fun acquireLatestScreenshot(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage() ?: return null
        } catch (e: Exception) {
            return null
        }

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val rawWidth = width + rowPadding / pixelStride
            val intermediate = Bitmap.createBitmap(
                rawWidth,
                height,
                Bitmap.Config.ARGB_8888
            )
            intermediate.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                intermediate
            } else {
                val cropped = Bitmap.createBitmap(intermediate, 0, 0, width, height)
                intermediate.recycle() // CRITICAL: Recycle intermediate bitmap to prevent memory leak!
                cropped
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screenshot: ${e.message}")
            null
        } finally {
            image.close()
        }
    }

    fun stop() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ScreenCaptureManager: ${e.message}")
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        isInitialized = false
    }
}

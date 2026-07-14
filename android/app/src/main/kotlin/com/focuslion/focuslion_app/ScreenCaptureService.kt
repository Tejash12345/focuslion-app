package com.focuslion.focuslion_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Captures the phone screen with MediaProjection and pushes low-rate JPEG
 * frames (base64) to [frameCallback]. MainActivity forwards those to the
 * WebView, which paints them to a canvas and feeds canvas.captureStream() into
 * the live WebRTC call — so screen sharing works inside the WebView app even
 * though the WebView engine has no getDisplayMedia. Low fps by design (built
 * for showing apps/documents, not smooth video).
 */
class ScreenCaptureService : Service() {
    companion object {
        var frameCallback: ((String) -> Unit)? = null
        var onStopped: (() -> Unit)? = null
        @Volatile var running = false
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "focuslion_screen"
        private const val NOTIF_ID = 770050
        private const val TARGET_FPS = 4
        private const val MAX_WIDTH = 640
    }

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val capture = HandlerThread("fl-screen").apply { start() }
    private val captureHandler = Handler(capture.looper)
    private val main = Handler(Looper.getMainLooper())
    private var lastFrame = 0L
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundNotif()
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        if (projection == null) { stopSelf(); return START_NOT_STICKY }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, main)
        startCapture()
        running = true
        return START_NOT_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        }
        val notif: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("FocusLion")
            .setContentText("Sharing your screen in a call")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startCapture() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val dw = metrics.widthPixels
        val dh = metrics.heightPixels
        val dpi = metrics.densityDpi
        val scale = if (dw > MAX_WIDTH) MAX_WIDTH.toFloat() / dw else 1f
        val w = (dw * scale).toInt().coerceAtLeast(2)
        val h = (dh * scale).toInt().coerceAtLeast(2)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vdisplay = projection?.createVirtualDisplay(
            "fl-screen", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, captureHandler)
        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (busy || now - lastFrame < 1000L / TARGET_FPS) { image.close(); return@setOnImageAvailableListener }
            lastFrame = now
            busy = true
            try {
                val bmp = imageToBitmap(image, w, h)
                image.close()
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 40, baos)
                bmp.recycle()
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                main.post { frameCallback?.invoke(b64) }
            } catch (_: Exception) {
                try { image.close() } catch (_: Exception) {}
            } finally {
                busy = false
            }
        }, captureHandler)
    }

    // ImageReader RGBA planes carry row padding; crop it back to the real width
    private fun imageToBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val padded = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return cropped
    }

    private fun stopCapture() {
        running = false
        try { vdisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        vdisplay = null
        reader = null
        projection = null
        main.post { onStopped?.invoke() }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { vdisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        capture.quitSafely()
        super.onDestroy()
    }
}

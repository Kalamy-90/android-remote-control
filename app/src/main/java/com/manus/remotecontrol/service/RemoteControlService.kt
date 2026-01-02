package com.manus.remotecontrol.service

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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import com.manus.remotecontrol.utils.AppLogger
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.manus.remotecontrol.R
import com.manus.remotecontrol.server.WebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class RemoteControlService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "RemoteControlChannel"
        
        var isRunning = false
        var currentPin = ""
        var currentIp = ""
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var webServer: WebServer? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    
    private val _jpegFlow = MutableSharedFlow<ByteArray>(replay = 0)
    val jpegFlow = _jpegFlow.asSharedFlow()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                AppLogger.log("RemoteControlService", "Starting service...")
                startForegroundService() // Start foreground FIRST
                
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    try {
                        startProjection(resultCode, resultData)
                        startServer()
                        isRunning = true
                        AppLogger.log("RemoteControlService", "Service started successfully")
                    } catch (e: Exception) {
                        AppLogger.error("RemoteControlService", "Error starting components", e)
                        stopSelf()
                    }
                } else {
                    AppLogger.error("RemoteControlService", "Missing result code or data")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                AppLogger.log("RemoteControlService", "Stopping service")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_content))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        AppLogger.log("RemoteControlService", "Starting MediaProjection")
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        // Scale down drastically for performance and network speed
        val width = metrics.widthPixels / 4
        val height = metrics.heightPixels / 4
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RemoteControl",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                
                // Crop if necessary and compress
                val finalBitmap = if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
                
                val stream = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 40, stream)
                val bytes = stream.toByteArray()
                
                serviceScope.launch {
                    _jpegFlow.emit(bytes)
                }
                
                if (finalBitmap != bitmap) finalBitmap.recycle()
                bitmap.recycle()
                image.close()
            }
        }, null)
    }

    private fun startServer() {
        AppLogger.log("RemoteControlService", "Starting WebServer")
        currentPin = String.format("%04d", Random.nextInt(10000))
        currentIp = getIpAddress()
        webServer = WebServer(this, 8080, currentPin, this)
        webServer?.start()
    }
    
    private fun getIpAddress(): String {
        // Simplified IP retrieval
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) { }
        return "Unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        webServer?.stop()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }
}

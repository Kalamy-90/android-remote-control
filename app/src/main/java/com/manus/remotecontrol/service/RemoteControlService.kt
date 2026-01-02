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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import com.manus.remotecontrol.utils.AppLogger
import android.view.WindowManager
import android.view.Surface
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
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.math.roundToInt

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
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var webServer: WebServer? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    
    // Flow for H.264 NAL units
    private val _h264Flow = MutableSharedFlow<ByteArray>(replay = 0)
    val h264Flow = _h264Flow.asSharedFlow()

    // Default settings
    private var currentScale = 2.0f // Default 1/2 scale
    private var currentBitrate = 2000000 // 2 Mbps default
    private var savedResultCode: Int = 0
    private var savedResultData: Intent? = null
    
    private var isEncoderRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                AppLogger.log("RemoteControlService", "Starting service...")
                startForegroundService() // Start foreground FIRST
                
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    savedResultCode = resultCode
                    savedResultData = resultData
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
        AppLogger.log("RemoteControlService", "Starting MediaProjection (H.264)")
        
        // Clean up previous projection if exists
        stopProjection()
        
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (mediaProjection == null) {
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        // Calculate dimensions based on float scale
        // Video dimensions must be even numbers for H.264
        var width = (metrics.widthPixels / currentScale).roundToInt()
        var height = (metrics.heightPixels / currentScale).roundToInt()
        
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--
        
        val density = metrics.densityDpi

        try {
            // Setup MediaCodec Encoder
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between I-frames
            
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()
            
            // Create VirtualDisplay on the Encoder's input surface
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "RemoteControl",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null, null
            )
            
            isEncoderRunning = true
            startEncoderLoop()
            
        } catch (e: Exception) {
            AppLogger.error("RemoteControlService", "Error starting encoder", e)
        }
    }
    
    private fun startEncoderLoop() {
        serviceScope.launch(Dispatchers.Default) {
            val bufferInfo = MediaCodec.BufferInfo()
            
            while (isEncoderRunning && mediaCodec != null) {
                try {
                    val outputBufferId = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    
                    if (outputBufferId >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferId)
                        
                        if (outputBuffer != null) {
                            // Adjust position and limit
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            
                            _h264Flow.emit(data)
                        }
                        
                        mediaCodec?.releaseOutputBuffer(outputBufferId, false)
                    }
                } catch (e: Exception) {
                    if (isEncoderRunning) {
                        Log.e("RemoteControlService", "Encoder loop error", e)
                    }
                }
            }
        }
    }
    
    private fun stopProjection() {
        isEncoderRunning = false
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            
            inputSurface?.release()
            inputSurface = null
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Error stopping projection", e)
        }
    }

    fun updateQuality(scale: Float, quality: Int) {
        // Map "quality" (0-100) to bitrate (500kbps - 5Mbps)
        // Quality 50 -> 2Mbps
        val newBitrate = (quality * 50000).coerceAtLeast(500000)
        
        if (savedResultCode != 0 && savedResultData != null) {
            currentScale = scale
            currentBitrate = newBitrate
            // Restart projection with new settings
            serviceScope.launch(Dispatchers.Main) {
                startProjection(savedResultCode, savedResultData!!)
            }
        }
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
        stopProjection()
        mediaProjection?.stop()
    }
}

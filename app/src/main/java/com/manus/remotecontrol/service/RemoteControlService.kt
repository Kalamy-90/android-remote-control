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
import kotlinx.coroutines.channels.BufferOverflow
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
    
    // Photo Mode Components
    private var imageReader: ImageReader? = null
    private var reusableBitmap: Bitmap? = null
    
    // Video Mode Components
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    
    // Store SPS/PPS headers to send to new clients
    private var spsPpsBuffer: ByteArray? = null
    
    private var webServer: WebServer? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    
    // Flow for Photo Mode (JPEG/WebP)
    private val _imageFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val imageFlow = _imageFlow.asSharedFlow()
    
    // Flow for Video Mode (H.264)
    // Replay = 1 to ensure new subscribers get the latest header/frame immediately
    // DROP_OLDEST ensures the encoder is NEVER blocked by slow network consumers
    private val _h264Flow = MutableSharedFlow<ByteArray>(
        replay = 1,
        extraBufferCapacity = 60, // Buffer up to 1-2 seconds of video
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val h264Flow = _h264Flow.asSharedFlow()

    // Settings
    enum class Mode { PHOTO, VIDEO }
    private var currentMode = Mode.PHOTO
    
    // Photo Settings
    private var photoScale = 2.0f
    private var photoQuality = 50
    
    // Video Settings
    private var videoBitrate = 800000 // 800 Kbps default (Lowered for stability)
    private var videoFps = 30
    private var videoScale = 0.4f // Default to ~480p for better performance
    
    private var savedResultCode: Int = 0
    private var savedResultData: Intent? = null
    
    private var isEncoderRunning = false
    private var isImageReaderRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                AppLogger.log("RemoteControlService", "Starting service...")
                startForegroundService()
                
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    savedResultCode = resultCode
                    savedResultData = resultData
                    try {
                        // Start in Photo mode by default
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
        AppLogger.log("RemoteControlService", "Starting Projection in mode: $currentMode")
        
        stopProjection() // Clean up previous
        
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (mediaProjection == null) {
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
        }

        if (currentMode == Mode.PHOTO) {
            startPhotoMode()
        } else {
            startVideoMode()
        }
    }
    
    private fun startPhotoMode() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val width = (metrics.widthPixels / photoScale).toInt()
        val height = (metrics.heightPixels / photoScale).toInt()
        val density = metrics.densityDpi
        
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RemoteControlPhoto",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
        
        isImageReaderRunning = true
        
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isImageReaderRunning) return@setOnImageAvailableListener
            
            try {
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                
                // Reuse bitmap if possible
                if (reusableBitmap == null || reusableBitmap?.width != width + rowPadding / pixelStride || reusableBitmap?.height != height) {
                    reusableBitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                }
                
                reusableBitmap?.copyPixelsFromBuffer(buffer)
                image.close()
                
                // Crop if needed
                val finalBitmap = if (rowPadding == 0) {
                    reusableBitmap!!
                } else {
                    Bitmap.createBitmap(reusableBitmap!!, 0, 0, width, height)
                }
                
                val outputStream = ByteArrayOutputStream()
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                
                finalBitmap.compress(format, photoQuality, outputStream)
                val bytes = outputStream.toByteArray()
                
                // Use tryEmit to avoid blocking the UI thread
                _imageFlow.tryEmit(bytes)
                
            } catch (e: Exception) {
                Log.e("RemoteControlService", "ImageReader error", e)
            }
        }, null)
    }
    
    private fun startVideoMode() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        // Apply scaling to video dimensions
        var width = (metrics.widthPixels * videoScale).toInt()
        var height = (metrics.heightPixels * videoScale).toInt()
        
        // Video dimensions must be even
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--
        
        // Ensure minimum dimensions
        width = width.coerceAtLeast(320)
        height = height.coerceAtLeast(240)
        
        val density = metrics.densityDpi
        
        AppLogger.log("RemoteControlService", "Starting Video: ${width}x${height} @ ${videoBitrate/1000}kbps ${videoFps}fps")
        
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between I-frames
            
            // Enforce CBR (Constant Bitrate) for consistent network usage
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            
            // Low latency settings if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
                // Try to set operating rate higher than FPS to reduce latency
                format.setFloat(MediaFormat.KEY_OPERATING_RATE, videoFps.toFloat() * 1.5f)
            }
            
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "RemoteControlVideo",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null, null
            )
            
            isEncoderRunning = true
            spsPpsBuffer = null // Reset headers
            startEncoderLoop()
            
        } catch (e: Exception) {
            AppLogger.error("RemoteControlService", "Error starting video mode", e)
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
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            
                            // Check for SPS/PPS headers (Codec Config)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                spsPpsBuffer = data
                                AppLogger.log("RemoteControlService", "Captured SPS/PPS headers: ${data.size} bytes")
                            }
                            
                            // If this is a key frame, prepend SPS/PPS if we have them
                            // This ensures new clients can start decoding immediately
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && spsPpsBuffer != null) {
                                val combined = ByteArray(spsPpsBuffer!!.size + data.size)
                                System.arraycopy(spsPpsBuffer!!, 0, combined, 0, spsPpsBuffer!!.size)
                                System.arraycopy(data, 0, combined, spsPpsBuffer!!.size, data.size)
                                // Use tryEmit to avoid blocking the encoder loop
                                _h264Flow.tryEmit(combined)
                            } else {
                                // Use tryEmit to avoid blocking the encoder loop
                                _h264Flow.tryEmit(data)
                            }
                        }
                        
                        mediaCodec?.releaseOutputBuffer(outputBufferId, false)
                    } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Fallback: capture SPS/PPS from format if not in buffer
                        val newFormat = mediaCodec?.outputFormat
                        val sps = newFormat?.getByteBuffer("csd-0")
                        val pps = newFormat?.getByteBuffer("csd-1")
                        
                        if (sps != null && pps != null) {
                            val spsBytes = ByteArray(sps.remaining())
                            sps.get(spsBytes)
                            val ppsBytes = ByteArray(pps.remaining())
                            pps.get(ppsBytes)
                            
                            spsPpsBuffer = ByteArray(spsBytes.size + ppsBytes.size)
                            System.arraycopy(spsBytes, 0, spsPpsBuffer!!, 0, spsBytes.size)
                            System.arraycopy(ppsBytes, 0, spsPpsBuffer!!, spsBytes.size, ppsBytes.size)
                            AppLogger.log("RemoteControlService", "Extracted SPS/PPS from format: ${spsPpsBuffer?.size} bytes")
                        }
                    }
                } catch (e: Exception) {
                    if (isEncoderRunning) Log.e("RemoteControlService", "Encoder loop error", e)
                }
            }
        }
    }
    
    // Method to get current headers for new clients
    fun getVideoHeaders(): ByteArray? {
        return spsPpsBuffer
    }
    
    private fun stopProjection() {
        isImageReaderRunning = false
        isEncoderRunning = false
        
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            
            imageReader?.close()
            imageReader = null
            
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            
            inputSurface?.release()
            inputSurface = null
        } catch (e: Exception) {
            Log.e("RemoteControlService", "Error stopping projection", e)
        }
    }

    fun setMode(modeStr: String) {
        val newMode = if (modeStr == "video") Mode.VIDEO else Mode.PHOTO
        if (currentMode != newMode) {
            currentMode = newMode
            restartProjection()
        }
    }
    
    fun updatePhotoSettings(scale: Float, quality: Int) {
        if (currentMode == Mode.PHOTO) {
            photoScale = scale
            photoQuality = quality
            restartProjection()
        }
    }
    
    fun updateVideoSettings(bitrate: Int, fps: Int, scale: Float) {
        if (currentMode == Mode.VIDEO) {
            videoBitrate = bitrate
            videoFps = fps
            videoScale = scale
            restartProjection()
        }
    }
    
    private fun restartProjection() {
        if (savedResultCode != 0 && savedResultData != null) {
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

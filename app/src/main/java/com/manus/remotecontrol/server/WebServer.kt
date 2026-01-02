package com.manus.remotecontrol.server

import android.content.Context
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import com.manus.remotecontrol.service.AccessibilityInputService
import com.manus.remotecontrol.service.RemoteControlService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.http.content.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class WebServer(
    private val context: Context,
    private val port: Int,
    private val pinCode: String,
    private val remoteControlService: RemoteControlService
) {
    private var server: NettyApplicationEngine? = null
    private val isRunning = AtomicBoolean(false)

    fun start() {
        if (isRunning.get()) return

        server = embeddedServer(Netty, port = port) {
            install(WebSockets)
            
            routing {
                get("/") {
                    try {
                        val indexContent = this@WebServer.context.assets.open("web/index.html").bufferedReader().use { it.readText() }
                        call.respondText(indexContent, ContentType.Text.Html)
                    } catch (e: Exception) {
                        call.respondText("Error loading UI: ${e.message}", ContentType.Text.Plain)
                    }
                }
                
                get("/jmuxer.min.js") {
                    try {
                        val jsContent = this@WebServer.context.assets.open("web/jmuxer.min.js").bufferedReader().use { it.readText() }
                        call.respondText(jsContent, ContentType.Application.JavaScript)
                    } catch (e: Exception) {
                        call.respondText("Error loading JMuxer: ${e.message}", ContentType.Text.Plain)
                    }
                }

                webSocket("/control") {
                    try {
                        val authFrame = incoming.receive() as? Frame.Text ?: return@webSocket
                        val authJson = JSONObject(authFrame.readText())
                        if (authJson.optString("pin") != pinCode) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid PIN"))
                            return@webSocket
                        }
                        
                        // Send existing SPS/PPS headers if available immediately upon connection
                        val headers = remoteControlService.getVideoHeaders()
                        if (headers != null) {
                            val packet = ByteArray(headers.size + 1)
                            packet[0] = 0x02 // Video type
                            System.arraycopy(headers, 0, packet, 1, headers.size)
                            send(Frame.Binary(true, packet))
                            Log.d("WebServer", "Sent cached SPS/PPS headers to new client")
                        }
                        
                        // Launch two parallel jobs for streaming
                        // The client will only receive data from the active flow (Photo or Video)
                        
                        val photoJob = launch {
                            remoteControlService.imageFlow
                                .conflate()
                                .collect { bytes ->
                                    try {
                                        // Prefix with 0x01 for Photo
                                        val packet = ByteArray(bytes.size + 1)
                                        packet[0] = 0x01
                                        System.arraycopy(bytes, 0, packet, 1, bytes.size)
                                        send(Frame.Binary(true, packet))
                                    } catch (e: Exception) {}
                                }
                        }
                        
                        val videoJob = launch {
                            remoteControlService.h264Flow
                                .conflate()
                                .collect { bytes ->
                                    try {
                                        // Prefix with 0x02 for Video
                                        val packet = ByteArray(bytes.size + 1)
                                        packet[0] = 0x02
                                        System.arraycopy(bytes, 0, packet, 1, bytes.size)
                                        send(Frame.Binary(true, packet))
                                    } catch (e: Exception) {}
                                }
                        }

                        incoming.consumeEach { frame ->
                            if (frame is Frame.Text) {
                                handleCommand(frame.readText())
                            }
                        }
                        
                        photoJob.cancel()
                        videoJob.cancel()
                    } catch (e: Exception) {
                        Log.e("WebServer", "WebSocket error", e)
                    }
                }
            }
        }
        
        server?.start(wait = false)
        isRunning.set(true)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        isRunning.set(false)
    }

    private fun handleCommand(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val type = json.optString("type")
            
            when (type) {
                "mode" -> {
                    val mode = json.optString("value", "photo")
                    remoteControlService.setMode(mode)
                }
                "photo_settings" -> {
                    val scale = json.optDouble("scale", 2.0).toFloat()
                    val quality = json.optInt("quality", 50)
                    remoteControlService.updatePhotoSettings(scale, quality)
                }
                "video_settings" -> {
                    val bitrate = json.optInt("bitrate", 1000000)
                    val fps = json.optInt("fps", 30)
                    val scale = json.optDouble("scale", 0.5).toFloat()
                    remoteControlService.updateVideoSettings(bitrate, fps, scale)
                }
                else -> {
                    // Input events
                    val service = AccessibilityInputService.instance ?: return
                    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val metrics = DisplayMetrics()
                    windowManager.defaultDisplay.getRealMetrics(metrics)
                    val screenWidth = metrics.widthPixels
                    val screenHeight = metrics.heightPixels

                    when (type) {
                        "tap" -> {
                            val relX = json.optDouble("x").toFloat()
                            val relY = json.optDouble("y").toFloat()
                            service.performTap(relX * screenWidth, relY * screenHeight)
                        }
                        "swipe" -> {
                            val relX1 = json.optDouble("x1").toFloat()
                            val relY1 = json.optDouble("y1").toFloat()
                            val relX2 = json.optDouble("x2").toFloat()
                            val relY2 = json.optDouble("y2").toFloat()
                            val duration = json.optLong("duration", 300)
                            service.performSwipe(relX1 * screenWidth, relY1 * screenHeight, relX2 * screenWidth, relY2 * screenHeight, duration)
                        }
                        "key" -> {
                            when (json.optString("code")) {
                                "BACK" -> service.performGlobalActionBack()
                                "HOME" -> service.performGlobalActionHome()
                                "RECENTS" -> service.performGlobalActionRecents()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebServer", "Command error", e)
        }
    }
}

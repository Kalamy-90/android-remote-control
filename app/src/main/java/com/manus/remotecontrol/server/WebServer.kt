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
                // Serve index.html manually from assets to ensure it works on Android
                get("/") {
                    try {
                        val indexContent = this@WebServer.context.assets.open("web/index.html").bufferedReader().use { it.readText() }
                        call.respondText(indexContent, ContentType.Text.Html)
                    } catch (e: Exception) {
                        call.respondText("Error loading UI: ${e.message}", ContentType.Text.Plain)
                        Log.e("WebServer", "Error serving index.html", e)
                    }
                }

                // WebSocket for control and streaming
                webSocket("/control") {
                    try {
                        // Auth check
                        val authFrame = incoming.receive() as? Frame.Text ?: return@webSocket
                        val authJson = JSONObject(authFrame.readText())
                        if (authJson.optString("pin") != pinCode) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid PIN"))
                            return@webSocket
                        }
                        
                        // Start streaming video to this client
                        // Use conflate() to drop old frames if the client is slow
                        // This ensures we always send the LATEST frame available
                        val videoJob = launch {
                            remoteControlService.jpegFlow
                                .conflate() // Drop intermediate values if collector is slow
                                .collect { jpegBytes ->
                                    try {
                                        send(Frame.Binary(true, jpegBytes))
                                    } catch (e: Exception) {
                                        // Ignore send errors (client might have disconnected)
                                    }
                                }
                        }

                        // Handle incoming control commands
                        incoming.consumeEach { frame ->
                            if (frame is Frame.Text) {
                                handleCommand(frame.readText())
                            }
                        }
                        
                        videoJob.cancel()
                    } catch (e: Exception) {
                        Log.e("WebServer", "WebSocket error", e)
                    }
                }
            }
        }
        
        server?.start(wait = false)
        isRunning.set(true)
        Log.d("WebServer", "Server started on port $port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        isRunning.set(false)
        Log.d("WebServer", "Server stopped")
    }

    private fun handleCommand(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val type = json.optString("type")
            
            // Handle quality update command first (doesn't need AccessibilityService)
            if (type == "quality") {
                val scale = json.optDouble("scale", 3.0).toFloat()
                val quality = json.optInt("quality", 50)
                remoteControlService.updateQuality(scale, quality)
                return
            }

            val service = AccessibilityInputService.instance ?: return

            // Get screen dimensions for relative coordinate conversion
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            when (type) {
                "tap" -> {
                    // Coordinates are now relative (0.0 - 1.0)
                    val relX = json.optDouble("x").toFloat()
                    val relY = json.optDouble("y").toFloat()
                    
                    // Convert to absolute coordinates
                    val absX = relX * screenWidth
                    val absY = relY * screenHeight
                    
                    service.performTap(absX, absY)
                }
                "swipe" -> {
                    // Coordinates are now relative (0.0 - 1.0)
                    val relX1 = json.optDouble("x1").toFloat()
                    val relY1 = json.optDouble("y1").toFloat()
                    val relX2 = json.optDouble("x2").toFloat()
                    val relY2 = json.optDouble("y2").toFloat()
                    
                    // Convert to absolute coordinates
                    val absX1 = relX1 * screenWidth
                    val absY1 = relY1 * screenHeight
                    val absX2 = relX2 * screenWidth
                    val absY2 = relY2 * screenHeight
                    
                    val duration = json.optLong("duration", 300)
                    service.performSwipe(absX1, absY1, absX2, absY2, duration)
                }
                "key" -> {
                    when (json.optString("code")) {
                        "BACK" -> service.performGlobalActionBack()
                        "HOME" -> service.performGlobalActionHome()
                        "RECENTS" -> service.performGlobalActionRecents()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebServer", "Command error", e)
        }
    }
}

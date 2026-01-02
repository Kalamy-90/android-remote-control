package com.manus.remotecontrol.server

import android.content.Context
import android.util.Log
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
                        val indexContent = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
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
                        val videoJob = launch {
                            remoteControlService.jpegFlow.collectLatest { jpegBytes ->
                                send(Frame.Binary(true, jpegBytes))
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
            val service = AccessibilityInputService.instance ?: return

            when (type) {
                "tap" -> {
                    val x = json.optDouble("x").toFloat()
                    val y = json.optDouble("y").toFloat()
                    service.performTap(x, y)
                }
                "swipe" -> {
                    val x1 = json.optDouble("x1").toFloat()
                    val y1 = json.optDouble("y1").toFloat()
                    val x2 = json.optDouble("x2").toFloat()
                    val y2 = json.optDouble("y2").toFloat()
                    val duration = json.optLong("duration", 300)
                    service.performSwipe(x1, y1, x2, y2, duration)
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

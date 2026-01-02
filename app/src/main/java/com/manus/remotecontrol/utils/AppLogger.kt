package com.manus.remotecontrol.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private val logs = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "$timestamp [$tag] $message\n"
        synchronized(logs) {
            logs.append(logEntry)
        }
        Log.d(tag, message)
    }

    fun error(tag: String, message: String, e: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "$timestamp [$tag] ERROR: $message\n${e?.stackTraceToString() ?: ""}\n"
        synchronized(logs) {
            logs.append(logEntry)
        }
        Log.e(tag, message, e)
    }

    fun getLogs(): String {
        synchronized(logs) {
            return logs.toString()
        }
    }
    
    fun clear() {
        synchronized(logs) {
            logs.setLength(0)
        }
    }
}

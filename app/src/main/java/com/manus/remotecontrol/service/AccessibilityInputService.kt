package com.manus.remotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.os.Build

class AccessibilityInputService : AccessibilityService() {

    companion object {
        var instance: AccessibilityInputService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AccessibilityInput", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for now
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun performTap(x: Float, y: Float) {
        try {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e("AccessibilityInput", "Error performing tap", e)
        }
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        try {
            val path = Path()
            path.moveTo(x1, y1)
            path.lineTo(x2, y2)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e("AccessibilityInput", "Error performing swipe", e)
        }
    }
    
    fun performGlobalActionBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
    
    fun performGlobalActionHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
    
    fun performGlobalActionRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
    
    fun performGlobalActionLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}

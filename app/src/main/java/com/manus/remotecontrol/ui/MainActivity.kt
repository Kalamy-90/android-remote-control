package com.manus.remotecontrol.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.manus.remotecontrol.R
import com.manus.remotecontrol.service.AccessibilityInputService
import com.manus.remotecontrol.service.RemoteControlService

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvPin: TextView
    private lateinit var btnToggleServer: Button
    private lateinit var btnAccessibility: Button

    private val SCREEN_CAPTURE_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvUrl = findViewById(R.id.tvUrl)
        tvPin = findViewById(R.id.tvPin)
        btnToggleServer = findViewById(R.id.btnToggleServer)
        btnAccessibility = findViewById(R.id.btnAccessibility)

        btnToggleServer.setOnClickListener {
            if (RemoteControlService.isRunning) {
                stopServer()
            } else {
                if (checkAccessibilityPermission()) {
                    requestScreenCapture()
                } else {
                    Toast.makeText(this, R.string.accessibility_permission_required, Toast.LENGTH_LONG).show()
                }
            }
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        if (RemoteControlService.isRunning) {
            tvStatus.text = getString(R.string.server_running)
            tvUrl.text = "http://${RemoteControlService.currentIp}:8080"
            tvPin.text = "PIN: ${RemoteControlService.currentPin}"
            btnToggleServer.text = getString(R.string.stop_server)
            tvUrl.visibility = View.VISIBLE
            tvPin.visibility = View.VISIBLE
        } else {
            tvStatus.text = getString(R.string.server_stopped)
            tvUrl.visibility = View.GONE
            tvPin.visibility = View.GONE
            btnToggleServer.text = getString(R.string.start_server)
        }

        if (checkAccessibilityPermission()) {
            btnAccessibility.visibility = View.GONE
        } else {
            btnAccessibility.visibility = View.VISIBLE
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        return AccessibilityInputService.instance != null
    }

    private fun requestScreenCapture() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startServerService(resultCode, data)
            } else {
                Toast.makeText(this, R.string.screen_capture_permission_required, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startServerService(resultCode: Int, data: Intent) {
        val intent = Intent(this, RemoteControlService::class.java).apply {
            action = RemoteControlService.ACTION_START
            putExtra(RemoteControlService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RemoteControlService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
        // Delay UI update slightly to allow service to start
        btnToggleServer.postDelayed({ updateUI() }, 500)
    }

    private fun stopServer() {
        val intent = Intent(this, RemoteControlService::class.java).apply {
            action = RemoteControlService.ACTION_STOP
        }
        startService(intent)
        btnToggleServer.postDelayed({ updateUI() }, 500)
    }
}

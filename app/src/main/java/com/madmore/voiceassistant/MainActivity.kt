package com.madmore.voiceassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val requestCode = 501
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var listenButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (!hasRequiredPermissions()) requestRequiredPermissions() else showReady()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 34, 28, 28)
            setBackgroundColor(Color.rgb(248, 249, 253))
        }

        val title = TextView(this).apply {
            text = "HELLO"
            textSize = 38f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(55, 61, 80))
        }

        val subtitle = TextView(this).apply {
            text = "Your voice assistant"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(100, 105, 120))
        }

        status = TextView(this).apply {
            text = "●  READY"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 38, 0, 18)
            setTextColor(Color.rgb(38, 145, 105))
            contentDescription = "Assistant status"
        }

        hint = TextView(this).apply {
            text = "Say  “Hello”  or  “Helloooo”\n\nThen say:  “Call Padmore for me”"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(55, 61, 80))
            setPadding(18, 22, 18, 22)
        }

        listenButton = bigButton("LISTEN NOW") { startAssistant(GrandfatherAssistantService.ACTION_LISTEN_NOW) }
        stopButton = bigButton("PAUSE HELLO") { startAssistant(GrandfatherAssistantService.ACTION_STOP) }

        val help = TextView(this).apply {
            text = "You can speak normally.\nHello can use your real contacts and can ask before calling when a name is unclear."
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(105, 110, 125))
            setPadding(20, 25, 20, 0)
        }

        root.addView(title, LinearLayout.LayoutParams(-1, 70))
        root.addView(subtitle, LinearLayout.LayoutParams(-1, 50))
        root.addView(status, LinearLayout.LayoutParams(-1, 105))
        root.addView(hint, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(listenButton, LinearLayout.LayoutParams(-1, 145))
        root.addView(stopButton, LinearLayout.LayoutParams(-1, 115))
        root.addView(help, LinearLayout.LayoutParams(-1, 115))
        setContentView(root)
    }

    private fun bigButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 27f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        minHeight = 100
        setOnClickListener { action() }
        contentDescription = label
    }

    private fun hasRequiredPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), requestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode && hasRequiredPermissions()) showReady()
        else {
            status.text = "PERMISSIONS NEEDED"
            hint.text = "Please allow microphone, contacts and phone calls so Hello can help."
        }
    }

    private fun showReady() {
        status.text = "●  HELLO IS READY"
        hint.text = "Say  “Hello”  or  “Helloooo”\n\nThen say:  “Call Padmore for me”"
        startAssistant(GrandfatherAssistantService.ACTION_START)
    }

    private fun startAssistant(action: String) {
        if (!hasRequiredPermissions()) { requestRequiredPermissions(); return }
        try {
            ContextCompat.startForegroundService(this, Intent(this, GrandfatherAssistantService::class.java).setAction(action))
            status.text = if (action == GrandfatherAssistantService.ACTION_STOP) "●  PAUSED" else "●  LISTENING FOR HELLO"
        } catch (_: Exception) {
            status.text = "OPEN HELLO TO START"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && hasRequiredPermissions()) status.text = "●  HELLO IS READY"
    }
}

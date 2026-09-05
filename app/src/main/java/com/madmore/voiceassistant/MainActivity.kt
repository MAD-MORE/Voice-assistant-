package com.madmore.voiceassistant

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val requestCode = 501
    private val assistantRoleRequest = 502
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
            text = "After setup, you do not need to press Hello.\n\nSay “Hello” and then tell me what you need."
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(55, 61, 80))
            setPadding(18, 22, 18, 22)
        }
        listenButton = bigButton("LISTEN NOW") { startAssistant(GrandfatherAssistantService.ACTION_LISTEN_NOW) }
        stopButton = bigButton("PAUSE HELLO") { startAssistant(GrandfatherAssistantService.ACTION_STOP) }
        val help = TextView(this).apply {
            text = "Hello can use your real contacts, understand common pronunciation differences, and ask before making an uncertain call."
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
            hint.text = "A helper needs to allow microphone, contacts and phone calls once during setup."
        }
    }

    private fun showReady() {
        status.text = "●  HELLO IS READY"
        hint.text = "After setup, Grandpa does not need to press a button.\n\nSay “Hello” and speak naturally."
        requestAssistantRoleIfAvailable()
        startAssistant(GrandfatherAssistantService.ACTION_START)
    }

    private fun requestAssistantRoleIfAvailable() {
        if (Build.VERSION.SDK_INT < 29) return
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return
        if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return
        if (isFinishing || isChangingConfigurations) return
        startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), assistantRoleRequest)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == assistantRoleRequest && resultCode == RESULT_OK && hasRequiredPermissions()) {
            startAssistant(GrandfatherAssistantService.ACTION_START)
        }
    }

    private fun startAssistant(action: String) {
        if (!hasRequiredPermissions()) { requestRequiredPermissions(); return }
        try {
            ContextCompat.startForegroundService(this, Intent(this, GrandfatherAssistantService::class.java).setAction(action))
            status.text = if (action == GrandfatherAssistantService.ACTION_STOP) "●  PAUSED" else "●  LISTENING FOR HELLO"
        } catch (_: Exception) {
            status.text = "HELLO NEEDS SETUP"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && hasRequiredPermissions()) status.text = "●  HELLO IS READY"
    }
}

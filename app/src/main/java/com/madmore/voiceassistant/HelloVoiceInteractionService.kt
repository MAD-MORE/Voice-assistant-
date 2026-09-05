package com.madmore.voiceassistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService

/**
 * System-level entry point for Hello when the user selects it as the device assistant.
 * Android keeps the selected VoiceInteractionService alive specifically for background
 * voice interactions/hotwording, avoiding a normal Activity-startup dependency.
 */
class HelloVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        startHelloService()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        startHelloService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startHelloService()
        return START_STICKY
    }

    private fun startHelloService() {
        val start = Intent(this, GrandfatherAssistantService::class.java)
            .setAction(GrandfatherAssistantService.ACTION_START)
        try {
            startForegroundService(start)
        } catch (_: Exception) {
            // Android may reject a second/redundant start while the assistant service is already active.
        }
    }
}

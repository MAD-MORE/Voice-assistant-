package com.madmore.voiceassistant

import android.content.Intent
import android.os.IBinder
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Lightweight session host. The real command engine remains in GrandfatherAssistantService. */
class HelloVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: BundleCompat): VoiceInteractionSession = HelloVoiceInteractionSession(this)
}

private class HelloVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context)

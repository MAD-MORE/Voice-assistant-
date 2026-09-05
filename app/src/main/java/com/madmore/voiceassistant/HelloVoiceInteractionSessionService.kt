package com.madmore.voiceassistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Lightweight session host. The real command engine remains in GrandfatherAssistantService. */
class HelloVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle): VoiceInteractionSession = HelloVoiceInteractionSession(this)
}

private class HelloVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context)

package com.madmore.voiceassistant

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

/**
 * Selects Android's on-device recognizer when the device provides one.
 * Falls back to the normal recognizer with EXTRA_PREFER_OFFLINE enabled.
 * Command parsing and contact matching remain local and never require a server.
 */
object OfflineSpeech {
    fun create(context: Context): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    fun isTrueOnDeviceAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
}

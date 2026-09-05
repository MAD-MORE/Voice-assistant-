package com.madmore.voiceassistant

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

/**
 * Speech recognizer selection.
 *
 * Prefer Android's true on-device recognizer when the phone provides one.
 * Older Android releases and some devices do not expose that API, so returning
 * null would make the whole assistant silently stop listening. In that case we
 * use the platform recognizer with EXTRA_PREFER_OFFLINE requested by the caller.
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

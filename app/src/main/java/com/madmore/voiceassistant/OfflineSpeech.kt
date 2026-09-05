package com.madmore.voiceassistant

import android.content.Context
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Compatibility layer for Android 8 (API 26) through current Android.
 *
 * Android 12+ may expose a dedicated on-device recognizer. Older devices do
 * not have that API, so we use the platform recognition service instead.
 * The caller still requests offline recognition first, but the assistant
 * must never become silent merely because a particular device lacks the
 * on-device API.
 */
object OfflineSpeech {
    data class Capabilities(
        val recognizerAvailable: Boolean,
        val onDeviceAvailable: Boolean,
        val apiLevel: Int
    )

    fun capabilities(context: Context): Capabilities {
        val recognizer = SpeechRecognizer.isRecognitionAvailable(context)
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return Capabilities(recognizer, onDevice, Build.VERSION.SDK_INT)
    }

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

    fun configureOfflineFirst(intent: android.content.Intent) {
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Keep language broad enough for Ghanaian English/Akan recognition on
        // devices whose installed recognizer does not expose en-GH offline data.
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GH")
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-GH")
    }

    fun isTrueOnDeviceAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
}

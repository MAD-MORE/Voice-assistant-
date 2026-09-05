package com.madmore.voiceassistant

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

/**
 * Strict offline recognizer selection.
 *
 * We deliberately do not fall back to the generic/network recognizer. The
 * assistant's core voice path must remain deterministic and local when it
 * advertises offline mode.
 */
object OfflineSpeech {
    fun create(context: Context): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
        return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    }

    fun isTrueOnDeviceAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
}

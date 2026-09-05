package com.madmore.voiceassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.Locale

class GrandfatherAssistantService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val CHANNEL_ID = "grandfather_assistant"
        const val NOTIFICATION_ID = 7001
        const val ACTION_START = "com.madmore.voiceassistant.START_ASSISTANT"
        const val ACTION_STOP = "com.madmore.voiceassistant.STOP_ASSISTANT"
    }

    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var parser: CommandParser
    private lateinit var contacts: ContactRepository
    private lateinit var aliases: AliasStore
    private var ttsReady = false
    private var listeningForCommand = false
    private var waitingForConfirmation: ContactCandidate? = null
    private var destroyed = false
    private val handler = Handler()

    override fun onCreate() {
        super.onCreate()
        parser = CommandParser()
        contacts = ContactRepository(this)
        aliases = AliasStore(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        tts = TextToSpeech(this, this)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer.setRecognitionListener(listener)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (::recognizer.isInitialized) listenForWakeWord()
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hello assistant is ready")
            .setContentText("Say hello or helloooo to wake her up")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Grandfather voice assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Keeps the screen-free wake-word assistant available."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun listenForWakeWord() {
        if (destroyed || !::recognizer.isInitialized || !hasMicPermission()) return
        listeningForCommand = false
        startRecognition()
    }

    private fun listenForCommand() {
        if (destroyed || !::recognizer.isInitialized || !hasMicPermission()) return
        listeningForCommand = true
        startRecognition()
    }

    private fun startRecognition() {
        recognizer.cancel()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GH")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-GH")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { recognizer.startListening(intent) } catch (_: Exception) { scheduleWakeRetry() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            if (!destroyed) scheduleWakeRetry()
        }

        override fun onResults(results: Bundle) {
            if (destroyed) return
            val phrases = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (!listeningForCommand) {
                if (phrases.any(::isWakePhrase)) {
                    speak("What should I do for you?")
                    handler.postDelayed({ listenForCommand() }, 1500)
                } else {
                    listenForWakeWord()
                }
            } else {
                handleCommand(phrases.firstOrNull().orEmpty())
            }
        }
    }

    private fun isWakePhrase(value: String): Boolean {
        val text = parser.normalize(value)
        return text == "hello" || text == "helloooo" || text == "helo" ||
            text.startsWith("hello ") || text.startsWith("helloooo ")
    }

    private fun handleCommand(spoken: String) {
        if (spoken.isBlank()) {
            speak("I did not hear you. Please say it again.")
            retryCommand()
            return
        }
        if (waitingForConfirmation != null) {
            val answer = parser.normalize(spoken)
            val candidate = waitingForConfirmation
            waitingForConfirmation = null
            if (answer == "yes" || answer == "y") callContact(candidate)
            else speak("Okay, I will not call.")
            retryWakeAfterSpeech()
            return
        }
        val normalized = parser.normalize(spoken)
        if (normalized.startsWith("remember ") && normalized.contains(" as ")) {
            val body = normalized.removePrefix("remember ")
            val person = body.substringBefore(" as ").trim()
            val relationship = body.substringAfter(" as ").trim()
            val candidate = contacts.findCandidates(person, 1).firstOrNull()
            if (candidate != null && candidate.score >= 0.45) {
                aliases.saveAlias(relationship, candidate.name)
                speak("Okay. I will remember ${candidate.name} as $relationship.")
            } else speak("I could not find that person in your contacts.")
            retryWakeAfterSpeech()
            return
        }
        val command = parser.parse(spoken)
        when (command.type) {
            CommandType.STOP -> {
                waitingForConfirmation = null
                speak("Okay. I am here when you need me.")
                retryWakeAfterSpeech()
            }
            CommandType.HELP -> {
                speak("You can say call a person's name, or say Frɛ me ba for your saved child.")
                retryWakeAfterSpeech()
            }
            CommandType.LIST_CONTACTS -> {
                val names = contacts.allNames()
                speak(if (names.isEmpty()) "There are no phone contacts available." else "Your contacts include ${names.joinToString(", ")}.")
                retryWakeAfterSpeech()
            }
            CommandType.CALL -> resolveAndCall(command.target.orEmpty())
            CommandType.UNKNOWN -> {
                speak("I can call anyone in your contacts. Try saying call Padmore.")
                retryCommand()
            }
        }
    }

    private fun resolveAndCall(target: String) {
        val query = aliases.resolveAlias(target) ?: target
        val candidates = contacts.findCandidates(query, 5)
        if (candidates.isEmpty()) {
            speak("I cannot find $target in your contacts. Please say the name again.")
            retryCommand()
            return
        }
        val top = candidates.first()
        if (top.score >= 0.82 && (candidates.size == 1 || top.score - candidates[1].score >= 0.12)) {
            speak("Calling ${top.name}.")
            handler.postDelayed({ callContact(top) }, 900)
        } else {
            waitingForConfirmation = top
            val alternatives = candidates.take(3).joinToString(", ") { it.name }
            speak("Did you mean ${top.name}? Other matches are $alternatives. Say yes or no.")
            retryCommand()
        }
    }

    private fun callContact(candidate: ContactCandidate?) {
        if (candidate == null) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            speak("Phone permission is not available.")
            retryWakeAfterSpeech()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${candidate.phone}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            speak("I could not start the call. Please try again.")
            retryWakeAfterSpeech()
        }
    }

    private fun retryCommand() = handler.postDelayed({ listenForCommand() }, 1800)
    private fun retryWakeAfterSpeech() = handler.postDelayed({ listenForWakeWord() }, 2600)
    private fun scheduleWakeRetry() = handler.postDelayed({ listenForWakeWord() }, 1000)

    private fun hasMicPermission() = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun speak(message: String) {
        if (ttsReady) tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "grandfather-${System.currentTimeMillis()}")
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val gh = Locale("en", "GH")
            val result = tts.setLanguage(gh)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts.language = Locale.US
            tts.setSpeechRate(0.9f)
            tts.setPitch(1.0f)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        if (::recognizer.isInitialized) recognizer.destroy()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}

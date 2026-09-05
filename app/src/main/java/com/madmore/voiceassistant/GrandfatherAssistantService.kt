package com.madmore.voiceassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class GrandfatherAssistantService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val CHANNEL_ID = "grandfather_assistant"
        const val NOTIFICATION_ID = 7001
        const val ACTION_START = "com.madmore.voiceassistant.START_ASSISTANT"
        const val ACTION_STOP = "com.madmore.voiceassistant.STOP_ASSISTANT"
        const val ACTION_LISTEN_NOW = "com.madmore.voiceassistant.LISTEN_NOW"
        private val LISTEN_TOKEN = Any()
    }

    private enum class Mode { IDLE, WAKE, COMMAND, CONFIRMATION, SPEAKING }
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var parser: CommandParser
    private lateinit var contacts: ContactRepository
    private lateinit var aliases: AliasStore
    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.IDLE
    private var speechResumeMode = Mode.WAKE
    private var speechShouldListen = true
    private var waitingForConfirmation: ContactCandidate? = null
    private var ttsReady = false
    private var destroyed = false
    private var lastRecognitionStart = 0L
    private var recognitionFailures = 0

    override fun onCreate() {
        super.onCreate()
        parser = CommandParser()
        contacts = ContactRepository(this)
        aliases = AliasStore(this)
        createNotificationChannel()
        promoteToForeground()
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { handler.post { if (!destroyed) finishSpeaking() } }
            override fun onError(id: String?) { handler.post { if (!destroyed) finishSpeaking() } }
        })
        OfflineSpeech.create(this)?.also {
            recognizer = it
            recognizer.setRecognitionListener(listener)
        }
    }

    private fun promoteToForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { mode = Mode.IDLE; waitingForConfirmation = null; stopSelf(); return START_NOT_STICKY }
            ACTION_LISTEN_NOW -> if (::recognizer.isInitialized && hasMicPermission()) speak("What should I do for you?", Mode.COMMAND)
            else -> if (::recognizer.isInitialized && hasMicPermission()) { mode = Mode.WAKE; scheduleListening(300) }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Hello is ready")
        .setContentText("On-device listening when available")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hello assistant", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Voice assistant foreground service"
                }
            )
        }
    }

    private fun scheduleListening(delayMs: Long) {
        handler.removeCallbacksAndMessages(LISTEN_TOKEN)
        handler.postAtTime({
            if (!destroyed && mode != Mode.SPEAKING && ::recognizer.isInitialized && hasMicPermission()) startRecognition()
        }, LISTEN_TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    private fun startRecognition() {
        if (destroyed || mode == Mode.SPEAKING || !::recognizer.isInitialized || !hasMicPermission()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRecognitionStart < 700) return
        lastRecognitionStart = now
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GH")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-GH")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700)
        }
        try { recognizer.startListening(intent) }
        catch (_: Exception) { recognitionFailures++; scheduleListening(minOf(5000L, 500L * recognitionFailures)) }
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
            if (destroyed || mode == Mode.SPEAKING) return
            recognitionFailures++
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1800L
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 500L
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 1200L
                else -> minOf(5000L, 500L * recognitionFailures)
            }
            scheduleListening(delay)
        }

        override fun onResults(results: Bundle) {
            if (destroyed || mode == Mode.SPEAKING) return
            recognitionFailures = 0
            val phrases = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (mode == Mode.WAKE || mode == Mode.IDLE) handleWakeResults(phrases) else handleCommandResults(phrases)
        }
    }

    private fun handleWakeResults(phrases: List<String>) {
        val wake = phrases.firstOrNull(::isWakePhrase)
        if (wake == null) { mode = Mode.WAKE; scheduleListening(220); return }
        val remainder = removeWakeWord(wake)
        if (remainder.isNotBlank()) { mode = Mode.COMMAND; handleCommand(remainder) }
        else speak("What should I do for you?", Mode.COMMAND)
    }

    private fun handleCommandResults(phrases: List<String>) {
        if (waitingForConfirmation != null) {
            val answer = phrases.asSequence().map(parser::normalize).firstOrNull { it == "yes" || it == "y" || it == "no" || it == "n" }
            if (answer == null) speak("Please say yes to call, or no to cancel.", Mode.CONFIRMATION)
            else {
                val c = waitingForConfirmation; waitingForConfirmation = null
                if (answer == "yes" || answer == "y") callContact(c) else speak("Okay. I will not call.", Mode.WAKE)
            }
            return
        }
        val spoken = phrases.asSequence().mapNotNull { p -> parser.parse(p).takeIf { it.type != CommandType.UNKNOWN }?.raw }.firstOrNull()
            ?: phrases.firstOrNull().orEmpty()
        handleCommand(spoken)
    }

    private fun isWakePhrase(value: String): Boolean {
        val t = parser.normalize(value)
        return t == "hello" || t == "helloooo" || t == "helo" || t.startsWith("hello ") || t.startsWith("helloooo ") || t.startsWith("helo ")
    }

    private fun removeWakeWord(value: String): String {
        val t = parser.normalize(value)
        return when {
            t.startsWith("helloooo ") -> t.removePrefix("helloooo ").trim()
            t.startsWith("hello ") -> t.removePrefix("hello ").trim()
            t.startsWith("helo ") -> t.removePrefix("helo ").trim()
            else -> ""
        }
    }

    private fun handleCommand(spoken: String) {
        if (spoken.isBlank()) { speak("I did not hear you. Please say it again.", Mode.COMMAND); return }
        val command = parser.parse(spoken)
        val normalized = parser.normalize(spoken)
        if (normalized.startsWith("remember ") && normalized.contains(" as ")) {
            val body = normalized.removePrefix("remember ")
            val person = body.substringBefore(" as ").trim()
            val relationship = body.substringAfter(" as ").trim()
            val c = contacts.findCandidates(person, 1).firstOrNull()
            if (c != null && c.score >= 0.45) { aliases.saveAlias(relationship, c.name); speak("Okay. I will remember ${c.name} as $relationship.", Mode.WAKE) }
            else speak("I could not find that person in your contacts.", Mode.WAKE)
            return
        }
        when (command.type) {
            CommandType.STOP -> { waitingForConfirmation = null; speak("Okay. I am here when you need me.", Mode.WAKE) }
            CommandType.HELP -> speak("Say hello, then say call a person's name. You can also say stop or remember a name as your son.", Mode.WAKE)
            CommandType.LIST_CONTACTS -> { val names = contacts.allNames(); speak(if (names.isEmpty()) "There are no phone contacts available." else "Your contacts include ${names.joinToString(", ")}.", Mode.WAKE) }
            CommandType.CALL -> resolveAndCall(command.target.orEmpty())
            CommandType.UNKNOWN -> speak("I can make calls. Please say call, followed by the person's name.", Mode.COMMAND)
        }
    }

    private fun resolveAndCall(target: String) {
        val query = aliases.resolveAlias(target) ?: target
        val candidates = contacts.findCandidates(query, 5)
        if (candidates.isEmpty()) { speak("I cannot find $target in your contacts. Please say the name again.", Mode.COMMAND); return }
        val top = candidates.first(); val second = candidates.getOrNull(1)
        val confident = top.score >= 0.86 && (second == null || top.score - second.score >= 0.10)
        if (confident) {
            waitingForConfirmation = null
            speak("Calling ${top.name}.", Mode.WAKE, false)
            handler.postDelayed({ if (!destroyed) callContact(top) }, 1100)
        } else {
            waitingForConfirmation = top
            val alternatives = candidates.take(3).joinToString(", ") { it.name }
            speak("I heard $target. Did you mean ${top.name}? Other matches are $alternatives. Say yes or no.", Mode.CONFIRMATION)
        }
    }

    private fun callContact(candidate: ContactCandidate?) {
        if (candidate == null) { mode = Mode.WAKE; scheduleListening(250); return }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            speak("Phone permission is not available. Please open Hello and allow phone calls.", Mode.WAKE); return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${candidate.phone.replace("#", "%23")}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            mode = Mode.WAKE
        } catch (_: Exception) { speak("I could not start the call. Please try again.", Mode.WAKE) }
    }

    private fun speak(message: String, resumeMode: Mode, listenAfter: Boolean = true) {
        if (!ttsReady || destroyed) return
        speechResumeMode = resumeMode; speechShouldListen = listenAfter; mode = Mode.SPEAKING
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "hello-${System.currentTimeMillis()}")
    }

    private fun finishSpeaking() { mode = speechResumeMode; if (speechShouldListen) scheduleListening(300) }
    private fun hasMicPermission() = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val gh = java.util.Locale("en", "GH")
            if (tts.setLanguage(gh) < 0) tts.language = java.util.Locale.US
            tts.setSpeechRate(0.88f); tts.setPitch(1.0f); mode = Mode.WAKE; scheduleListening(500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null)
        if (::recognizer.isInitialized) recognizer.destroy()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

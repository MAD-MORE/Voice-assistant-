package com.madmore.voiceassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
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

    private var recognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var parser: CommandParser
    private lateinit var contacts: ContactRepository
    private lateinit var aliases: AliasStore
    private val wakeDetector = WakeWordDetector()
    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.IDLE
    private var speechResumeMode = Mode.WAKE
    private var speechShouldListen = true
    private var waitingForConfirmation: ContactCandidate? = null
    private var ttsReady = false
    private var destroyed = false
    private var lastRecognitionStart = 0L
    private var recognitionFailures = 0
    private var currentlyListening = false

    override fun onCreate() {
        super.onCreate()
        parser = CommandParser()
        contacts = ContactRepository(this)
        aliases = AliasStore(this)
        createNotificationChannel()
        promoteToForeground()
        tts = TextToSpeech(this, this)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { handler.post { if (!destroyed) finishSpeaking() } }
            override fun onError(id: String?) { handler.post { if (!destroyed) finishSpeaking() } }
        })
        createRecognizer()
    }

    private fun createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = null
            return
        }
        try {
            recognizer = OfflineSpeech.create(this)?.also { it.setRecognitionListener(listener) }
        } catch (_: Exception) {
            recognizer = null
        }
    }

    private fun promoteToForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                waitingForConfirmation = null
                mode = Mode.IDLE
                speechShouldListen = false
                handler.removeCallbacksAndMessages(LISTEN_TOKEN)
                stopRecognition()
                if (ttsReady) tts.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LISTEN_NOW -> {
                if (!hasMicPermission()) return START_STICKY
                mode = Mode.COMMAND
                speak("What should I do for you?", Mode.COMMAND)
            }
            else -> {
                if (hasMicPermission()) {
                    mode = Mode.WAKE
                    if (ttsReady) speak("Hello is ready. Say hello when you need me.", Mode.WAKE)
                    else scheduleListening(250)
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Hello is ready")
        .setContentText(
            if (OfflineSpeech.isTrueOnDeviceAvailable(this)) "Offline voice is ready"
            else if (SpeechRecognizer.isRecognitionAvailable(this)) "Voice recognition is ready"
            else "Speech recognition unavailable"
        )
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
            if (!destroyed && mode != Mode.SPEAKING && recognizer != null && hasMicPermission()) {
                startRecognition()
            }
        }, LISTEN_TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    private fun startRecognition() {
        val r = recognizer ?: run {
            createRecognizer()
            if (recognizer == null) {
                if (ttsReady) speak("I cannot hear you because speech recognition is not available on this phone.", Mode.IDLE, false)
                return
            }
            recognizer!!
        }
        if (destroyed || mode == Mode.SPEAKING || !hasMicPermission()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (currentlyListening) return
        if (now - lastRecognitionStart < 500) {
            scheduleListening(550)
            return
        }
        lastRecognitionStart = now
        currentlyListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            OfflineSpeech.configureOfflineFirst(this)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
        }
        try {
            r.startListening(intent)
        } catch (_: Exception) {
            currentlyListening = false
            recognitionFailures++
            recreateRecognizerAfterFailure()
            scheduleListening(minOf(4000L, 500L * recognitionFailures))
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            currentlyListening = true
            recognitionFailures = 0
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { currentlyListening = false }
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            currentlyListening = false
            if (destroyed || mode == Mode.SPEAKING) return
            recognitionFailures++
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1800L
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 500L
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 1800L
                SpeechRecognizer.ERROR_CLIENT -> 800L
                else -> minOf(5000L, 600L * recognitionFailures)
            }
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recreateRecognizerAfterFailure()
            }
            scheduleListening(delay)
        }

        override fun onResults(results: Bundle) {
            currentlyListening = false
            if (destroyed || mode == Mode.SPEAKING) return
            recognitionFailures = 0
            val phrases = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (phrases.isEmpty()) {
                scheduleListening(350)
                return
            }
            if (mode == Mode.WAKE || mode == Mode.IDLE) handleWakeResults(phrases) else handleCommandResults(phrases)
        }
    }

    private fun recreateRecognizerAfterFailure() {
        if (destroyed) return
        try { recognizer?.cancel() } catch (_: Exception) { }
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        createRecognizer()
    }

    private fun stopRecognition() {
        currentlyListening = false
        try { recognizer?.cancel() } catch (_: Exception) { }
    }

    private fun handleWakeResults(phrases: List<String>) {
        val match = wakeDetector.detect(phrases)
        if (!match.matched) {
            mode = Mode.WAKE
            scheduleListening(450)
            return
        }
        val remainder = wakeDetector.removeWakeWord(match.text)
        if (remainder.isNotBlank()) {
            mode = Mode.COMMAND
            handleCommand(remainder)
        } else {
            speak("What should I do for you?", Mode.COMMAND)
        }
    }

    private fun handleCommandResults(phrases: List<String>) {
        if (waitingForConfirmation != null) {
            val answer = phrases.asSequence().map(parser::normalize).firstOrNull { it == "yes" || it == "y" || it == "no" || it == "n" }
            if (answer == null) speak("Please say yes to call, or no to cancel.", Mode.CONFIRMATION)
            else {
                val c = waitingForConfirmation
                waitingForConfirmation = null
                if (answer == "yes" || answer == "y") callContact(c) else speak("Okay. I will not call.", Mode.WAKE)
            }
            return
        }
        val ranked = phrases.map { phrase ->
            val parsed = parser.parse(phrase)
            val normalized = parser.normalize(phrase)
            val commandBias = when (parsed.type) {
                CommandType.CALL -> 1.0
                CommandType.STOP, CommandType.HELP, CommandType.LIST_CONTACTS -> 0.95
                CommandType.UNKNOWN -> 0.0
            }
            Triple(parsed, commandBias, normalized.length)
        }
        val spoken = ranked.asSequence()
            .filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Triple<VoiceCommand, Double, Int>> { it.second }.thenByDescending { it.third })
            .firstOrNull()?.first?.raw
            ?: phrases.firstOrNull().orEmpty()
        handleCommand(spoken)
    }

    private fun handleCommand(spoken: String) {
        if (spoken.isBlank()) {
            speak("I did not hear you. Please say it again.", Mode.COMMAND)
            return
        }
        val command = parser.parse(spoken)
        val normalized = parser.normalize(spoken)
        if (normalized.startsWith("remember ") && normalized.contains(" as ")) {
            val body = normalized.removePrefix("remember ")
            val person = body.substringBefore(" as ").trim()
            val relationship = body.substringAfter(" as ").trim()
            val c = contacts.findCandidates(person, 1).firstOrNull()
            if (c != null && c.score >= 0.45) {
                aliases.saveAlias(relationship, c.name)
                speak("Okay. I will remember ${c.name} as $relationship.", Mode.WAKE)
            } else speak("I could not find that person in your contacts.", Mode.WAKE)
            return
        }
        when (command.type) {
            CommandType.STOP -> {
                waitingForConfirmation = null
                speak("Okay. I am here when you need me.", Mode.WAKE)
            }
            CommandType.HELP -> speak("Say hello, then say call a person's name. You can also say stop or remember a name as your son.", Mode.WAKE)
            CommandType.LIST_CONTACTS -> {
                val names = contacts.allNames()
                speak(if (names.isEmpty()) "There are no phone contacts available." else "Your contacts include ${names.joinToString(", ")}.", Mode.WAKE)
            }
            CommandType.CALL -> resolveAndCall(command.target.orEmpty())
            CommandType.UNKNOWN -> speak("I can make calls. Please say call, followed by the person's name.", Mode.COMMAND)
        }
    }

    private fun resolveAndCall(target: String) {
        val query = aliases.resolveAlias(target) ?: target
        val candidates = contacts.findCandidates(query, 5)
        if (candidates.isEmpty()) {
            speak("I cannot find $target in your contacts. Please say the name again.", Mode.COMMAND)
            return
        }
        val top = candidates.first()
        val second = candidates.getOrNull(1)
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
        if (candidate == null) {
            mode = Mode.WAKE
            scheduleListening(500)
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            speak("Phone permission is not available. Please open Hello and allow phone calls.", Mode.WAKE)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${candidate.phone.replace("#", "%23")}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            mode = Mode.WAKE
        } catch (_: Exception) {
            speak("I could not start the call. Please try again.", Mode.WAKE)
        }
    }

    private fun speak(message: String, resumeMode: Mode, listenAfter: Boolean = true) {
        if (destroyed) return
        if (!ttsReady) {
            mode = resumeMode
            speechShouldListen = listenAfter
            if (listenAfter) scheduleListening(250)
            return
        }
        stopRecognition()
        handler.removeCallbacksAndMessages(LISTEN_TOKEN)
        speechResumeMode = resumeMode
        speechShouldListen = listenAfter
        mode = Mode.SPEAKING
        val result = tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "hello-${System.currentTimeMillis()}")
        if (result == TextToSpeech.ERROR) {
            mode = resumeMode
            if (listenAfter) scheduleListening(350)
        }
    }

    private fun finishSpeaking() {
        mode = speechResumeMode
        if (speechShouldListen) scheduleListening(550)
    }

    private fun hasMicPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onInit(status: Int) {
        ttsReady = false
        if (status == TextToSpeech.SUCCESS) {
            val languages = listOf(
                java.util.Locale("en", "GH"),
                java.util.Locale.UK,
                java.util.Locale.US
            )
            for (locale in languages) {
                val availability = tts.isLanguageAvailable(locale)
                if (availability >= TextToSpeech.LANG_AVAILABLE) {
                    tts.language = locale
                    tts.setSpeechRate(0.88f)
                    tts.setPitch(1.0f)
                    ttsReady = true
                    break
                }
            }
        }

        if (hasMicPermission()) {
            mode = Mode.WAKE
            if (ttsReady) {
                speak("Hello is ready. Say hello when you need me.", Mode.WAKE)
            } else {
                scheduleListening(200)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        stopRecognition()
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

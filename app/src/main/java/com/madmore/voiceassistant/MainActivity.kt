package com.madmore.voiceassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var parser: CommandParser
    private lateinit var contacts: ContactRepository
    private lateinit var aliases: AliasStore
    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private var ttsReady = false
    private var waitingForConfirmation: ContactCandidate? = null
    private val requestCode = 501

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parser = CommandParser(); contacts = ContactRepository(this); aliases = AliasStore(this)
        buildAccessibilityFirstUi()
        tts = TextToSpeech(this, this)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer.setRecognitionListener(listener)
        }
        if (!hasPermissions()) requestRequiredPermissions() else announceReady()
    }

    private fun buildAccessibilityFirstUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32); gravity = Gravity.CENTER_HORIZONTAL }
        statusView = TextView(this).apply { text = "READY TO LISTEN"; textSize = 28f; gravity = Gravity.CENTER; contentDescription = "Assistant status" }
        transcriptView = TextView(this).apply { text = "Say: Call Gyamera\nOr: Frɛ me ba"; textSize = 24f; setPadding(0, 28, 0, 28) }
        val listen = Button(this).apply { text = "LISTEN"; textSize = 30f; minHeight = 150; contentDescription = "Start voice listening"; setOnClickListener { listenNow() } }
        val repeat = Button(this).apply { text = "REPEAT"; textSize = 24f; minHeight = 100; setOnClickListener { speak("Say call followed by the person's name, or say Frɛ me ba to call your saved child.") } }
        val scroll = ScrollView(this).apply { addView(transcriptView) }
        root.addView(statusView, LinearLayout.LayoutParams(-1, 100)); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(listen, LinearLayout.LayoutParams(-1, 160)); root.addView(repeat, LinearLayout.LayoutParams(-1, 110))
        setContentView(root)
    }

    // Volume up is a screen-free listen trigger; volume down is an immediate cancel.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { listenNow(); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { if (::speechRecognizer.isInitialized) speechRecognizer.cancel(); waitingForConfirmation = null; speak("Stopped listening."); true }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun requestRequiredPermissions() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), requestCode)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode && hasPermissions()) announceReady() else speak("I need microphone, contacts, and phone permission to help you make calls.")
    }

    private fun announceReady() {
        speak("Hello. I am ready. Tell me who you would like to call.")
        statusView.postDelayed({ if (!isFinishing) listenNow() }, 3200)
    }

    private fun listenNow() {
        if (waitingForConfirmation != null) { speak("Please say yes to call ${waitingForConfirmation!!.name}, or say no to cancel."); return }
        if (!::speechRecognizer.isInitialized) { speak("Voice recognition is not available on this phone."); return }
        if (!hasPermissions()) { requestRequiredPermissions(); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GH")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-GH")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        statusView.text = "LISTENING…"; transcriptView.text = "Listening for English or Akan/Twi…"; speechRecognizer.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { statusView.text = "SPEAK NOW" }
        override fun onBeginningOfSpeech() { statusView.text = "HEARING YOU" }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { statusView.text = "THINKING…" }
        override fun onError(error: Int) {
            statusView.text = "READY TO LISTEN"
            speak(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "I did not understand. Please say it again."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The voice service is unavailable right now. Please try again."
                else -> "Please try again."
            })
        }
        override fun onResults(results: Bundle) {
            statusView.text = "READY TO LISTEN"
            val spoken = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            transcriptView.text = "You said: $spoken"; handleSpeech(spoken)
        }
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun handleSpeech(spoken: String) {
        if (spoken.isBlank()) return
        if (waitingForConfirmation != null) {
            val answer = parser.normalize(spoken); val candidate = waitingForConfirmation; waitingForConfirmation = null
            if (answer == "yes" || answer == "y") callContact(candidate) else speak("Okay, I will not call.")
            return
        }
        val normalized = parser.normalize(spoken)
        if (normalized.startsWith("remember ") && normalized.contains(" as ")) {
            val body = normalized.removePrefix("remember "); val person = body.substringBefore(" as ").trim(); val relationship = body.substringAfter(" as ").trim()
            val candidate = contacts.findCandidates(person, 1).firstOrNull()
            if (candidate != null && candidate.score >= 0.45) { aliases.saveAlias(relationship, candidate.name); speak("Okay. I will remember ${candidate.name} as $relationship.") }
            else speak("I could not find that person in your contacts.")
            return
        }
        val command = parser.parse(spoken)
        when (command.type) {
            CommandType.STOP -> { if (::speechRecognizer.isInitialized) speechRecognizer.cancel(); waitingForConfirmation = null; speak("Stopped. I am ready when you need me.") }
            CommandType.HELP -> speak("You can say call a person's name, say Frɛ me ba for a saved relationship, or say stop.")
            CommandType.LIST_CONTACTS -> { val names = contacts.allNames(); speak(if (names.isEmpty()) "There are no phone contacts available." else "Your contacts include ${names.joinToString(", ")}.") }
            CommandType.CALL -> resolveAndCall(command.target.orEmpty())
            CommandType.UNKNOWN -> speak("I can make calls. Try saying call Gyamera, or Frɛ me ba.")
        }
    }

    private fun resolveAndCall(target: String) {
        val query = aliases.resolveAlias(target) ?: target
        val candidates = contacts.findCandidates(query, 5)
        if (candidates.isEmpty()) { speak("I cannot find $target in your contacts. Say another name."); return }
        val top = candidates.first()
        if (top.score >= 0.82 && (candidates.size == 1 || top.score - candidates[1].score >= 0.12)) { speak("Calling ${top.name}."); callContact(top) }
        else { waitingForConfirmation = top; val alternatives = candidates.take(3).joinToString(", ") { it.name }; speak("I heard $target. Did you mean ${top.name}? Other matches are $alternatives. Say yes or no.") }
    }

    private fun callContact(candidate: ContactCandidate?) {
        if (candidate == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) { speak("Phone permission is not available."); requestRequiredPermissions(); return }
        try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${candidate.phone}"))) }
        catch (_: Exception) { Toast.makeText(this, "Unable to start the call", Toast.LENGTH_LONG).show(); speak("I could not start the call. Please try again.") }
    }

    private fun speak(message: String) {
        if (!::tts.isInitialized || !ttsReady) return
        statusView.post { statusView.text = "SPEAKING…" }
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "assistant-${System.currentTimeMillis()}")
        statusView.postDelayed({ statusView.text = "READY TO LISTEN" }, 2500)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val gh = Locale("en", "GH"); val result = tts.setLanguage(gh)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts.language = Locale.US
            tts.setSpeechRate(0.9f); tts.setPitch(1.0f)
        }
    }

    override fun onDestroy() {
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}

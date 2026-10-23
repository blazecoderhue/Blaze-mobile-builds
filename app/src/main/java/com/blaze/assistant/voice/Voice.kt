package com.blaze.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import com.blaze.assistant.voice.myvoice.MyVoice
import com.blaze.assistant.voice.myvoice.VoiceRoute
import com.blaze.assistant.voice.gemini.GeminiTts
import com.blaze.assistant.voice.gemini.GeminiVoice

/**
 * Speech in (Android's recognizer) and speech out. Main thread only.
 * v0.28.0: speech out goes through one route ([out]): the phone's TextToSpeech, or "My voice" (the user's own voice,
 * cloned on the phone by [MyVoice]) when they chose it. Every caller (voice replies, calls, Read aloud, proactive
 * notices) still just calls speak / speakPart / say, so all of Blaze's speech follows the choice.
 */
object Voice {
    enum class State { IDLE, LISTENING, SPEAKING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial
    /** Mic level 0..1 while listening, for the pulsing ring. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    /** Set by the app (MainActivity / assistant session) to ask the chat screen to start listening. */
    val listenRequests = MutableStateFlow(0)

    /** Call Blaze: a full-screen voice call. While on, replies are always spoken and the mic reopens after each one. */
    @Volatile var inCall = false
    @Volatile var callMuted = false
    /** Bumped to ask the chat screen to open the call screen (launcher shortcut, "call Blaze"). */
    val callRequests = MutableStateFlow(0)
    /** Bumped when the caller ends the call by voice ("bye", "that's all"). */
    val callEnded = MutableStateFlow(0)
    /** Call screen state, shared so Blaze can flip it by voice ("put me on loudspeaker", "mute"). */
    val callSpeaker = MutableStateFlow(true)
    val callMute = MutableStateFlow(false)
    fun shouldSpeak() = inCall || com.blaze.assistant.agent.Settings.speakReplies

    /** True when the current turn started by voice, so the reply is read aloud. */
    @Volatile var lastTurnByVoice = false

    private lateinit var app: Context
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** Something can speak: the phone's TTS, or My voice. */
    fun ttsOk() = ttsReady || MyVoice.route().first == VoiceRoute.Route.MINE || GeminiVoice.ready()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        MyVoice.init(app)
        MyVoice.events = object : MyVoice.Events {
            override fun started(id: String) = utteranceStarted()
            override fun done(id: String) = utteranceDone(id)
            override fun handoff(text: String, id: String) {
                if (!ttsReady) { utteranceDone(id); return }
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            }
        }
        GeminiVoice.events = object : GeminiVoice.Events {
            override fun started(id: String) = utteranceStarted()
            override fun done(id: String) = utteranceDone(id)
            override fun handoff(text: String, id: String) {
                if (!ttsReady) { utteranceDone(id); return }
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            }
            override fun tell(text: String) = tellGeminiOnce(text)
        }
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                applyPrefs()
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) = utteranceStarted()
                    override fun onDone(id: String?) = utteranceDone(id)
                    @Deprecated("old api") override fun onError(id: String?) { _state.value = State.IDLE; WakeWordService.resume(app) }
                })
            }
        }
    }

    private fun utteranceStarted() { _state.value = State.SPEAKING }

    /** Shared by the phone's TTS and My voice: what happens when an utterance finishes playing. */
    private fun utteranceDone(id: String?) {
        // A streamed sentence finished: the rest of the reply is still coming, so keep the mic paused.
        if (id?.startsWith("part-") == true) { if (!speakingNow()) _state.value = State.IDLE; return }
        _state.value = State.IDLE
        // Hands-free: after answering a spoken question, listen again for the follow-up.
        if (id?.startsWith("end-") == true && inCall) { callEnded.value = callEnded.value + 1; return }
        if (inCall && !callMuted && id?.startsWith("blaze-") == true) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (inCall && !callMuted) conversationListen() }, 250)
        } else if (com.blaze.assistant.agent.Settings.bool("voice_loop", true) && lastTurnByVoice && id?.startsWith("blaze-") == true) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ conversationListen() }, 200)
        } else WakeWordService.resume(app)
    }

    private fun speakingNow() = tts?.isSpeaking == true || MyVoice.busy() || GeminiVoice.busy()

    /** v0.41.0: why the Gemini voice isn't speaking, said once per app run. */
    @Volatile private var geminiTold = false
    private fun tellGeminiOnce(text: String) {
        if (geminiTold || text.isBlank()) return
        geminiTold = true
        try { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) { }
    }

    /**
     * The one place speech goes out (VoiceOut). My voice when chosen and ready; otherwise the phone's TTS, and when
     * My voice was chosen but can't speak (slow phone, model missing/failed) the user is told why, once.
     */
    private fun out(text: String, flush: Boolean, id: String): Boolean {
        // v0.41.0 Gemini voice: the user's Gemini key speaks; without one (or after a failure) the phone's voice does.
        if (GeminiVoice.chosen()) {
            if (GeminiVoice.ready()) {
                if (flush) { tts?.stop(); MyVoice.stop() }
                GeminiVoice.speak(text, id, flush)
                return true
            }
            tellGeminiOnce(if (!GeminiVoice.hasKey()) GeminiTts.NO_KEY + " Using the phone voice for now." else GeminiVoice.lastError.ifBlank { "The Google voice isn't working right now." } + " Using the phone voice for now.")
            if (!ttsReady) return false
            if (flush) GeminiVoice.stop()
            tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            return true
        }
        val (route, reason) = MyVoice.route()
        if (route == VoiceRoute.Route.MINE) {
            if (flush) { tts?.stop(); GeminiVoice.stop() }
            MyVoice.speak(text, id, flush)
            return true
        }
        if (MyVoice.chosen()) MyVoice.tellOnce(reason)
        if (!ttsReady) return false
        if (flush) { MyVoice.stop(); GeminiVoice.stop() }
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        return true
    }

    fun available() = SpeechRecognizer.isRecognitionAvailable(app)

    private val endWords = Regex("""^(thanks?( you)?|thank u|cheers|that'?s (all|it)|nothing|no( thanks)?|bye|goodbye|good ?night|i'?m (good|done)|done|ok(ay)? thanks?)[.! ]*$""", RegexOption.IGNORE_CASE)
    private val stopWords = Regex("""^(stop|cancel|never ?mind|abort|quiet|shut up|hold on)[.! ]*$""", RegexOption.IGNORE_CASE)
    private var silentTurns = 0

    /**
     * Conversation mode: after a spoken answer, listen again. Ends on silence (twice), or on
     * "thanks"/"that's all"; "stop"/"cancel" stops whatever Blaze is doing.
     */
    fun conversationListen() {
        com.blaze.assistant.agent.LlmClient.prewarm()   // keep the connection warm while we talk
        listen({ raw ->
            val t = raw.trim()
            when {
                t.isBlank() && inCall -> { if (!callMuted) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (inCall && !callMuted) conversationListen() }, 300) }
                endWords.matches(t) && inCall && com.blaze.assistant.agent.Settings.bool("call_bye", true) -> { lastTurnByVoice = false; speakEnd("Talk soon.") }
                t.isBlank() -> { silentTurns++; if (silentTurns < 2) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ conversationListen() }, 150) else silentTurns = 0 }
                stopWords.matches(t) -> { silentTurns = 0; com.blaze.assistant.agent.Agent.stopAll(); com.blaze.assistant.device.Sounds.done() }
                endWords.matches(t) -> { silentTurns = 0; lastTurnByVoice = false; speakEnd("Anytime.") }
                else -> { silentTurns = 0; com.blaze.assistant.agent.Agent.send(t, byVoice = true) }
            }
        }, { })
    }

    private fun speakEnd(text: String) {
        out(text, true, "end-${System.currentTimeMillis()}")
    }

    /** Short spoken status for a voice turn (acknowledgement, progress). Never interrupts speech. */
    fun say(text: String) {
        if (!ttsOk() || speakingNow() || _state.value == State.LISTENING) return
        WakeWordService.pause(app)
        out(text, false, "part-${System.currentTimeMillis()}")
    }

    /** Listen once. [onText] gets the final words (blank when nothing was heard). */
    fun listen(onText: (String) -> Unit, onError: (String) -> Unit) {
        stopSpeaking()
        cancelListening()
        if (!available()) { onError("Speech recognition isn't available on this phone."); return }
        MyVoice.prewarm()   // a reply is coming: have My voice loaded by then (no-op when it isn't in use)
        WakeWordService.pause(app)  // only one app can hold the mic
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = r
        _partial.value = ""
        _state.value = State.LISTENING
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { _level.value = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { _level.value = 0f }
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { _partial.value = it }
            }
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finish(); onText(text)
            }
            override fun onError(code: Int) {
                finish()
                if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) onText("")
                else onError(when (code) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Blaze needs microphone permission."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs a connection right now."
                    else -> "Couldn't hear that (code $code)."
                })
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, com.blaze.assistant.agent.Settings.str("stt_lang", "").ifBlank { Locale.getDefault().toLanguageTag() })
        r.startListening(i)
    }

    private fun finish() {
        _state.value = State.IDLE; _level.value = 0f
        recognizer?.destroy(); recognizer = null
        WakeWordService.resume(app)
    }

    /** v0.39.0 hold-to-talk: the finger came off, so stop recording and deliver what was said. */
    fun stopListening() { try { recognizer?.stopListening() } catch (_: Exception) { } }

    fun cancelListening() {
        recognizer?.let { it.cancel(); it.destroy() }
        recognizer = null
        if (_state.value == State.LISTENING) { _state.value = State.IDLE; WakeWordService.resume(app) }
    }

    fun speak(text: String, queue: Boolean = false) {
        if (!ttsOk()) return
        WakeWordService.pause(app)
        // v0.41.0: only the reply's words: no markdown symbols, links, code, or step / status lines.
        val clean = SpokenText.forSpeech(text).ifBlank { " " }
        out(clean, !queue, "blaze-${System.currentTimeMillis()}")
    }

    /** A sentence spoken while the reply is still streaming (queued; the final part follows via speak(queue = true)). */
    fun speakPart(text: String) {
        if (!ttsOk()) return
        WakeWordService.pause(app)
        val clean = SpokenText.forSpeech(text)
        if (clean.isBlank()) return
        out(clean, false, "part-${System.currentTimeMillis()}")
    }

    /** Phone-call style: earpiece instead of loudspeaker. */
    fun setEarpiece(on: Boolean) {
        try {
            val am = app.getSystemService(android.media.AudioManager::class.java)
            val usage = if (on) android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION else android.media.AudioAttributes.USAGE_ASSISTANT
            tts?.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(usage)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            MyVoice.setUsage(usage)
            GeminiVoice.setUsage(usage)
            am.mode = if (on) android.media.AudioManager.MODE_IN_COMMUNICATION else android.media.AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = !on
        } catch (_: Exception) { }
    }

    /** The recognizer beeps every time it reopens; hush system sounds for the call. */
    fun hushBeeps(on: Boolean) {
        try {
            val am = app.getSystemService(android.media.AudioManager::class.java)
            val dir = if (on) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE
            listOf(android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.STREAM_NOTIFICATION).forEach { am.adjustStreamVolume(it, dir, 0) }
        } catch (_: Exception) { }
    }

    /** Blaze's voice, speed and pitch from settings. */
    fun applyPrefs() {
        val t = tts ?: return
        if (!ttsReady) return
        val S = com.blaze.assistant.agent.Settings
        try {
            t.setSpeechRate(S.str("tts_rate", "1.0").toFloatOrNull() ?: 1f)
            t.setPitch(S.str("tts_pitch", "1.0").toFloatOrNull() ?: 1f)
            val name = S.str("tts_voice", "")
            if (name.isNotBlank()) t.voices?.firstOrNull { it.name == name }?.let { t.voice = it }
            else t.language = Locale.getDefault()
        } catch (_: Exception) { }
    }

    /** Voices the phone's speech engine has installed, the phone's language first. */
    fun voices(): List<android.speech.tts.Voice> = try {
        val lang = Locale.getDefault().language
        tts?.voices.orEmpty()
            .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy({ it.locale.language != lang }, { it.locale.displayName }, { it.isNetworkConnectionRequired }, { it.name }))
    } catch (_: Exception) { emptyList() }

    /** "Hear it" in Settings → Voice: always the phone's voice (that's what those settings tune). */
    fun preview(text: String = "Hi, this is how I sound.") {
        if (!ttsReady) return
        applyPrefs()
        MyVoice.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview-${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
        MyVoice.stop()
        GeminiVoice.stop()
        if (_state.value == State.SPEAKING) { _state.value = State.IDLE; WakeWordService.resume(app) }
    }
}

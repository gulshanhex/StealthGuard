package com.system.cacheclean.call

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.system.cacheclean.model.Gender
import com.system.cacheclean.storage.AudioResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CallStateManager
 *
 * The brain of the fake call. Drives the HOOK → LISTEN → RESPOND/FILLER loop.
 *
 * THREADING MODEL (important):
 *   • All state transitions run on the MAIN thread.
 *   • MediaPlayer and SpeechRecognizer both require the main thread.
 *   • Only the Room DB keyword lookup is dispatched to Dispatchers.IO,
 *     then immediately switches back to Main before any UI/audio action.
 *
 * GENDER CONTRACT:
 *   The `gender` parameter is locked at construction time from the session
 *   persona. Every single audio file resolved — hook, response, filler —
 *   comes exclusively from that gender's directory. No mixing ever occurs.
 *
 * USAGE:
 *   val manager = CallStateManager(context, audioResolver, Gender.LADY, lifecycleScope)
 *   manager.start()      // begins HOOK → LISTEN loop
 *   manager.release()    // call from panicCancel / onDestroy
 *
 * STT TIMEOUT LOGIC:
 *   A 3-second Handler watchdog is armed every time LISTEN is entered.
 *   If SpeechRecognizer returns results before the watchdog fires →
 *     watchdog is cancelled, keyword lookup runs.
 *   If watchdog fires first →
 *     STT is stopped, FILLER plays immediately, watchdog resets.
 *   If SpeechRecognizer.onError() fires →
 *     Same as watchdog — go to FILLER.
 *
 * AUDIO FOCUS:
 *   Requests AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK on call start so system
 *   sounds and other media don't bleed into the fake call audio.
 */
class CallStateManager(
    private val context: Context,
    private val audioResolver: AudioResolver,
    private val gender: Gender,
    private val scope: CoroutineScope,
    private val onStateChange: (CallState) -> Unit = {}
) {

    companion object {
        private const val TAG            = "StealthGuard_CSM"
        private const val STT_TIMEOUT_MS = 3_000L
    }

    // ─── State ────────────────────────────────────────────────────────────────

    @Volatile
    private var currentState = CallState.IDLE

    // N-13: guards release() against double-invocation races (e.g. panicCancel()
    // on main thread overlapping with an STT onError() callback).
    private val isReleased = AtomicBoolean(false)

    // ─── Audio ────────────────────────────────────────────────────────────────

    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusGranted = false

    // ─── STT ─────────────────────────────────────────────────────────────────

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fired when SpeechRecognizer doesn't return results within STT_TIMEOUT_MS.
     * Treated identically to onError() — move to FILLER state.
     */
    private val sttTimeoutRunnable = Runnable {
        Log.d(TAG, "STT timeout (${STT_TIMEOUT_MS}ms) — going to FILLER")
        stopSTT()
        transitionToFiller()
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Begins the call engine. Must be called from the main thread.
     * Safe to call only once — subsequent calls are ignored while active.
     */
    fun start() {
        if (currentState != CallState.IDLE) {
            Log.w(TAG, "start() called in state $currentState — ignored")
            return
        }
        requestAudioFocus()
        initSpeechRecognizer()
        transitionToHook()
    }

    /**
     * Immediately stops all audio, STT, and destroys all resources.
     * Call from panicCancel(), onDestroy(), decline button.
     * Safe to call from any state including ENDED.
     */
    fun release() {
        if (!isReleased.compareAndSet(false, true)) return
        Log.i(TAG, "CallStateManager released from state: $currentState")
        currentState = CallState.ENDED
        onStateChange(CallState.ENDED)
        mainHandler.removeCallbacks(sttTimeoutRunnable)
        stopSTT()
        stopAudio()
        abandonAudioFocus()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ─── State Transitions ────────────────────────────────────────────────────

    private fun transitionToHook() {
        if (currentState == CallState.ENDED) return
        currentState = CallState.HOOK
        onStateChange(CallState.HOOK)
        Log.d(TAG, "STATE → HOOK")

        val hookFile = audioResolver.resolveHook(gender)
        if (hookFile == null) {
            Log.w(TAG, "No hook audio available — skipping directly to LISTEN")
            transitionToListen()
            return
        }
        playAudio(hookFile) {
            // onComplete — runs on main thread via MediaPlayer.OnCompletionListener
            if (currentState != CallState.ENDED) transitionToListen()
        }
    }

    private fun transitionToListen() {
        if (currentState == CallState.ENDED) return
        currentState = CallState.LISTEN
        onStateChange(CallState.LISTEN)
        Log.d(TAG, "STATE → LISTEN (timeout in ${STT_TIMEOUT_MS}ms)")
        startSTT()
    }

    private fun transitionToRespond(file: File) {
        if (currentState == CallState.ENDED) return
        currentState = CallState.RESPOND
        onStateChange(CallState.RESPOND)
        Log.d(TAG, "STATE → RESPOND: ${file.name}")
        playAudio(file) {
            if (currentState != CallState.ENDED) transitionToListen()
        }
    }

    private fun transitionToFiller() {
        if (currentState == CallState.ENDED) return
        currentState = CallState.FILLER
        onStateChange(CallState.FILLER)
        Log.d(TAG, "STATE → FILLER")

        val fillerFile = audioResolver.resolveRandomFiller(gender)
        if (fillerFile == null) {
            Log.w(TAG, "No filler audio available — looping back to LISTEN")
            // Wait 1.5s before looping to prevent CPU-spinning if user hasn't
            // recorded any fillers yet
            mainHandler.postDelayed({ transitionToListen() }, 1_500L)
            return
        }
        playAudio(fillerFile) {
            if (currentState != CallState.ENDED) transitionToListen()
        }
    }

    // ─── Audio Playback ───────────────────────────────────────────────────────

    /**
     * Plays a single audio file. Stops any currently playing audio first.
     * [onComplete] is guaranteed to fire on the main thread.
     */
    private fun playAudio(file: File, onComplete: () -> Unit) {
        stopAudio()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    start()
                    Log.d(TAG, "Playing: ${file.name} (${file.length() / 1024}KB)")
                }
                setOnCompletionListener {
                    Log.d(TAG, "Playback complete: ${file.name}")
                    onComplete()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra — skipping to next state")
                    onComplete()
                    true
                }
                // A-12: async prepare — sync prepare() can block the main thread
                // for hundreds of ms on a corrupted/truncated file.
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ${file.name}: ${e.message}")
            // Always advance state on failure — call must never freeze
            mainHandler.post { onComplete() }
        }
    }

    private fun stopAudio() {
        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) { /* safe to ignore during teardown */ }
        mediaPlayer = null
    }

    // ─── Speech Recognition ───────────────────────────────────────────────────

    /**
     * Initialises SpeechRecognizer. Must run on the main thread.
     * If recognition is unavailable (no offline pack installed), sets
     * speechRecognizer to null — the timeout watchdog then handles all
     * state advancement via FILLER playback, so the call still works.
     */
    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer unavailable — running in filler-only mode")
            speechRecognizer = null
            return
        }
        speechRecognizer = SpeechRecognizer
            .createSpeechRecognizer(context)
            .apply { setRecognitionListener(recognitionListener) }
        Log.d(TAG, "SpeechRecognizer initialised (offline preferred)")
    }

    /**
     * Starts a single STT listening pass and arms the timeout watchdog.
     * Must run on the main thread (SpeechRecognizer requirement).
     */
    private fun startSTT() {
        // Arm the timeout watchdog regardless of whether STT is available.
        // If STT is null, the watchdog IS the only advancement mechanism.
        mainHandler.postDelayed(sttTimeoutRunnable, STT_TIMEOUT_MS)

        val recognizer = speechRecognizer ?: return  // Filler-only mode

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // KEY FLAG: force offline recognition. Will fall back to online
            // if user hasn't installed the offline pack — still works, just
            // requires data. User is expected to pre-install the offline pack.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Request up to 5 alternative transcriptions — increases chances
            // of a keyword match even if the top result is wrong.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Disable partial results (we only act on final results)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening() threw: ${e.message}")
            mainHandler.removeCallbacks(sttTimeoutRunnable)
            transitionToFiller()
        }
    }

    private fun stopSTT() {
        mainHandler.removeCallbacks(sttTimeoutRunnable)
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
    }

    // ─── RecognitionListener ──────────────────────────────────────────────────

    private val recognitionListener = object : RecognitionListener {

        /**
         * Final STT results received. Cancel the timeout watchdog.
         * Search ALL returned alternatives for any keyword match.
         * DB lookup dispatched to IO thread; audio play back to Main.
         */
        override fun onResults(results: Bundle) {
            mainHandler.removeCallbacks(sttTimeoutRunnable)
            if (currentState == CallState.ENDED) return

            val matches = results
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()

            Log.d(TAG, "STT results [${matches.size}]: $matches")

            if (matches.isEmpty()) {
                transitionToFiller()
                return
            }

            // Dispatch DB lookup to IO thread — Room sync query not allowed on main
            scope.launch(Dispatchers.IO) {
                if (currentState == CallState.ENDED) return@launch
                val matched = findFirstKeywordMatch(matches)
                withContext(Dispatchers.Main) {
                    if (currentState == CallState.ENDED) return@withContext
                    if (matched != null) {
                        Log.d(TAG, "Keyword matched → ${matched.name}")
                        transitionToRespond(matched)
                    } else {
                        Log.d(TAG, "No keyword matched — going to FILLER")
                        transitionToFiller()
                    }
                }
            }
        }

        /**
         * STT error. Treat identically to timeout — play filler and continue.
         * Common error codes:
         *   ERROR_NO_MATCH (7)        — heard speech but no transcription
         *   ERROR_SPEECH_TIMEOUT (6)  — no speech detected
         *   ERROR_RECOGNIZER_BUSY (8) — previous session not fully closed
         */
        override fun onError(error: Int) {
            mainHandler.removeCallbacks(sttTimeoutRunnable)
            Log.w(TAG, "STT error code: $error — going to FILLER")
            stopSTT()
            if (currentState != CallState.ENDED) transitionToFiller()
        }

        // ── Unused callbacks — required by interface ──────────────────────────
        override fun onReadyForSpeech(params: Bundle)  { Log.d(TAG, "STT ready") }
        override fun onBeginningOfSpeech()             { Log.d(TAG, "STT speech detected") }
        override fun onEndOfSpeech()                   { Log.d(TAG, "STT end of speech") }
        override fun onPartialResults(partial: Bundle) { /* ignored */ }
        override fun onRmsChanged(rmsdB: Float)        { /* ignored */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* ignored */ }
        override fun onEvent(type: Int, params: Bundle?) { /* ignored */ }
    }

    /**
     * Iterates over all STT result alternatives and all words within each
     * phrase, returning the first audio file match found.
     *
     * Word-level iteration means "let's go to the market" will match "market"
     * even though the full phrase isn't a keyword.
     *
     * Runs on Dispatchers.IO — AudioResolver.resolveResponse() calls a
     * synchronous Room query which must NOT run on the main thread.
     */
    private fun findFirstKeywordMatch(phrases: List<String>): File? {
        for (phrase in phrases) {
            val words = phrase.trim().lowercase().split("\\s+".toRegex())
            for (word in words) {
                if (word.length < 2) continue  // Skip single-char noise words
                val file = audioResolver.resolveResponse(gender, word)
                if (file != null) return file
            }
        }
        return null
    }

    // ─── Audio Focus ──────────────────────────────────────────────────────────

    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * Requests transient audio focus so the fake call audio is heard clearly
     * without system sounds or media apps bleeding in.
     *
     * L-07: AudioManager.requestAudioFocus(listener, stream, durationHint) was
     * deprecated in API 26 in favor of AudioFocusRequest. Both paths kept for
     * minSdk 24 compatibility.
     */
    private fun requestAudioFocus() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build()
            audioFocusRequest = request
            val result = audioManager?.requestAudioFocus(request)
            audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
        Log.d(TAG, "Audio focus granted: $audioFocusGranted")
    }

    private fun abandonAudioFocus() {
        if (!audioFocusGranted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
        audioFocusGranted = false
    }
}

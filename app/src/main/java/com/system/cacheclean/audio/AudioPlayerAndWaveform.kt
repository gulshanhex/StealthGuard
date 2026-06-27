package com.system.cacheclean.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.io.File
import kotlin.random.Random

// ═════════════════════════════════════════════════════════════════════════════
// AudioPlayer
// ═════════════════════════════════════════════════════════════════════════════

/**
 * AudioPlayer
 *
 * Thin wrapper around MediaPlayer for in-admin audio preview.
 * NOT used during live calls — CallStateManager has its own MediaPlayer.
 *
 * USAGE:
 *   val player = AudioPlayer()
 *   player.play(file) { /* on completion */ }
 *   player.stop()
 *   player.release()   // in onDestroy()
 */
class AudioPlayer {

    companion object {
        private const val TAG = "StealthGuard_Player"
    }

    private var mediaPlayer: MediaPlayer? = null

    /** true if audio is currently playing */
    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    /**
     * Plays the given file. Stops any currently playing audio first.
     *
     * @param file       The .m4a file to play.
     * @param onComplete Called on the main thread when playback finishes naturally.
     * @return true if playback started successfully.
     */
    fun play(file: File, onComplete: () -> Unit = {}): Boolean {
        stop()
        if (!file.exists()) {
            Log.w(TAG, "File not found: ${file.absolutePath}")
            return false
        }
        return try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { onComplete() }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    stop()
                    true
                }
                prepare()       // Synchronous prepare is fine for local files
                start()
            }
            Log.d(TAG, "Playing: ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed: ${e.message}")
            release()
            false
        }
    }

    /** Stops playback immediately. Safe to call even when not playing. */
    fun stop() {
        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
        } catch (_: Exception) { }
        cleanup()
    }

    /** Releases MediaPlayer resources. Call from onDestroy to prevent leaks. */
    fun release() {
        cleanup()
    }

    // N-12: single shared teardown — stop() and release() both end up here
    // instead of stop() calling the public release() (confusing call chain
    // for a reader trying to trace what "stop" actually does).
    private fun cleanup() {
        try { mediaPlayer?.release() } catch (_: Exception) { }
        mediaPlayer = null
    }
}


// ═════════════════════════════════════════════════════════════════════════════
// WaveformView
// ═════════════════════════════════════════════════════════════════════════════

/**
 * WaveformView
 *
 * A custom View that renders an animated bar-waveform during audio recording.
 * Driven by two modes:
 *
 *   REAL MODE:   Feed actual amplitude from AudioRecorder.getAmplitude()
 *                via updateAmplitude(). The bars reflect real microphone input.
 *
 *   IDLE MODE:   When stopped, all bars collapse to a flat line.
 *
 * The view renders 24 vertical bars. Each frame, bar heights are smoothly
 * interpolated toward their target to prevent jarring jumps.
 *
 * XML usage:
 *   <com.system.cacheclean.audio.WaveformView
 *       android:id="@+id/waveformView"
 *       android:layout_width="match_parent"
 *       android:layout_height="64dp" />
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT     = 24
        private const val UPDATE_INTERVAL_MS = 80L
        private const val SMOOTHING     = 0.4f   // 0=instant snap, 1=never moves
    }

    // ─── Paint ────────────────────────────────────────────────────────────────

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#1565C0")
        strokeCap   = Paint.Cap.ROUND
        style       = Paint.Style.STROKE
    }

    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#BDBDBD")
        strokeCap   = Paint.Cap.ROUND
        style       = Paint.Style.STROKE
    }

    // ─── State ────────────────────────────────────────────────────────────────

    /** Current rendered heights (0f–1f, fraction of view height). */
    private val currentHeights = FloatArray(BAR_COUNT) { 0.05f }

    /** Target heights we're interpolating toward. */
    private val targetHeights  = FloatArray(BAR_COUNT) { 0.05f }

    private var isAnimating = false
    private var amplitudeSource: (() -> Int)? = null   // Injected amplitude provider

    // A-18: fixed per-bar shape (computed once, not re-randomised every frame).
    private val barShape = FloatArray(BAR_COUNT) { Random.nextFloat() }

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isAnimating) return
            refreshBars()
            invalidate()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Start animating the waveform.
     * @param amplitudeProvider Lambda returning current microphone amplitude (0–32767).
     *                          Pass AudioRecorder::getAmplitude.
     */
    fun startAnimating(amplitudeProvider: () -> Int) {
        amplitudeSource = amplitudeProvider
        isAnimating = true
        handler.post(updateRunnable)
    }

    /** Stop animation and collapse bars to flat line. */
    fun stopAnimating() {
        isAnimating = false
        amplitudeSource = null
        handler.removeCallbacks(updateRunnable)
        targetHeights.fill(0.05f)
        currentHeights.fill(0.05f)
        invalidate()
    }

    // ─── Bar Update Logic ─────────────────────────────────────────────────────

    private fun refreshBars() {
        val rawAmplitude = amplitudeSource?.invoke() ?: 0
        // Normalise amplitude (0–32767) to 0.0–1.0
        val normalised = (rawAmplitude / 32_767f).coerceIn(0f, 1f)

        // A-18: each bar gets a small *fixed* offset based on its own index
        // (a static "shape" across the 24 bars) instead of a brand-new random
        // jitter every single frame. The old per-frame-per-bar Random call
        // made the whole waveform look like noise rather than something
        // following the mic — this keeps a natural uneven look while still
        // visibly rising and falling with `normalised`.
        for (i in 0 until BAR_COUNT) {
            val shapeOffset = barShape[i] * 0.25f - 0.125f  // fixed ±12.5%
            targetHeights[i] = (normalised + shapeOffset).coerceIn(0.05f, 0.95f)
            // Smooth interpolation toward target
            currentHeights[i] += (targetHeights[i] - currentHeights[i]) * (1f - SMOOTHING)
        }
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val barWidth  = width.toFloat() / BAR_COUNT
        val centerY   = height / 2f
        val paint     = if (isAnimating) activePaint else idlePaint
        paint.strokeWidth = (barWidth * 0.55f).coerceAtLeast(2f)

        for (i in 0 until BAR_COUNT) {
            val x         = i * barWidth + barWidth / 2f
            val barHeight = height * currentHeights[i]
            canvas.drawLine(x, centerY - barHeight / 2f,
                            x, centerY + barHeight / 2f, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimating()
    }
}

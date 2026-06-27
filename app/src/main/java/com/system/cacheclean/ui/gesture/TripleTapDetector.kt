package com.system.cacheclean.ui.gesture

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent

/**
 * TripleTapDetector
 *
 * Custom gesture detector for the invisible corner backup trigger.
 * Detects two distinct gestures on the same transparent view:
 *
 *   GESTURE 1 — Triple Tap (3 taps, all released within 600ms window):
 *     → Triggers Fake Call (same as 2× Volume UP + hold)
 *
 *   GESTURE 2 — Triple Tap + Hold (3rd tap held for 2000ms):
 *     → Triggers SOS (same as 3× Volume UP + hold)
 *
 * DESIGN DECISIONS:
 *   - Uses raw MotionEvent counts instead of GestureDetector because the
 *     built-in detector only supports double-tap and has no triple-tap event.
 *   - The detection window resets if taps are too slow (> TAP_WINDOW_MS apart).
 *   - The hold timer is armed ONLY after the 3rd tap is confirmed.
 *   - If the 3rd finger lifts before hold threshold → Fake Call fires.
 *   - If hold threshold fires before 3rd finger lifts → SOS fires.
 *   - After any gesture fires, the detector resets to prevent double-firing.
 *
 * USAGE:
 *   val detector = TripleTapDetector(
 *       onTripleTap  = { launchFakeCall() },
 *       onHold       = { launchSOS() }
 *   )
 *   invisibleView.setOnTouchListener { _, event ->
 *       detector.onTouchEvent(event)
 *   }
 */
class TripleTapDetector(
    private val onTripleTap: () -> Unit,
    private val onTripleTapAndHold: () -> Unit
) {

    companion object {
        /** All 3 taps must start within this window. */
        private const val TAP_WINDOW_MS      = 600L

        /**
         * How long the 3rd tap must be held to trigger SOS instead of Call.
         * Intentionally longer than the volume-button hold (800ms, see
         * StealthAccessibilityService) — the corner tap overlaps normal
         * screen-touch areas, so a longer hold avoids accidental SOS triggers
         * from ordinary taps (N-07).
         */
        private const val HOLD_THRESHOLD_MS  = 2_000L
    }

    private var tapCount      = 0
    private var firstTapTime  = 0L
    private var holdFired     = false

    private val handler = Handler(Looper.getMainLooper())
    private var pendingHold: Runnable? = null

    /**
     * Feed every MotionEvent from your view's OnTouchListener here.
     * @return true to consume the event (prevent other listeners seeing it).
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown()
            MotionEvent.ACTION_UP   -> handleUp()
            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                reset()
                false
            }
            else -> tapCount == 3   // Consume all events once in hold state
        }
    }

    // ─── Event Handlers ───────────────────────────────────────────────────────

    private fun handleDown(): Boolean {
        val now = System.currentTimeMillis()

        // If the gap since the first tap exceeds the window, start fresh.
        if (tapCount > 0 && (now - firstTapTime) > TAP_WINDOW_MS) {
            cancelHold()
            reset()
        }

        // Record first tap time on the first press.
        if (tapCount == 0) firstTapTime = now

        tapCount++

        if (tapCount == 3) {
            // Third tap landed. Arm the hold timer.
            holdFired = false
            pendingHold = Runnable {
                // Hold threshold reached — fire SOS.
                holdFired = true
                onTripleTapAndHold()
                reset()
            }
            handler.postDelayed(pendingHold!!, HOLD_THRESHOLD_MS)
        }

        // Consume the event once we've registered at least one tap in sequence.
        return tapCount in 1..3
    }

    private fun handleUp(): Boolean {
        if (tapCount == 3 && !holdFired) {
            // 3rd finger lifted before hold threshold → Fake Call.
            cancelHold()
            onTripleTap()
            reset()
            return true
        }
        return false
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun cancelHold() {
        pendingHold?.let { handler.removeCallbacks(it) }
        pendingHold = null
    }

    private fun reset() {
        tapCount     = 0
        firstTapTime = 0L
        holdFired    = false
    }
}

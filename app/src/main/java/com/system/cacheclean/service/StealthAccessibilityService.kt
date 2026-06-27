package com.system.cacheclean.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.system.cacheclean.sos.SOSManager
import com.system.cacheclean.ui.FakeCallActivity

/**
 * StealthAccessibilityService
 *
 * THE TRIGGER ENGINE — updated per user requirement.
 *
 * OLD LOGIC (rejected):
 *   4× Volume UP  → Fake Call
 *   5× Volume DOWN → SOS
 *   Problem: too many presses, looks suspicious, accidental triggers
 *            on normal volume adjustments.
 *
 * NEW LOGIC:
 *   2× Volume UP quickly → then HOLD (800ms) → Fake Call
 *   3× Volume UP quickly → then HOLD (800ms) → SOS
 *
 * HOW IT WORKS:
 *   Press 1 (DOWN) : passes through normally → volume goes up 1 step.
 *                    This is intentional — looks completely natural.
 *
 *   Press 2 (DOWN) : consumed (volume doesn't change).
 *                    Hold timer armed: if button stays held for
 *                    HOLD_THRESHOLD_MS → Fake Call fires.
 *
 *   Press 3 (DOWN) : if pressed BEFORE hold timer fires → cancels
 *                    Fake Call timer, arms SOS hold timer instead.
 *                    If button held for HOLD_THRESHOLD_MS → SOS fires.
 *
 *   Any UP before HOLD_THRESHOLD_MS elapses → timer cancelled.
 *                    No trigger. User just gets a slightly shorter
 *                    hold than needed — must try again.
 *
 * PRESS WINDOW:
 *   All presses must happen within PRESS_WINDOW_MS (1500ms).
 *   After the window expires, the counter resets.
 *   This prevents accidental accumulation from unrelated volume presses
 *   that happen minutes apart.
 *
 * EXAMPLE SEQUENCES:
 *   [UP↓] [UP↓ hold 900ms]                     → FAKE CALL ✓
 *   [UP↓] [UP↑] [UP↓] [UP↓ hold 900ms]         → FAKE CALL ✓
 *   [UP↓] [UP↓] [UP↓ hold 900ms]               → SOS ✓
 *   [UP↓] [UP↓ hold 400ms] [UP↑]               → Nothing (released too soon)
 *   [UP↓] [UP↑] [wait 2s] [UP↓ hold 900ms]     → Nothing (window expired)
 *   [UP↓] [UP↑] [UP↓] [UP↑]                    → Normal volume adjust (+2)
 */
class StealthAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG               = "StealthGuard_AS"

        /** All presses must occur within this window. */
        private const val PRESS_WINDOW_MS   = 1_500L

        /**
         * How long the Nth button must be HELD to confirm the trigger.
         * Short enough to feel responsive, long enough to not false-fire.
         */
        private const val HOLD_THRESHOLD_MS = 800L

        /** Post-trigger cooldown — prevents double-firing. */
        private const val COOLDOWN_MS       = 3_000L

        private const val PRESSES_FAKE_CALL = 2
        private const val PRESSES_SOS       = 3
    }

    // ─── State ────────────────────────────────────────────────────────────────

    /** Timestamps of recent Volume UP ACTION_DOWN events. */
    private val pressTimestamps = ArrayDeque<Long>()

    /** Timestamp of the last ACTION_DOWN — used to measure hold duration. */
    private var lastDownTime = 0L

    /** Timestamp when the last trigger fired — for cooldown enforcement. */
    private var lastTriggerTime = 0L

    /**
     * Which trigger is currently armed (waiting for hold to complete).
     * null = no hold in progress.
     */
    private var pendingTrigger: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Runnable that fires when the hold threshold is reached.
     * Cancelled by ACTION_UP if the user releases the button too soon.
     */
    private val holdTriggerRunnable = Runnable {
        val trigger = pendingTrigger ?: return@Runnable
        pendingTrigger = null
        pressTimestamps.clear()
        lastTriggerTime = System.currentTimeMillis()
        trigger()
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected.")
    }

    /**
     * A-06: if the user (or OEM battery manager) disables the accessibility
     * service while a hold-trigger is armed, the postDelayed Runnable was
     * still able to fire afterward and call startActivity/startService from
     * a service that's mid-teardown — crash risk. Clear everything here.
     */
    override fun onServiceDisconnected() {
        super.onServiceDisconnected()
        Log.w(TAG, "Accessibility service disconnected — clearing pending trigger.")
        cancelHoldTimer()
        pendingTrigger = null
        pressTimestamps.clear()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { Log.w(TAG, "Service interrupted.") }

    // ─── Key Event Interception ───────────────────────────────────────────────

    /**
     * Returns FALSE → event passes through (volume adjusts normally).
     * Returns TRUE  → event consumed (volume does NOT change).
     *
     * CONSUMPTION POLICY:
     *   Press 1 ACTION_DOWN  : false  (let volume go up — looks natural)
     *   Press 2+ ACTION_DOWN : true   (consumed — hold detection phase)
     *   ACTION_UP during hold: false  (but cancels pending hold timer)
     *   During cooldown      : true   (consume all presses silently)
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false

        val now = System.currentTimeMillis()

        // Cooldown guard
        if (now - lastTriggerTime < COOLDOWN_MS) {
            return true   // Silently consume during cooldown
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown(now)
            KeyEvent.ACTION_UP   -> handleUp(now)
            else                 -> false
        }
    }

    // ─── ACTION_DOWN ──────────────────────────────────────────────────────────

    private fun handleDown(now: Long): Boolean {
        // Prune presses outside the window
        while (pressTimestamps.isNotEmpty() &&
               (now - pressTimestamps.first()) > PRESS_WINDOW_MS) {
            pressTimestamps.removeFirst()
        }

        // A-20: if the device clock jumps backward (manual change, NTP
        // correction) the prune-by-age loop above can't remove anything
        // (now - first goes negative). The else-branch reset below already
        // bounds this to ~4 in normal operation, but this is a cheap
        // explicit safety net against any future change to that logic.
        if (pressTimestamps.size > 10) pressTimestamps.clear()

        pressTimestamps.addLast(now)
        lastDownTime = now

        val count = pressTimestamps.size

        Log.d(TAG, "Vol UP down — press #$count in window")

        return when (count) {
            1 -> {
                // First press — pass through so volume adjusts naturally.
                // To a bystander this just looks like normal volume use.
                false
            }

            PRESSES_FAKE_CALL -> {
                // Second press — arm Fake Call hold timer.
                // Cancel any prior timer first (shouldn't exist, but be safe).
                cancelHoldTimer()
                pendingTrigger = ::launchFakeCall
                mainHandler.postDelayed(holdTriggerRunnable, HOLD_THRESHOLD_MS)
                Log.d(TAG, "Hold armed: FAKE CALL (hold ${HOLD_THRESHOLD_MS}ms to confirm)")
                true   // Consume — volume does not change on this press
            }

            PRESSES_SOS -> {
                // Third press — cancel the 2-press Fake Call timer and
                // arm SOS timer instead. User pressed a 3rd time, so they
                // want SOS not Fake Call.
                cancelHoldTimer()
                pendingTrigger = ::launchSOS
                mainHandler.postDelayed(holdTriggerRunnable, HOLD_THRESHOLD_MS)
                Log.d(TAG, "Hold armed: SOS (hold ${HOLD_THRESHOLD_MS}ms to confirm)")
                true   // Consume
            }

            else -> {
                // 4th+ press — cancel everything and reset.
                // Avoids runaway accumulation.
                cancelHoldTimer()
                pendingTrigger = null
                pressTimestamps.clear()
                Log.d(TAG, "Reset — too many presses without hold.")
                true
            }
        }
    }

    // ─── ACTION_UP ────────────────────────────────────────────────────────────

    private fun handleUp(now: Long): Boolean {
        val heldMs = now - lastDownTime

        if (pendingTrigger != null) {
            if (heldMs < HOLD_THRESHOLD_MS) {
                // Released too soon — cancel the pending trigger.
                cancelHoldTimer()
                pendingTrigger = null
                Log.d(TAG, "Hold cancelled (held ${heldMs}ms < ${HOLD_THRESHOLD_MS}ms threshold)")
                // Don't clear pressTimestamps — allow building toward 3-press SOS
                // from an existing 2-press state.
            }
            // If heldMs >= HOLD_THRESHOLD_MS, the Runnable already fired before
            // this UP arrived. pendingTrigger was cleared in the Runnable. Nothing to do.
        }

        return false   // Always pass UP through (can't un-change volume anyway)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun cancelHoldTimer() {
        mainHandler.removeCallbacks(holdTriggerRunnable)
    }

    // ─── Trigger Actions ──────────────────────────────────────────────────────

    private fun launchFakeCall() {
        Log.i(TAG, "TRIGGER: Fake Call (2× press + hold)")
        val intent = Intent(this, FakeCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun launchSOS() {
        Log.i(TAG, "TRIGGER: SOS (3× press + hold)")
        val intent = Intent(this, SOSManager::class.java).apply {
            action = SOSManager.ACTION_SEND_SOS
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

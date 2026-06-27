package com.system.cacheclean.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.system.cacheclean.R
import com.system.cacheclean.databinding.ActivityMainBinding
import com.system.cacheclean.security.PinManager
import com.system.cacheclean.sos.SOSManager
import com.system.cacheclean.ui.gesture.TripleTapDetector

/**
 * MainActivity — "System Cache Manager"
 *
 * This is the ONLY face of the app visible to a casual observer.
 * It must look like a boring, pre-installed Android system utility.
 *
 * TWO HIDDEN MECHANISMS:
 *
 *   1. Long-press (700ms) on the "Clear Cache" button
 *      → Opens PIN dialog → Correct PIN → AdminActivity
 *
 *   2. Triple-tap on an INVISIBLE 80×80dp corner zone (bottom-right)
 *      → Triple Tap          → FakeCallActivity
 *      → Triple Tap + 2s hold → SOSManager
 *
 * FAKE UI BEHAVIOR (what a bystander sees):
 *   - App opens showing storage stats (hardcoded but realistic-looking).
 *   - "Scan" button animates for 2 seconds, shows "cache found" result.
 *   - "Clear Cache" button shows a Toast and resets the stats display.
 *   - Nothing else happens. No hints of hidden functionality.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pinManager: PinManager
    private lateinit var cornerTrigger: TripleTapDetector

    private var isScanning = false

    // A-09: was a local Handler/Runnable inside setupHiddenGateway() with no
    // way to cancel from onDestroy() — hoisted to class fields.
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    companion object {
        private const val LONG_PRESS_DURATION = 700L     // ms to trigger PIN dialog
        private const val SCAN_DURATION       = 2_200L   // ms fake scan animation
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pinManager = PinManager(this)

        // First-ever launch: force the user to SET a PIN before anything else.
        if (!pinManager.isPinSet()) {
            showSetPinDialog()
        }

        setupFakeUI()
        setupHiddenGateway()
        setupCornerTrigger()
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
    }

    // ─── Fake UI Setup ────────────────────────────────────────────────────────

    /**
     * Initialises the realistic-looking cache cleaner interface.
     * All numbers are hardcoded but change slightly after "clearing" to
     * make the app feel functional to a casual observer.
     */
    private fun setupFakeUI() {
        // Fake storage stats — realistic for a mid-range Android device
        resetToDefaultStats()

        // "Scan" button — runs a fake 2-second analysis animation
        binding.btnScan.setOnClickListener {
            if (!isScanning) runFakeScan()
        }

        // "Clear Cache" button — shows toast, resets display, hides real purpose
        // The long-press on THIS button is the admin gateway (see below).
        binding.btnClearCache.setOnClickListener {
            runFakeClear()
        }
    }

    private fun resetToDefaultStats() {
        binding.tvStorageTotal.text    = "Storage: 64.0 GB"
        binding.tvStorageUsed.text     = "Used: 47.3 GB"
        binding.tvCacheSize.text       = "Cached Data: 2.31 GB"
        binding.tvJunkSize.text        = "Junk Files: 847 MB"
        binding.progressStorage.progress = 74    // 47.3 / 64 ≈ 74%
        binding.tvStatus.text          = "Tap Scan to analyse storage"
        binding.btnClearCache.isEnabled = false
        binding.btnClearCache.alpha    = 0.5f
    }

    private fun runFakeScan() {
        isScanning = true
        binding.tvStatus.text       = "Scanning system storage…"
        binding.progressScan.visibility = View.VISIBLE
        binding.btnScan.isEnabled   = false

        Handler(Looper.getMainLooper()).postDelayed({
            // After "scan" completes, show results
            binding.progressScan.visibility = View.GONE
            binding.tvStatus.text       = "Scan complete. 3.16 GB can be freed."
            binding.tvCacheSize.text    = "Cached Data: 2.31 GB  ⚠"
            binding.tvJunkSize.text     = "Junk Files: 854 MB  ⚠"
            binding.btnClearCache.isEnabled = true
            binding.btnClearCache.alpha = 1.0f
            binding.btnScan.isEnabled   = true
            isScanning = false
        }, SCAN_DURATION)
    }

    private fun runFakeClear() {
        binding.tvStatus.text       = "Clearing…"
        binding.btnClearCache.isEnabled = false

        Handler(Looper.getMainLooper()).postDelayed({
            binding.tvCacheSize.text    = "Cached Data: 0 B"
            binding.tvJunkSize.text     = "Junk Files: 0 B"
            binding.tvStorageUsed.text  = "Used: 44.1 GB"
            binding.progressStorage.progress = 69
            binding.tvStatus.text       = "Done. 3.16 GB freed successfully."
            binding.btnClearCache.alpha = 0.5f

            Toast.makeText(this, "Cache Cleared. 3.16 GB freed.", Toast.LENGTH_SHORT).show()
        }, 800L)
    }

    // ─── Hidden Admin Gateway ─────────────────────────────────────────────────

    /**
     * Long-press on the Clear Cache button (700ms) opens the PIN dialog.
     * This gesture is invisible — no ripple change, no hint in the UI.
     *
     * We use a manual Handler approach instead of setOnLongClickListener
     * because the built-in listener only fires after the system's long-press
     * threshold (~500ms) and shows the press state, which might hint at
     * something happening. Our Handler gives us full control.
     */
    private fun setupHiddenGateway() {
        binding.btnClearCache.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressRunnable = Runnable { showPinDialog() }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION)
                    false   // Return false so normal click still works
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    false
                }
                else -> false
            }
        }
    }

    // ─── Corner Invisible Trigger ─────────────────────────────────────────────

    /**
     * Sets up the invisible 80×80dp corner view as a backup trigger.
     * The view has alpha=0 and no background — completely invisible.
     *
     * TripleTapDetector handles:
     *   Triple-Tap         → FakeCallActivity
     *   Triple-Tap + Hold  → SOSManager
     */
    private fun setupCornerTrigger() {
        cornerTrigger = TripleTapDetector(
            onTripleTap = {
                launchFakeCall()
            },
            onTripleTapAndHold = {
                launchSOS()
            }
        )

        binding.viewCornerTrigger.setOnTouchListener { _, event ->
            cornerTrigger.onTouchEvent(event)
        }
    }

    // ─── PIN Dialog — Verification ────────────────────────────────────────────

    private fun showPinDialog() {
        // If locked out, show absolutely nothing. No dialog, no toast.
        if (pinManager.isLockedOut()) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_pin, null)
        val pinInput   = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etPinInput
        )

        AlertDialog.Builder(this, R.style.PinDialogTheme)
            .setView(dialogView)
            .setCancelable(true)
            .setPositiveButton("OK") { dialog, _ ->
                val entered = pinInput?.text?.toString() ?: ""
                handlePinAttempt(entered)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun handlePinAttempt(pin: String) {
        if (pinManager.verifyPin(pin)) {
            openAdminPanel()
        } else {
            // BUG-14 FIX: drop unused `remaining` variable.
            // recordFailedAttempt() triggers lockout internally after 3 failures.
            // The caller sees nothing either way — stealth design intentional.
            pinManager.recordFailedAttempt()
        }
    }

    // ─── PIN Dialog — First Launch Setup ─────────────────────────────────────

    /**
     * First-launch flow: user MUST set their PIN before using the app.
     * This dialog is NOT cancelable — the user must set a PIN.
     */
    private fun showSetPinDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pin_setup, null)
        val pinInput   = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etNewPin
        )
        val pinConfirm = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etConfirmPin
        )
        val tvError    = dialogView.findViewById<android.widget.TextView>(R.id.tvPinError)

        val dialog = AlertDialog.Builder(this, R.style.PinDialogTheme)
            .setView(dialogView)
            .setCancelable(false)  // User MUST set a PIN
            .setPositiveButton("Set PIN", null)  // null listener set below for validation
            .create()

        dialog.show()

        // Override the positive button to validate before dismissing
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p1 = pinInput?.text?.toString() ?: ""
            val p2 = pinConfirm?.text?.toString() ?: ""

            when {
                p1.length < 4 -> tvError?.text = "PIN must be at least 4 digits."
                p1 != p2      -> tvError?.text = "PINs do not match."
                else -> {
                    pinManager.setPin(p1)
                    dialog.dismiss()
                    Toast.makeText(this, "Setup complete.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    private fun openAdminPanel() {
        startActivity(Intent(this, AdminActivity::class.java))
    }

    private fun launchFakeCall() {
        val intent = Intent(this, FakeCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun launchSOS() {
        val intent = Intent(this, SOSManager::class.java).apply {
            action = SOSManager.ACTION_SEND_SOS
        }
        // A-04: SOSManager calls startForeground() internally — must be
        // started via startForegroundService()/ContextCompat equivalent on
        // API 26+, or strict OEM skins (Xiaomi/Oppo) can throw/crash here.
        ContextCompat.startForegroundService(this, intent)
    }
}

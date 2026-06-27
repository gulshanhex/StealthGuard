package com.system.cacheclean.ui

import android.app.KeyguardManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.system.cacheclean.call.CallState
import com.system.cacheclean.call.CallStateManager
import com.system.cacheclean.call.PersonaRepository
import com.system.cacheclean.databinding.ActivityFakeCallBinding
import com.system.cacheclean.db.StealthGuardDatabase
import com.system.cacheclean.db.entity.PersonaEntity
import com.system.cacheclean.model.Gender
import com.system.cacheclean.storage.AudioResolver
import com.system.cacheclean.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FakeCallActivity — Full Phase 4 Implementation
 *
 * Displays a convincing fake incoming call screen and drives the
 * HOOK → LISTEN → RESPOND/FILLER state machine via CallStateManager.
 *
 * FEATURES:
 *   • Appears over the lockscreen without unlocking (setShowWhenLocked)
 *   • Wakes the physical screen (setTurnScreenOn)
 *   • Randomly selected CallerPersona from Room DB
 *   • User-configured custom caller ID shown on screen (A-11)
 *   • Proximity sensor → screen goes black when phone is near ear
 *   • Gender-locked audio (every clip matches the selected persona's gender)
 *   • 3-finger tap anywhere → instant silent panic cancel
 *   • Back button disabled (use End button or panic cancel)
 *   • Call duration timer shown during in-call phase
 */
class FakeCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFakeCallBinding

    // ─── Session ─────────────────────────────────────────────────────────────

    private var sessionPersona: PersonaEntity? = null
    private var callStateManager: CallStateManager? = null

    // ─── Accept Retry Guard (H-02) ──────────────────────────────────────────────
    private var acceptRetryCount = 0
    private var acceptRetryHandler: Handler? = null

    // ─── Proximity Sensor ────────────────────────────────────────────────────

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var savedBrightness = -1f
    private var isScreenBlacked = false

    // ─── Duration Timer ───────────────────────────────────────────────────────

    private val durationHandler = Handler(Looper.getMainLooper())
    private var durationSeconds = 0
    private val durationTick = object : Runnable {
        override fun run() {
            durationSeconds++
            binding.tvCallDuration.text = formatDuration(durationSeconds)
            durationHandler.postDelayed(this, 1_000L)
        }
    }

    companion object {
        private const val TAG = "StealthGuard_Call"
        private const val MAX_ACCEPT_RETRIES = 20 // ~3s at 150ms intervals
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFakeCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        makeFullscreen()
        applyLockscreenFlags()
        disableBackButton()
        showIncomingCallPanel()

        // Load random persona on IO thread, update UI on Main
        lifecycleScope.launch(Dispatchers.IO) {
            val persona = PersonaRepository(this@FakeCallActivity).getRandomPersona()
            withContext(Dispatchers.Main) {
                sessionPersona         = persona
                binding.tvCallerName.text  = persona.displayName
                binding.tvCallerLabel.text = "Mobile"
                binding.tvInCallName.text  = persona.displayName
            }
        }

        binding.tvFakeNumber.text = resolveCallerNumber()
        setupProximitySensor()
        setupButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        callStateManager?.release()
        unregisterProximitySensor()
        durationHandler.removeCallbacks(durationTick)
        acceptRetryHandler?.removeCallbacksAndMessages(null)
    }

    // ─── Window Flags ─────────────────────────────────────────────────────────

    private fun makeFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun applyLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(KeyguardManager::class.java)
                ?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissError() {
                        // A-25: previously no callback at all — we'd never know if
                        // the dismiss silently failed (e.g. secure lockscreen with
                        // no FLAG_SHOW_WHEN_LOCKED support on some OEM skins).
                        android.util.Log.w(TAG, "Keyguard dismiss request failed.")
                    }
                })
        }
    }

    private fun disableBackButton() {
        onBackPressedDispatcher.addCallback(this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { /* intentionally empty */ }
            }
        )
    }

    // ─── UI Panel Control ─────────────────────────────────────────────────────

    private fun showIncomingCallPanel() {
        binding.layoutIncoming.visibility = View.VISIBLE
        binding.layoutInCall.visibility   = View.GONE
    }

    private fun showInCallPanel() {
        binding.layoutIncoming.visibility = View.GONE
        binding.layoutInCall.visibility   = View.VISIBLE
        durationSeconds = 0
        binding.tvCallDuration.text = "0:00"
        durationHandler.post(durationTick)
    }

    private fun onCallStateChanged(state: CallState) {
        // State hint label intentionally left blank for stealth — no text
        // visible on screen that might hint the call is fake.
        // Extend here if debug logging to UI is needed during development.
        if (state == CallState.ENDED) finish()
    }

    // ─── Buttons ─────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnAccept.setOnClickListener  { acceptCall() }
        binding.btnDecline.setOnClickListener { panicCancel() }
        binding.btnEndCall.setOnClickListener { panicCancel() }
    }

    // ─── Call Control ─────────────────────────────────────────────────────────

    private fun acceptCall() {
        val persona = sessionPersona
        if (persona == null) {
            // Persona DB load not yet complete — retry after short delay,
            // capped at MAX_ACCEPT_RETRIES (~3s) to avoid an infinite loop
            // and Handler leak if the DB load never completes (H-02).
            if (acceptRetryCount >= MAX_ACCEPT_RETRIES) {
                acceptRetryCount = 0
                panicCancel()
                return
            }
            acceptRetryCount++
            val handler = Handler(Looper.getMainLooper())
            acceptRetryHandler = handler
            handler.postDelayed({ acceptCall() }, 150L)
            return
        }
        acceptRetryCount = 0
        acceptRetryHandler = null

        val gender = runCatching { Gender.valueOf(persona.gender) }
            .getOrDefault(Gender.GENTS)

        showInCallPanel()
        registerProximitySensor()

        // H-03: release any in-flight CallStateManager before replacing it,
        // so a double-tap on Accept can't create two live instances.
        callStateManager?.release()

        val audioResolver = AudioResolver(
            storageManager = StorageManager(applicationContext),
            keywordAudioDao = StealthGuardDatabase.getInstance(this).keywordAudioDao(),
            context = applicationContext    // M-09: avoid leaking the Activity context
        )

        callStateManager = CallStateManager(
            context       = this,
            audioResolver = audioResolver,
            gender        = gender,
            scope         = lifecycleScope,
            onStateChange = ::onCallStateChanged
        ).also { it.start() }
    }

    // ─── Panic Cancel ─────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (event.pointerCount >= 3) {
                panicCancel()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun panicCancel() {
        acceptRetryHandler?.removeCallbacksAndMessages(null)
        acceptRetryCount = 0
        callStateManager?.release()
        callStateManager = null
        // N-14: the sensor listener was only torn down in onDestroy(); if the
        // Activity is finish()'d but Android delays destroying it, the
        // listener kept running for that gap.
        unregisterProximitySensor()
        restoreScreenBrightness()
        finish()
    }

    // ─── Proximity Sensor ─────────────────────────────────────────────────────

    private fun setupProximitySensor() {
        sensorManager   = getSystemService(SENSOR_SERVICE) as? SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    private fun registerProximitySensor() {
        val sensor = proximitySensor ?: return
        sensorManager?.registerListener(
            proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    private fun unregisterProximitySensor() {
        sensorManager?.unregisterListener(proximityListener)
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
            val isNear = event.values[0] < event.sensor.maximumRange
            if (isNear) blackoutScreen() else restoreScreenBrightness()
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private fun blackoutScreen() {
        if (isScreenBlacked) return
        val lp = window.attributes
        savedBrightness = lp.screenBrightness
        lp.screenBrightness = 0.0f
        window.attributes = lp
        isScreenBlacked = true
    }

    private fun restoreScreenBrightness() {
        if (!isScreenBlacked) return
        val lp = window.attributes
        lp.screenBrightness = savedBrightness
        window.attributes = lp
        isScreenBlacked = false
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * A-11: returns the user-configured custom caller ID (any country code).
     * Falls back to a generic placeholder only if the user hasn't set one
     * yet in Setup — no more hardcoded "+91" / randomly generated digits.
     */
    private fun resolveCallerNumber(): String =
        StorageManager(applicationContext).getCustomCallerId() ?: "Unknown"

    private fun formatDuration(s: Int) = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

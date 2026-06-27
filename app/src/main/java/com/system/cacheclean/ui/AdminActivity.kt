package com.system.cacheclean.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.system.cacheclean.R
import com.system.cacheclean.databinding.ActivityAdminBinding
import com.system.cacheclean.security.PinManager
import com.system.cacheclean.ui.admin.AudioStudioFragment
import com.system.cacheclean.ui.admin.KeywordMatrixFragment
import com.system.cacheclean.ui.admin.PersonaManagerFragment
import com.system.cacheclean.ui.admin.SetupFragment
import com.system.cacheclean.ui.admin.SosSettingsFragment

/**
 * AdminActivity — The Real Dashboard
 *
 * Accessible ONLY via correct PIN from MainActivity.
 *
 * TAB LAYOUT (Phase 6: added Setup tab):
 *   Tab 0 — 🎙 GENTS     Audio Studio (male voice)
 *   Tab 1 — 🎙 LADY      Audio Studio (female voice)
 *   Tab 2 — 🗺 MATRIX    Keyword → Audio mapping
 *   Tab 3 — 👤 PERSONAS  Caller name + gender pool
 *   Tab 4 — 🚨 SOS       Trusted contacts
 *   Tab 5 — ⚙ SETUP     Permission dashboard (Phase 6)
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding

    private val tabs = listOf(
        TabConfig("GENTS",    "🎙"),
        TabConfig("LADY",     "🎙"),
        TabConfig("MATRIX",   "🗺"),
        TabConfig("PERSONAS", "👤"),
        TabConfig("SOS",      "🚨"),
        TabConfig("SETUP",    "⚙")   // Phase 6
    )

    data class TabConfig(val title: String, val icon: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Advanced Settings"
            setDisplayHomeAsUpEnabled(true)
        }

        binding.viewPager.adapter = AdminPagerAdapter(this)
        // A-17: with only 1 extra tab kept alive, jumping from tab 0 to the
        // default tab 5 (SETUP) tore down and recreated 4 fragments in
        // between, causing a visible flicker/reload. Keep all tabs alive —
        // there are only 6 lightweight fragments, the memory cost is trivial.
        binding.viewPager.offscreenPageLimit = tabs.size - 1

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = "${tabs[position].icon} ${tabs[position].title}"
        }.attach()

        // Default: open SETUP tab on first launch so user sees permission status.
        // On rotation/recreation, restore whichever tab the user was on (M-02).
        val startTab = savedInstanceState?.getInt(KEY_CURRENT_TAB) ?: 5
        binding.viewPager.setCurrentItem(startTab, false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, binding.viewPager.currentItem)
    }

    companion object {
        private const val KEY_CURRENT_TAB = "currentTab"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.admin_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_change_pin -> { showChangePinDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showChangePinDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pin_setup, null)
        val tilCurrent = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilCurrentPin)
        val etCurrent = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCurrentPin)
        val etNew     = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etNewPin)
        val etConfirm = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etConfirmPin)
        val tvError   = dialogView.findViewById<android.widget.TextView>(R.id.tvPinError)

        // A-02: show the current-PIN field for this flow only (hidden by
        // default since dialog_pin_setup.xml is shared with first-launch setup).
        tilCurrent.visibility = android.view.View.VISIBLE

        val dialog = AlertDialog.Builder(this, R.style.PinDialogTheme)
            .setTitle("Change Security Code")
            .setView(dialogView)
            .setCancelable(true)
            .setPositiveButton("Update", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val current = etCurrent?.text?.toString() ?: ""
            val p1 = etNew?.text?.toString() ?: ""
            val p2 = etConfirm?.text?.toString() ?: ""
            val pinManager = PinManager(this)
            when {
                !pinManager.verifyPin(current) -> tvError?.text = "Current security code is incorrect."
                p1.length < 4 -> tvError?.text = "PIN must be at least 4 digits."
                p1 != p2      -> tvError?.text = "PINs do not match."
                else -> {
                    pinManager.setPin(p1)
                    dialog.dismiss()
                    android.widget.Toast.makeText(
                        this, "Security code updated.", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private inner class AdminPagerAdapter(activity: AdminActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount() = tabs.size

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> AudioStudioFragment.newInstance(isGents = true)
            1 -> AudioStudioFragment.newInstance(isGents = false)
            2 -> KeywordMatrixFragment()
            3 -> PersonaManagerFragment()
            4 -> SosSettingsFragment()
            5 -> SetupFragment()          // Phase 6
            else -> throw IllegalArgumentException("Invalid tab: $position")
        }
    }
}

package com.system.cacheclean.ui.admin

import android.Manifest
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.system.cacheclean.R
import com.system.cacheclean.storage.StorageManager
import com.system.cacheclean.util.BatteryOptimizationHelper
import com.system.cacheclean.util.PermissionManager
import com.system.cacheclean.util.PermissionStatus

/**
 * SetupFragment — Admin Tab 5: SETUP
 *
 * Live permission dashboard showing the current grant status of every
 * permission StealthGuard requires. Updates automatically when the
 * fragment is resumed (e.g., after the user grants a permission in Settings
 * and presses Back).
 *
 * LAYOUT:
 *   ┌─────────────────────────────────────────────────────┐
 *   │  ✅  Microphone                    [Granted]         │
 *   │      Required for STT + recording                   │
 *   ├─────────────────────────────────────────────────────┤
 *   │  ❌  Accessibility Service         [FIX →]          │
 *   │      Required for volume button trigger             │
 *   ├─────────────────────────────────────────────────────┤
 *   │  ⚠   Battery Unrestricted          [FIX →]          │
 *   │      Prevents MIUI from killing the service         │
 *   └─────────────────────────────────────────────────────┘
 *
 *   ROM-specific instructions shown below the list when battery
 *   optimization is not yet granted.
 *
 * PERMISSION FLOW:
 *   Standard permissions (Mic, Location, SMS):
 *     → ActivityResultLauncher.launch() → system dialog
 *   Special permissions (Overlay, Accessibility, Battery):
 *     → PermissionManager.openSettingsForPermission() → Settings screen
 *   After user returns → onResume() refreshes the list automatically
 */
class SetupFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvRomInstructions: TextView
    private lateinit var tvAllGranted: TextView
    private lateinit var adapter: PermissionRowAdapter

    // ─── Standard Permission Launcher ────────────────────────────────────────

    private var pendingPermissionKey: String? = null

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val key = pendingPermissionKey
            if (granted) {
                Toast.makeText(requireContext(), "Permission granted ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(),
                    "Permission denied. Tap FIX to try again.", Toast.LENGTH_SHORT).show()
            }
            pendingPermissionKey = null
            refreshList()   // Update the row status
        }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_setup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView      = view.findViewById(R.id.rvPermissions)
        tvRomInstructions = view.findViewById(R.id.tvRomInstructions)
        tvAllGranted      = view.findViewById(R.id.tvAllGranted)

        adapter = PermissionRowAdapter { status ->
            handleFixTap(status)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        setupCallerIdField(view)

        refreshList()
    }

    // ─── Custom Caller ID (A-11) ──────────────────────────────────────────────

    private fun setupCallerIdField(view: View) {
        val etCallerId = view.findViewById<EditText>(R.id.etCallerId)
        val btnSave    = view.findViewById<Button>(R.id.btnSaveCallerId)
        val storage    = StorageManager(requireContext().applicationContext)

        etCallerId.setText(storage.getCustomCallerId() ?: "")

        btnSave.setOnClickListener {
            val number = etCallerId.text?.toString()?.trim() ?: ""
            if (number.isEmpty()) {
                Toast.makeText(requireContext(), "Enter a caller ID first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            storage.setCustomCallerId(number)
            Toast.makeText(requireContext(), "Caller ID saved ✓", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * onResume fires when the user returns from a Settings screen.
     * This is the key mechanism that makes the dashboard "live" —
     * no polling needed.
     */
    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // ─── List Refresh ─────────────────────────────────────────────────────────

    private fun refreshList() {
        val statuses = PermissionManager.getAllStatuses(requireContext())
        adapter.submitList(statuses)

        val allGranted = statuses.all { it.isGranted }
        tvAllGranted.visibility = if (allGranted) View.VISIBLE else View.GONE

        // Show ROM-specific battery instructions if battery is not exempt
        val batteryStatus = statuses.find { it.key == PermissionManager.KEY_BATTERY }
        if (batteryStatus?.isGranted == false) {
            val rom = BatteryOptimizationHelper.detectRom()
            val instructions = BatteryOptimizationHelper.getRomSpecificInstructions(rom)
            tvRomInstructions.text = instructions
            tvRomInstructions.visibility = View.VISIBLE
        } else {
            tvRomInstructions.visibility = View.GONE
        }
    }

    // ─── Fix Button Handler ───────────────────────────────────────────────────

    private fun handleFixTap(status: PermissionStatus) {
        if (status.isSpecial) {
            // Special permission → open the correct Settings screen
            PermissionManager.openSettingsForPermission(requireContext(), status)
        } else {
            // Standard permission → launch the Android permission dialog
            val androidPermission = PermissionManager.toAndroidPermission(status.key)
            if (androidPermission != null) {
                pendingPermissionKey = status.key
                requestPermission.launch(androidPermission)
            }
        }
    }
}


// ═════════════════════════════════════════════════════════════════════════════
// PermissionRowAdapter
// ═════════════════════════════════════════════════════════════════════════════

class PermissionRowAdapter(
    private val onFixClick: (PermissionStatus) -> Unit
) : ListAdapter<PermissionStatus, PermissionRowAdapter.VH>(PermDiff()) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon:        TextView    = view.findViewById(R.id.tvPermIcon)
        val tvLabel:       TextView    = view.findViewById(R.id.tvPermLabel)
        val tvDescription: TextView    = view.findViewById(R.id.tvPermDescription)
        val tvStatus:      TextView    = view.findViewById(R.id.tvPermStatus)
        val btnFix:        Button      = view.findViewById(R.id.btnFixPermission)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_permission_row, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val status = getItem(position)

        holder.tvIcon.text  = if (status.isGranted) "✅" else if (status.isCritical) "❌" else "⚠️"
        holder.tvLabel.text = status.label
        holder.tvDescription.text = status.description

        if (status.isGranted) {
            holder.tvStatus.text = "Granted"
            holder.tvStatus.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.holo_green_dark))
            holder.btnFix.visibility = View.GONE
        } else {
            holder.tvStatus.text = if (status.isCritical) "Required" else "Recommended"
            holder.tvStatus.setTextColor(
                ContextCompat.getColor(holder.itemView.context,
                    if (status.isCritical) android.R.color.holo_red_dark
                    else android.R.color.holo_orange_dark))
            holder.btnFix.visibility = View.VISIBLE
            holder.btnFix.text = if (status.isSpecial) "Open Settings →" else "Grant"
            holder.btnFix.setOnClickListener { onFixClick(status) }
        }
    }

    class PermDiff : DiffUtil.ItemCallback<PermissionStatus>() {
        override fun areItemsTheSame(a: PermissionStatus, b: PermissionStatus) = a.key == b.key
        override fun areContentsTheSame(a: PermissionStatus, b: PermissionStatus) = a == b
    }
}

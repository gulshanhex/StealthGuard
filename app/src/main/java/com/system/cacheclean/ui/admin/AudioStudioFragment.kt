package com.system.cacheclean.ui.admin

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.system.cacheclean.R
import com.system.cacheclean.audio.AudioPlayer
import com.system.cacheclean.audio.WaveformView
import com.system.cacheclean.model.AudioType
import com.system.cacheclean.model.Gender
import com.system.cacheclean.ui.admin.adapter.AudioFileAdapter
import java.io.File

/**
 * AudioStudioFragment — Full Implementation
 *
 * One instance per gender tab (GENTS = Tab 0, LADY = Tab 1).
 * Gender is passed via arguments Bundle — same class, different data.
 *
 * SECTIONS:
 *   ┌─────────────────────────────────────────┐
 *   │  HOOK AUDIO                       [+]   │  ← FAB records new hook
 *   │  ┌──────────────────────────────────┐   │
 *   │  │ hook_g_17181234.m4a [●ACTIVE][▶][✕]│   │
 *   │  └──────────────────────────────────┘   │
 *   ├─────────────────────────────────────────┤
 *   │  RESPONSE AUDIO                   [+]   │  ← FAB records + links keyword
 *   │  ┌──────────────────────────────────┐   │
 *   │  │ "market" resp_g_17181235.m4a [▶][✕]│   │
 *   │  └──────────────────────────────────┘   │
 *   ├─────────────────────────────────────────┤
 *   │  FILLER AUDIO                     [+]   │  ← FAB records new filler
 *   │  ┌──────────────────────────────────┐   │
 *   │  │ fill_g_17181236.m4a         [▶][✕]│   │
 *   │  └──────────────────────────────────┘   │
 *   └─────────────────────────────────────────┘
 */
class AudioStudioFragment : Fragment() {

    companion object {
        private const val ARG_IS_GENTS = "is_gents"
        private const val AMPLITUDE_POLL_MS = 80L

        fun newInstance(isGents: Boolean) = AudioStudioFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_IS_GENTS, isGents) }
        }
    }

    private val viewModel: AudioStudioViewModel by viewModels()
    private val audioPlayer = AudioPlayer()

    private val gender: Gender
        get() = if (arguments?.getBoolean(ARG_IS_GENTS, true) == true)
            Gender.GENTS else Gender.LADY

    // Adapters for the three RecyclerViews
    private lateinit var hookAdapter:     AudioFileAdapter
    private lateinit var responseAdapter: AudioFileAdapter
    private lateinit var fillerAdapter:   AudioFileAdapter

    // Track which type is currently being recorded
    private var activeRecordType: AudioType? = null

    // Waveform polling
    private var waveformView: WaveformView? = null

    // N-03: tracked at class level so onDestroyView() can dismiss the dialog
    // and cancel its tick handler if the Fragment is destroyed mid-recording
    // (e.g. rotation) instead of leaking the dialog Window.
    private var recordDialog: AlertDialog? = null
    private val durationHandler = Handler(Looper.getMainLooper())
    private var durationRunnable: Runnable? = null

    // ─── Permission Request ───────────────────────────────────────────────────

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingRecordAction?.invoke()
            else Toast.makeText(requireContext(),
                "Microphone permission required for recording.", Toast.LENGTH_SHORT).show()
            pendingRecordAction = null
        }
    private var pendingRecordAction: (() -> Unit)? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_audio_studio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews(view)
        setupFabs(view)
        observeViewModel()
        viewModel.loadFiles(gender)
    }

    // ─── RecyclerViews ────────────────────────────────────────────────────────

    private fun setupRecyclerViews(view: View) {
        hookAdapter = AudioFileAdapter(
            onPlay      = { file -> playFile(file) },
            onDelete    = { file -> confirmDelete(file, AudioType.HOOK) },
            onSetActive = { file -> viewModel.setActiveHook(gender, file) },
            activeHookProvider = { viewModel.getActiveHook(gender)?.name },
            showSetActive = true
        )

        responseAdapter = AudioFileAdapter(
            onPlay      = { file -> playFile(file) },
            onDelete    = { file -> confirmDelete(file, AudioType.RESPONSE) },
            onSetActive = null,
            activeHookProvider = { null },
            showSetActive = false
        )

        fillerAdapter = AudioFileAdapter(
            onPlay      = { file -> playFile(file) },
            onDelete    = { file -> confirmDelete(file, AudioType.FILLER) },
            onSetActive = null,
            activeHookProvider = { null },
            showSetActive = false
        )

        view.findViewById<RecyclerView>(R.id.rvHooks).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = hookAdapter
        }
        view.findViewById<RecyclerView>(R.id.rvResponses).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = responseAdapter
        }
        view.findViewById<RecyclerView>(R.id.rvFillers).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = fillerAdapter
        }
    }

    // ─── FABs ─────────────────────────────────────────────────────────────────

    private fun setupFabs(view: View) {
        view.findViewById<FloatingActionButton>(R.id.fabAddHook)
            .setOnClickListener { withAudioPermission { showRecordDialog(AudioType.HOOK) } }
        view.findViewById<FloatingActionButton>(R.id.fabAddResponse)
            .setOnClickListener { withAudioPermission { showRecordDialog(AudioType.RESPONSE) } }
        view.findViewById<FloatingActionButton>(R.id.fabAddFiller)
            .setOnClickListener { withAudioPermission { showRecordDialog(AudioType.FILLER) } }
    }

    // ─── ViewModel Observation ────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.hookFiles.observe(viewLifecycleOwner)     { hookAdapter.submitList(it) }
        viewModel.responseFiles.observe(viewLifecycleOwner) { responseAdapter.submitList(it) }
        viewModel.fillerFiles.observe(viewLifecycleOwner)   { fillerAdapter.submitList(it) }
    }

    // ─── Record Dialog ────────────────────────────────────────────────────────

    /**
     * Shows the recording dialog with live waveform animation.
     * For RESPONSE type, also shows a keyword entry field.
     */
    private fun showRecordDialog(type: AudioType) {
        audioPlayer.stop()
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_record_audio, null)

        val tvTitle     = dialogView.findViewById<TextView>(R.id.tvRecordTitle)
        val etKeyword   = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(
            R.id.tilKeyword)
        val waveform    = dialogView.findViewById<WaveformView>(R.id.waveformView)
        val btnRecord   = dialogView.findViewById<Button>(R.id.btnRecordToggle)
        val tvDuration  = dialogView.findViewById<TextView>(R.id.tvDuration)
        val tvStatus    = dialogView.findViewById<TextView>(R.id.tvRecordStatus)

        this.waveformView = waveform

        // Configure dialog for type
        tvTitle.text  = "Record ${type.name.lowercase().replaceFirstChar { it.uppercase() }} Audio"
        etKeyword.visibility = if (type == AudioType.RESPONSE) View.VISIBLE else View.GONE

        var isCurrentlyRecording = false
        var savedFile: File? = null
        var durationSeconds = 0
        durationRunnable = object : Runnable {
            override fun run() {
                // N-15: if RECORD_AUDIO is revoked mid-recording (some custom
                // ROMs do this aggressively), stop cleanly instead of letting
                // the recording silently fail or produce a corrupt file.
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    viewModel.cancelRecording(gender)
                    isCurrentlyRecording = false
                    waveform.stopAnimating()
                    tvStatus.text = "Microphone permission revoked — recording stopped."
                    btnRecord.text = "Record Again"
                    btnRecord.setBackgroundColor(
                        ContextCompat.getColor(requireContext(), R.color.colorPrimary))
                    return
                }
                durationSeconds++
                tvDuration.text = formatDuration(durationSeconds)
                durationHandler.postDelayed(this, 1000L)
            }
        }
        val durationRunnableLocal = durationRunnable!!

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Save", null)
            .setNegativeButton("Discard") { _, _ ->
                if (isCurrentlyRecording) viewModel.cancelRecording(gender)
                else savedFile?.delete()
                waveform.stopAnimating()
                durationHandler.removeCallbacks(durationRunnableLocal)
            }
            .create()
        recordDialog = dialog

        btnRecord.setOnClickListener {
            if (!isCurrentlyRecording) {
                // START recording
                val started = viewModel.startRecording(gender, type)
                if (!started) {
                    Toast.makeText(requireContext(),
                        "Could not start recording.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isCurrentlyRecording = true
                durationSeconds = 0
                tvDuration.text = "0:00"
                tvStatus.text   = "Recording…"
                btnRecord.text  = "Stop"
                btnRecord.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                waveform.startAnimating { viewModel.getAmplitude() }
                durationHandler.post(durationRunnableLocal)
            } else {
                // STOP recording
                savedFile = viewModel.stopRecording(gender, type)
                isCurrentlyRecording = false
                waveform.stopAnimating()
                durationHandler.removeCallbacks(durationRunnableLocal)
                btnRecord.text  = "Record Again"
                btnRecord.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.colorPrimary))
                tvStatus.text   = if (savedFile != null)
                    "✓ Recorded: ${savedFile!!.name}" else "Recording failed."
            }
        }

        dialog.show()

        // Override Save to validate + link keyword before dismissing
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (isCurrentlyRecording) {
                savedFile = viewModel.stopRecording(gender, type)
                waveform.stopAnimating()
                durationHandler.removeCallbacks(durationRunnableLocal)
                isCurrentlyRecording = false
            }
            if (savedFile == null) {
                Toast.makeText(requireContext(),
                    "Nothing recorded yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (type == AudioType.RESPONSE) {
                val keyword = etKeyword.editText?.text?.toString()?.trim() ?: ""
                if (keyword.isEmpty()) {
                    etKeyword.error = "Keyword required for response audio"
                    return@setOnClickListener
                }
                etKeyword.error = null
                viewModel.linkResponseToKeyword(gender, keyword, savedFile!!)
            }
            waveform.stopAnimating()
            dialog.dismiss()
        }
    }

    // ─── Delete Confirmation ──────────────────────────────────────────────────

    private fun confirmDelete(file: File, type: AudioType) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Recording")
            .setMessage("Delete '${file.name}'? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteFile(gender, type, file)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Playback ─────────────────────────────────────────────────────────────

    private fun playFile(file: File) {
        audioPlayer.stop()
        val played = audioPlayer.play(file) {}
        if (!played) Toast.makeText(requireContext(),
            "Could not play file.", Toast.LENGTH_SHORT).show()
    }

    // ─── Permission ───────────────────────────────────────────────────────────

    private fun withAudioPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingRecordAction = action
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun formatDuration(seconds: Int): String =
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    override fun onDestroyView() {
        super.onDestroyView()
        // A-05: if the user was recording when the fragment is destroyed
        // (e.g. rotation), cancel it — otherwise the MediaRecorder keeps
        // writing to disk with no visible UI to stop it.
        if (viewModel.isRecorderActive) {
            viewModel.cancelRecording(gender)
        }
        // N-03: dismiss the record dialog explicitly — letting it outlive
        // this Fragment leaks its Window (WindowLeaked) on rotation.
        recordDialog?.dismiss()
        recordDialog = null
        durationRunnable?.let { durationHandler.removeCallbacks(it) }
        durationRunnable = null
        audioPlayer.release()
        waveformView?.stopAnimating()
        waveformView = null
    }
}

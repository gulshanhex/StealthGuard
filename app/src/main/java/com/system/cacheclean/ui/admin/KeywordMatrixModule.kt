package com.system.cacheclean.ui.admin

// ─── KeywordMatrixViewModel ───────────────────────────────────────────────────

import android.app.Application
import android.app.AlertDialog
import android.os.Bundle
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.system.cacheclean.R
import com.system.cacheclean.audio.AudioPlayer
import com.system.cacheclean.db.StealthGuardDatabase
import com.system.cacheclean.db.entity.KeywordAudioEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class KeywordMatrixViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = StealthGuardDatabase.getInstance(app).keywordAudioDao()

    /** All mappings — drives the RecyclerView via LiveData. */
    val allMappings: LiveData<List<KeywordAudioEntity>> = dao.getAllMappings()

    fun delete(entity: KeywordAudioEntity) {
        viewModelScope.launch(Dispatchers.IO) { dao.delete(entity) }
    }

    /**
     * Adds a new keyword mapping with optional per-gender audio paths.
     * If the keyword already exists, the existing row is updated (upsert).
     */
    fun addOrUpdate(keyword: String, gentsPath: String?, ladyPath: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsert(
                KeywordAudioEntity(
                    keyword        = keyword.trim().lowercase(),
                    gentsAudioPath = gentsPath,
                    ladyAudioPath  = ladyPath
                )
            )
        }
    }
}


// ─── KeywordMatrixFragment ────────────────────────────────────────────────────

class KeywordMatrixFragment : Fragment() {

    private val viewModel: KeywordMatrixViewModel by viewModels()
    private lateinit var adapter: KeywordMappingAdapter
    private val player = AudioPlayer()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_keyword_matrix, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = KeywordMappingAdapter(
            onPlayGents = { entity ->
                entity.gentsAudioPath?.let { player.play(File(it)) }
                    ?: Toast.makeText(requireContext(),
                        "No GENTS audio for this keyword.", Toast.LENGTH_SHORT).show()
            },
            onPlayLady = { entity ->
                entity.ladyAudioPath?.let { player.play(File(it)) }
                    ?: Toast.makeText(requireContext(),
                        "No LADY audio for this keyword.", Toast.LENGTH_SHORT).show()
            },
            onDelete = { entity ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete Mapping")
                    .setMessage("Delete keyword '${entity.keyword}'?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.delete(entity) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        view.findViewById<RecyclerView>(R.id.rvKeywordMatrix).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@KeywordMatrixFragment.adapter
        }

        viewModel.allMappings.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            view.findViewById<TextView>(R.id.tvEmptyMatrix).visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        view.findViewById<FloatingActionButton>(R.id.fabAddMapping)
            .setOnClickListener { showAddMappingDialog() }
    }

    /**
     * Add-mapping dialog.
     * The user enters a keyword — audio is linked via the Audio Studio
     * recording flow. This dialog is for manual keyword creation when audio
     * files already exist and just need to be linked by path.
     */
    private fun showAddMappingDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_keyword_mapping, null)

        val etKeyword    = dialogView.findViewById<TextInputEditText>(R.id.etNewKeyword)
        val etGentsPath  = dialogView.findViewById<TextInputEditText>(R.id.etGentsPath)
        val etLadyPath   = dialogView.findViewById<TextInputEditText>(R.id.etLadyPath)
        val tilKeyword   = dialogView.findViewById<TextInputLayout>(R.id.tilNewKeyword)

        AlertDialog.Builder(requireContext())
            .setTitle("Add Keyword Mapping")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val keyword = etKeyword.text?.toString()?.trim() ?: ""
                    if (keyword.isEmpty()) {
                        tilKeyword.error = "Keyword cannot be empty"
                        return@setOnClickListener
                    }
                    tilKeyword.error = null
                    val gentsPath = etGentsPath.text?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() && isPathWithinAppStorage(it) }
                    val ladyPath  = etLadyPath.text?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() && isPathWithinAppStorage(it) }
                    viewModel.addOrUpdate(keyword, gentsPath, ladyPath)
                    dialog.dismiss()
                }
            }
    }

    /**
     * N-16: previously any existing file on the phone was accepted (File(it).exists()),
     * meaning a typed path could point outside the app's own storage. Audio
     * playback during a live call should only ever read from filesDir.
     */
    private fun isPathWithinAppStorage(path: String): Boolean {
        return try {
            val file = File(path).canonicalFile
            val root = requireContext().filesDir.canonicalFile
            file.exists() && file.path.startsWith(root.path + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player.release()
    }
}


// ─── KeywordMappingAdapter ────────────────────────────────────────────────────

class KeywordMappingAdapter(
    private val onPlayGents: (KeywordAudioEntity) -> Unit,
    private val onPlayLady:  (KeywordAudioEntity) -> Unit,
    private val onDelete:    (KeywordAudioEntity) -> Unit
) : ListAdapter<KeywordAudioEntity, KeywordMappingAdapter.ViewHolder>(DiffCb()) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvKeyword:   TextView    = view.findViewById(R.id.tvKeyword)
        val tvGentsFile: TextView    = view.findViewById(R.id.tvGentsFile)
        val tvLadyFile:  TextView    = view.findViewById(R.id.tvLadyFile)
        val btnPlayG:    android.widget.ImageButton = view.findViewById(R.id.btnPlayGents)
        val btnPlayL:    android.widget.ImageButton = view.findViewById(R.id.btnPlayLady)
        val btnDelete:   android.widget.ImageButton = view.findViewById(R.id.btnDeleteMapping)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_keyword_mapping, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entity = getItem(position)
        holder.tvKeyword.text   = "\"${entity.keyword}\""
        holder.tvGentsFile.text = entity.gentsAudioPath
            ?.let { File(it).name } ?: "No GENTS audio"
        holder.tvLadyFile.text  = entity.ladyAudioPath
            ?.let { File(it).name } ?: "No LADY audio"

        holder.btnPlayG.alpha   = if (entity.gentsAudioPath != null) 1f else 0.3f
        holder.btnPlayL.alpha   = if (entity.ladyAudioPath  != null) 1f else 0.3f

        holder.btnPlayG.setOnClickListener  { onPlayGents(entity) }
        holder.btnPlayL.setOnClickListener  { onPlayLady(entity) }
        holder.btnDelete.setOnClickListener { onDelete(entity) }
    }

    class DiffCb : DiffUtil.ItemCallback<KeywordAudioEntity>() {
        override fun areItemsTheSame(a: KeywordAudioEntity, b: KeywordAudioEntity) = a.id == b.id
        override fun areContentsTheSame(a: KeywordAudioEntity, b: KeywordAudioEntity) = a == b
    }
}

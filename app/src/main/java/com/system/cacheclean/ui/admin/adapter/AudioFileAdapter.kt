package com.system.cacheclean.ui.admin.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.system.cacheclean.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * AudioFileAdapter
 *
 * Displays a list of audio Files in a RecyclerView.
 * Used by all three sections (Hook, Response, Filler) in AudioStudioFragment.
 *
 * The "Set as Active" button is shown only for hook files (showSetActive=true).
 * The current active hook is highlighted with a different background.
 *
 * Keywords (for response files) are shown via the file name prefix — the
 * naming convention resp_g_{keyword}_{timestamp}.m4a allows extraction.
 * For now, the full filename is shown — Phase 3.5 can add keyword overlay.
 */
class AudioFileAdapter(
    private val onPlay:             (File) -> Unit,
    private val onDelete:           (File) -> Unit,
    private val onSetActive:        ((File) -> Unit)?,
    private val activeHookProvider: () -> String?,    // Returns active hook filename
    private val showSetActive:      Boolean
) : ListAdapter<File, AudioFileAdapter.ViewHolder>(FileDiffCallback()) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName:    TextView    = view.findViewById(R.id.tvAudioFileName)
        val tvFileMeta:    TextView    = view.findViewById(R.id.tvAudioFileMeta)
        val btnPlay:       ImageButton = view.findViewById(R.id.btnPlayAudio)
        val btnDelete:     ImageButton = view.findViewById(R.id.btnDeleteAudio)
        val btnSetActive:  ImageButton = view.findViewById(R.id.btnSetActive)
        val activeBadge:   TextView    = view.findViewById(R.id.tvActiveBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file       = getItem(position)
        val isActive   = (file.name == activeHookProvider())
        val dateStr    = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            .format(Date(file.lastModified()))
        val sizeStr    = fileSizeLabel(file)

        holder.tvFileName.text = file.name
        holder.tvFileMeta.text = "$dateStr  •  $sizeStr"

        // Active badge (hooks only)
        holder.activeBadge.visibility = if (isActive) View.VISIBLE else View.GONE
        holder.itemView.setBackgroundColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (isActive) R.color.activeHookBackground else android.R.color.white
            )
        )

        // Set Active button
        holder.btnSetActive.visibility = if (showSetActive) View.VISIBLE else View.GONE
        holder.btnSetActive.setOnClickListener { onSetActive?.invoke(file) }

        // Play button
        holder.btnPlay.setOnClickListener { onPlay(file) }

        // Delete button
        holder.btnDelete.setOnClickListener { onDelete(file) }
    }

    private fun fileSizeLabel(file: File): String {
        val bytes = file.length()
        return when {
            bytes < 1_024     -> "${bytes}B"
            bytes < 1_048_576 -> "${"%.1f".format(bytes / 1_024f)}KB"
            else              -> "${"%.1f".format(bytes / 1_048_576f)}MB"
        }
    }

    class FileDiffCallback : DiffUtil.ItemCallback<File>() {
        override fun areItemsTheSame(old: File, new: File) = old.absolutePath == new.absolutePath
        override fun areContentsTheSame(old: File, new: File) =
            old.lastModified() == new.lastModified() && old.length() == new.length()
    }
}

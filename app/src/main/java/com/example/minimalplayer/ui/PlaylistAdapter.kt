package com.example.minimalplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.minimalplayer.R
import com.example.minimalplayer.data.Playlist

class PlaylistAdapter(
    private val onClick: (Playlist) -> Unit,
    private val onLongClick: (Playlist) -> Unit,
    private val onToggleSelect: (Playlist) -> Unit,
    private val isSelected: (Long) -> Boolean = { false },
    private val inSelectionMode: () -> Boolean = { false }
) : ListAdapter<Playlist, PlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView as com.google.android.material.card.MaterialCardView
        private val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)

        fun bind(playlist: Playlist) {
            tvName.text = playlist.name

            val selected = isSelected(playlist.id)
            card.strokeColor = androidx.core.content.ContextCompat.getColor(
                card.context,
                if (selected) R.color.accent_blue else android.R.color.white
            )
            card.strokeWidth = ((if (selected) 2 else 1) * itemView.resources.displayMetrics.density).toInt()

            itemView.setOnClickListener {
                if (inSelectionMode()) onToggleSelect(playlist) else onClick(playlist)
            }
            itemView.setOnLongClickListener {
                onLongClick(playlist)
                true
            }
        }
    }

    class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist) = oldItem == newItem
    }
}

package com.example.minimalplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.minimalplayer.R
import com.example.minimalplayer.data.Track

class TrackAdapter(
    private val onTrackClick: (Track) -> Unit,
    private val onTrackLongClick: ((Track) -> Unit)? = null
) : ListAdapter<Track, TrackAdapter.TrackViewHolder>(TrackDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view, onTrackClick, onTrackLongClick)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class TrackViewHolder(
        itemView: View,
        private val onTrackClick: (Track) -> Unit,
        private val onTrackLongClick: ((Track) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)

        fun bind(track: Track) {
            tvTitle.text = track.title
            
            tvDuration.text = track.durationMs?.let { ms ->
                val minutes = (ms / 1000) / 60
                val seconds = (ms / 1000) % 60
                String.format("%02d:%02d", minutes, seconds)
            } ?: "--:--"

            val alphaValue = if (track.isAvailable) 1.0f else 0.4f
            tvTitle.alpha = alphaValue
            tvDuration.alpha = alphaValue

            itemView.setOnClickListener {
                onTrackClick(track)
            }
            itemView.setOnLongClickListener {
                onTrackLongClick?.invoke(track)
                true
            }
        }
    }

    class TrackDiffCallback : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Track, newItem: Track) = oldItem == newItem
    }
}

package com.example.minimalplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.minimalplayer.R
import com.example.minimalplayer.data.Track
import com.example.minimalplayer.util.AlbumArtCache
import com.example.minimalplayer.util.BitmapUtil
import com.example.minimalplayer.util.FormatUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupedTrackAdapter(
    private val scope: CoroutineScope,
    private val onTrackClick: (Track) -> Unit,
    private val onTrackLongClick: ((Track) -> Unit)? = null,
    private val onTrackToggleSelect: ((Track) -> Unit)? = null,
    private val isSelected: (Long) -> Boolean = { false },
    private val inSelectionMode: () -> Boolean = { false }
) : ListAdapter<TrackListItem, RecyclerView.ViewHolder>(TrackListItemDiffCallback()) {

    companion object { const val TYPE_LETTER = 0; const val TYPE_ARTIST = 1; const val TYPE_TRACK = 2 }
    private val inFlightJobs = mutableMapOf<RecyclerView.ViewHolder, Job>()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is TrackListItem.LetterHeader -> TYPE_LETTER
        is TrackListItem.ArtistHeader -> TYPE_ARTIST
        is TrackListItem.TrackItem -> TYPE_TRACK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_LETTER -> LetterViewHolder(inflater.inflate(R.layout.item_letter_header, parent, false))
            TYPE_ARTIST -> ArtistViewHolder(inflater.inflate(R.layout.item_artist_header, parent, false))
            TYPE_TRACK -> TrackViewHolder(inflater.inflate(R.layout.item_track, parent, false), onTrackClick, onTrackLongClick, onTrackToggleSelect, isSelected, inSelectionMode)
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TrackListItem.LetterHeader -> (holder as LetterViewHolder).bind(item.letter)
            is TrackListItem.ArtistHeader -> (holder as ArtistViewHolder).bind(item.artist)
            is TrackListItem.TrackItem -> { (holder as TrackViewHolder).bind(item.track); loadAlbumArt(holder, item.track) }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is TrackViewHolder) inFlightJobs.remove(holder)?.cancel()
    }

    private fun loadAlbumArt(holder: TrackViewHolder, track: Track) {
        inFlightJobs.remove(holder)?.cancel()
        val cached = AlbumArtCache.lruCache.get(track.id)
        if (cached != null) { holder.ivAlbumArt.setImageBitmap(cached); return }
        
        holder.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
        holder.ivAlbumArt.tag = track.id
        val context = holder.itemView.context
        val job = scope.launch(Dispatchers.IO) {
            val bitmap = extractAlbumArt(context, track.uri)
            if (bitmap != null) AlbumArtCache.lruCache.put(track.id, bitmap)
            withContext(Dispatchers.Main) {
                if (holder.ivAlbumArt.tag == track.id && bitmap != null) holder.ivAlbumArt.setImageBitmap(bitmap)
            }
        }
        inFlightJobs[holder] = job
    }

    private fun extractAlbumArt(context: Context, uri: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            val artBytes = retriever.embeddedPicture ?: return null
            return BitmapUtil.decodeSampledBitmap(artBytes, 128, 128)
        } catch (_: Exception) { return null } finally { retriever.release() }
    }

    class LetterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLetter: TextView = itemView.findViewById(R.id.tvLetter)
        fun bind(letter: Char) { tvLetter.text = letter.toString() }
    }

    class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        fun bind(artist: String) { tvArtist.text = artist }
    }

    class TrackViewHolder(
        itemView: View, private val onTrackClick: (Track) -> Unit, private val onTrackLongClick: ((Track) -> Unit)?,
        private val onTrackToggleSelect: ((Track) -> Unit)?, private val isSelected: (Long) -> Boolean, private val inSelectionMode: () -> Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        val ivAlbumArt: ImageView = itemView.findViewById(R.id.ivAlbumArt)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)

        fun bind(track: Track) {
            tvTitle.text = track.title
            val dur = track.durationMs
            if (dur != null && dur > 0) { tvDuration.text = FormatUtil.formatTime(dur); tvDuration.visibility = View.VISIBLE } 
            else { tvDuration.visibility = View.GONE }
            
            val alphaValue = if (track.isAvailable) 1.0f else 0.4f
            tvTitle.alpha = alphaValue; tvDuration.alpha = alphaValue; ivAlbumArt.alpha = alphaValue
            itemView.isActivated = isSelected(track.id)

            itemView.setOnClickListener { if (inSelectionMode()) onTrackToggleSelect?.invoke(track) else onTrackClick(track) }
            itemView.setOnLongClickListener { onTrackLongClick?.invoke(track); true }
        }
    }

    class TrackListItemDiffCallback : DiffUtil.ItemCallback<TrackListItem>() {
        override fun areItemsTheSame(oldItem: TrackListItem, newItem: TrackListItem): Boolean = when {
            oldItem is TrackListItem.LetterHeader && newItem is TrackListItem.LetterHeader -> oldItem.letter == newItem.letter
            oldItem is TrackListItem.ArtistHeader && newItem is TrackListItem.ArtistHeader -> oldItem.artist == newItem.artist
            oldItem is TrackListItem.TrackItem && newItem is TrackListItem.TrackItem -> oldItem.track.id == newItem.track.id
            else -> false
        }
        override fun areContentsTheSame(oldItem: TrackListItem, newItem: TrackListItem) = oldItem == newItem
    }
}

package com.example.minimalplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.minimalplayer.R
import com.example.minimalplayer.data.MusicRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class PlaylistDetailFragment : Fragment() {

    private lateinit var repository: MusicRepository
    private lateinit var adapter: TrackAdapter
    private var playlistId: Long = -1L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_playlist_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.setBackgroundResource(R.color.black)

        repository = MusicRepository(requireContext())
        
        playlistId = arguments?.getLong(ARG_PLAYLIST_ID) ?: -1L
        val playlistName = arguments?.getString(ARG_PLAYLIST_NAME) ?: ""
        
        view.findViewById<TextView>(R.id.tvPlaylistName).text = playlistName
        
        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        
        val tvEmptyState = view.findViewById<TextView>(R.id.tvEmptyState)
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        adapter = TrackAdapter(
            onTrackClick = { track ->
                val mainActivity = requireActivity() as MainActivity
                val currentList = adapter.currentList
                val index = currentList.indexOf(track)
                mainActivity.playMusic(currentList, if (index >= 0) index else 0)
            },
            onTrackLongClick = { track ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Remove Track")
                    .setMessage("Remove '${track.title}' from this playlist?")
                    .setPositiveButton("Remove") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            repository.removeTrackFromPlaylist(playlistId, track.id)
                            refreshTracks(tvEmptyState, recyclerView)
                            Snackbar.make(requireView(), "Removed from playlist", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        recyclerView.adapter = adapter
        
        viewLifecycleOwner.lifecycleScope.launch {
            refreshTracks(tvEmptyState, recyclerView)
        }
    }

    private suspend fun refreshTracks(tvEmptyState: TextView, recyclerView: RecyclerView) {
        val tracks = repository.getTracksForPlaylist(playlistId)
        if (tracks.isEmpty()) {
            tvEmptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            tvEmptyState.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.submitList(tracks)
        }
    }

    companion object {
        private const val ARG_PLAYLIST_ID = "playlist_id"
        private const val ARG_PLAYLIST_NAME = "playlist_name"

        fun newInstance(playlistId: Long, playlistName: String): PlaylistDetailFragment {
            return PlaylistDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_PLAYLIST_ID, playlistId)
                    putString(ARG_PLAYLIST_NAME, playlistName)
                }
            }
        }
    }
}

package com.example.minimalplayer.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.minimalplayer.R
import com.example.minimalplayer.data.MusicRepository
import com.example.minimalplayer.util.FileImportHelper
import com.example.minimalplayer.util.OpenMultipleAudioDocuments
import kotlinx.coroutines.launch

class TracksFragment : Fragment(R.layout.fragment_tracks) {

    private lateinit var repository: MusicRepository
    private lateinit var adapter: TrackAdapter

    private val importLauncher = registerForActivityResult(OpenMultipleAudioDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                FileImportHelper.processAndInsertUris(requireContext(), uris, repository)
                refreshTrackList()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = MusicRepository(requireContext())
        
        adapter = TrackAdapter(
            onTrackClick = { track ->
                val mainActivity = requireActivity() as MainActivity
                val currentList = adapter.currentList
                val index = currentList.indexOf(track)
                mainActivity.playMusic(currentList, if (index >= 0) index else 0)
            },
            onTrackLongClick = { track ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val playlists = repository.getAllPlaylists()
                    if (playlists.isEmpty()) {
                        com.google.android.material.snackbar.Snackbar.make(requireView(), "Create a playlist first!", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                        return@launch
                    }
                    val playlistNames = playlists.map { it.name }.toTypedArray()
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Add to Playlist")
                        .setItems(playlistNames) { _, which ->
                            val selectedPlaylist = playlists[which]
                            viewLifecycleOwner.lifecycleScope.launch {
                                val added = repository.addTrackToPlaylist(selectedPlaylist.id, track.id)
                                val msg = if (added) "Added to ${selectedPlaylist.name}" else "Already in ${selectedPlaylist.name}"
                                com.google.android.material.snackbar.Snackbar.make(requireView(), msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                            }
                        }
                        .show()
                }
            }
        )

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.fabAdd).setOnClickListener {
            importLauncher.launch(Unit)
        }

        (requireActivity() as MainActivity).trackRecoveryListener = {
            lifecycleScope.launch { refreshTrackList() }
        }

        lifecycleScope.launch {
            refreshTrackList()
        }
    }

    private suspend fun refreshTrackList() {
        val tracks = repository.getAllTracks()
        adapter.submitList(tracks)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (requireActivity() as MainActivity).trackRecoveryListener = null
    }
}

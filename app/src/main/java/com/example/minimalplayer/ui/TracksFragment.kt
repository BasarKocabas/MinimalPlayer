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
        
        adapter = TrackAdapter { track ->
            val mainActivity = requireActivity() as MainActivity
            val currentList = adapter.currentList
            val index = currentList.indexOf(track)
            mainActivity.playMusic(currentList, if (index >= 0) index else 0)
        }

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

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
    private lateinit var adapter: GroupedTrackAdapter
    private var playlistId: Long = -1L
    
    private var actionMode: androidx.appcompat.view.ActionMode? = null
    private val selectedTrackIds = mutableSetOf<Long>()

    private val actionModeCallback = object : androidx.appcompat.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: androidx.appcompat.view.ActionMode, menu: android.view.Menu): Boolean {
            (requireActivity() as MainActivity).setSelectionModeEnabled(true)
            view?.findViewById<View>(R.id.btnBack)?.visibility = View.INVISIBLE
            mode.menuInflater.inflate(R.menu.menu_playlist_detail_selection, menu)
            return true
        }
        override fun onPrepareActionMode(mode: androidx.appcompat.view.ActionMode, menu: android.view.Menu) = false
        override fun onActionItemClicked(mode: androidx.appcompat.view.ActionMode, item: android.view.MenuItem): Boolean {
            if (item.itemId == R.id.action_remove_from_playlist) { confirmRemoveTracks(selectedTrackIds.toList()); return true } 
            else if (item.itemId == R.id.action_select_all) {
                selectedTrackIds.addAll(adapter.currentList.filterIsInstance<TrackListItem.TrackItem>().map { it.track.id })
                actionMode?.title = "${selectedTrackIds.size} selected"
                adapter.notifyDataSetChanged()
                return true
            }
            return false
        }
        override fun onDestroyActionMode(mode: androidx.appcompat.view.ActionMode) {
            (requireActivity() as MainActivity).setSelectionModeEnabled(false)
            view?.findViewById<View>(R.id.btnBack)?.visibility = View.VISIBLE
            actionMode = null; selectedTrackIds.clear(); adapter.notifyDataSetChanged()
        }
    }

    private fun toggleSelection(track: com.example.minimalplayer.data.Track) {
        if (!selectedTrackIds.remove(track.id)) selectedTrackIds.add(track.id)
        if (selectedTrackIds.isEmpty()) actionMode?.finish() else actionMode?.title = "${selectedTrackIds.size} selected"
        adapter.notifyDataSetChanged()
    }

    private fun startSelection(track: com.example.minimalplayer.data.Track) {
        if (actionMode == null) actionMode = (requireActivity() as androidx.appcompat.app.AppCompatActivity).startSupportActionMode(actionModeCallback)
        toggleSelection(track)
    }

    private fun confirmRemoveTracks(ids: List<Long>) {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Remove Track(s)")
            .setMessage("Remove ${ids.size} track(s) from this playlist?")
            .setPositiveButton("Remove") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val tvEmptyState = requireView().findViewById<TextView>(R.id.tvEmptyState)
                    val recyclerView = requireView().findViewById<RecyclerView>(R.id.recyclerView)
                    repository.removeTracksFromPlaylist(playlistId, ids)
                    refreshTracks(tvEmptyState, recyclerView)
                    actionMode?.finish()
                    Snackbar.make(requireView(), "Removed from playlist", Snackbar.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = 
        inflater.inflate(R.layout.fragment_playlist_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundResource(R.color.black)
        repository = MusicRepository(requireContext())
        
        playlistId = arguments?.getLong(ARG_PLAYLIST_ID) ?: -1L
        val playlistName = arguments?.getString(ARG_PLAYLIST_NAME) ?: ""
        view.findViewById<TextView>(R.id.tvPlaylistName).text = playlistName
        view.findViewById<ImageButton>(R.id.btnBack).setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        
        val tvEmptyState = view.findViewById<TextView>(R.id.tvEmptyState)
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        adapter = GroupedTrackAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onTrackClick = { track ->
                val mainActivity = requireActivity() as MainActivity
                val currentList = adapter.currentList.filterIsInstance<TrackListItem.TrackItem>().map { it.track }
                val index = currentList.indexOf(track)
                mainActivity.playMusic(currentList, if (index >= 0) index else 0)
            },
            onTrackLongClick = { track -> startSelection(track) },
            onTrackToggleSelect = { track -> toggleSelection(track) },
            isSelected = { id -> selectedTrackIds.contains(id) },
            inSelectionMode = { actionMode != null }
        )
        recyclerView.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch { refreshTracks(tvEmptyState, recyclerView) }
    }

    private suspend fun refreshTracks(tvEmptyState: TextView, recyclerView: RecyclerView) {
        val tracks = repository.getTracksForPlaylist(playlistId)
        if (tracks.isEmpty()) { tvEmptyState.visibility = View.VISIBLE; recyclerView.visibility = View.GONE } 
        else {
            tvEmptyState.visibility = View.GONE; recyclerView.visibility = View.VISIBLE
            adapter.submitList(tracks.map { TrackListItem.TrackItem(it) })
        }
    }

    companion object {
        private const val ARG_PLAYLIST_ID = "playlist_id"
        private const val ARG_PLAYLIST_NAME = "playlist_name"
        fun newInstance(playlistId: Long, playlistName: String): PlaylistDetailFragment = PlaylistDetailFragment().apply {
            arguments = Bundle().apply { putLong(ARG_PLAYLIST_ID, playlistId); putString(ARG_PLAYLIST_NAME, playlistName) }
        }
    }
}

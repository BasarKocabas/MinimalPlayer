package com.example.minimalplayer.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.minimalplayer.R
import com.example.minimalplayer.data.MusicRepository
import com.example.minimalplayer.data.Track
import com.example.minimalplayer.util.FileImportHelper
import com.example.minimalplayer.util.OpenMultipleAudioDocuments
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class TracksFragment : Fragment(R.layout.fragment_tracks) {

    private lateinit var repository: MusicRepository
    private lateinit var adapter: GroupedTrackAdapter
    private lateinit var fastScroller: FastScrollerView
    private lateinit var etSearch: EditText

    private var actionMode: ActionMode? = null
    private val selectedTrackIds = mutableSetOf<Long>()
    private var allTracks: List<Track> = emptyList()

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            (requireActivity() as MainActivity).setSelectionModeEnabled(true)
            view?.findViewById<View>(R.id.fabAdd)?.visibility = View.GONE
            mode.menuInflater.inflate(R.menu.menu_track_selection, menu)
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
            R.id.action_add_to_playlist -> { showAddToPlaylistDialog(selectedTrackIds.toList()); true }
            R.id.action_remove_from_library -> { confirmRemoveTracks(selectedTrackIds.toList()); true }
            R.id.action_select_all -> {
                selectedTrackIds.addAll(adapter.currentList.filterIsInstance<TrackListItem.TrackItem>().map { it.track.id })
                actionMode?.title = "${selectedTrackIds.size} selected"
                adapter.notifyDataSetChanged()
                true
            }
            else -> false
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            (requireActivity() as MainActivity).setSelectionModeEnabled(false)
            view?.findViewById<View>(R.id.fabAdd)?.visibility = View.VISIBLE
            actionMode = null; selectedTrackIds.clear(); adapter.notifyDataSetChanged()
        }
    }

    private fun toggleSelection(track: Track) {
        if (!selectedTrackIds.remove(track.id)) selectedTrackIds.add(track.id)
        if (selectedTrackIds.isEmpty()) actionMode?.finish() else actionMode?.title = "${selectedTrackIds.size} selected"
        adapter.notifyDataSetChanged()
    }

    private fun startSelection(track: Track) {
        if (actionMode == null) actionMode = (requireActivity() as androidx.appcompat.app.AppCompatActivity).startSupportActionMode(actionModeCallback)
        toggleSelection(track)
    }

    private fun showAddToPlaylistDialog(trackIds: List<Long>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val playlists = repository.getAllPlaylists()
            if (playlists.isEmpty()) { Snackbar.make(requireView(), "Create a playlist first!", Snackbar.LENGTH_SHORT).show(); return@launch }
            val playlistNames = playlists.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext()).setTitle("Add to Playlist").setItems(playlistNames) { _, which ->
                val selectedPlaylist = playlists[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    trackIds.forEach { repository.addTrackToPlaylist(selectedPlaylist.id, it) }
                    Snackbar.make(requireView(), "Added to ${selectedPlaylist.name}", Snackbar.LENGTH_SHORT).show()
                    actionMode?.finish()
                }
            }.show()
        }
    }

    private fun confirmRemoveTracks(trackIds: List<Long>) {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Remove from Library")
            .setMessage("Remove ${trackIds.size} track(s)? This won't delete the audio files.")
            .setPositiveButton("Remove") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { repository.deleteTracks(trackIds); refreshTrackList(); actionMode?.finish() }
            }.setNegativeButton("Cancel", null).show()
    }

    private val importLauncher = registerForActivityResult(OpenMultipleAudioDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch { FileImportHelper.processAndInsertUris(requireContext(), uris, repository); refreshTrackList() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = MusicRepository(requireContext())
        etSearch = view.findViewById(R.id.etSearch)
        fastScroller = view.findViewById(R.id.fastScroller)
        
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

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.overScrollMode = View.OVER_SCROLL_NEVER 
        recyclerView.isNestedScrollingEnabled = true
        recyclerView.adapter = adapter
        fastScroller.attach(recyclerView)

        view.findViewById<View>(R.id.fabAdd).setOnClickListener { importLauncher.launch(Unit) }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterTracks(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })

        (requireActivity() as MainActivity).trackRecoveryListener = { lifecycleScope.launch { refreshTrackList() } }
        lifecycleScope.launch { refreshTrackList() }
    }

    private suspend fun refreshTrackList() {
        allTracks = repository.getAllTracks()
        filterTracks(etSearch.text.toString())
    }

    private fun filterTracks(query: String) {
        val filtered = if (query.isEmpty()) allTracks 
        else allTracks.filter { it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) }
        adapter.submitList(buildListItems(filtered))
    }

    private fun buildListItems(tracks: List<Track>): List<TrackListItem> {
        val items = mutableListOf<TrackListItem>()
        val fastScrollerMap = mutableMapOf<Char, Int>()
        var currentLetter: Char? = null
        var currentArtist: String? = null
        
        for (track in tracks) {
            val firstLetter = track.artist.firstOrNull()?.uppercaseChar() ?: '#'
            if (firstLetter != currentLetter) {
                fastScrollerMap[firstLetter] = items.size
                items.add(TrackListItem.LetterHeader(firstLetter))
                currentLetter = firstLetter
                currentArtist = null
            }
            if (track.artist != currentArtist) {
                items.add(TrackListItem.ArtistHeader(track.artist))
                currentArtist = track.artist
            }
            items.add(TrackListItem.TrackItem(track))
        }
        fastScroller.setSections(fastScrollerMap)
        return items
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (requireActivity() as MainActivity).trackRecoveryListener = null
    }
}

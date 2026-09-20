package com.example.minimalplayer.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.minimalplayer.R
import com.example.minimalplayer.data.MusicRepository
import com.example.minimalplayer.data.Playlist
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment(R.layout.fragment_playlists) {

    private lateinit var repository: MusicRepository
    private lateinit var adapter: PlaylistAdapter
    private lateinit var etSearch: EditText

    private var actionMode: ActionMode? = null
    private val selectedPlaylistIds = mutableSetOf<Long>()
    private var allPlaylists: List<Playlist> = emptyList()

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            (requireActivity() as MainActivity).setSelectionModeEnabled(true)
            view?.findViewById<View>(R.id.fabAdd)?.visibility = View.GONE
            mode.menuInflater.inflate(R.menu.menu_playlist_selection, menu)
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == R.id.action_delete_playlists) { confirmDeletePlaylists(selectedPlaylistIds.toList()); return true } 
            else if (item.itemId == R.id.action_select_all) {
                selectedPlaylistIds.addAll(adapter.currentList.map { it.id })
                actionMode?.title = "${selectedPlaylistIds.size} selected"
                adapter.notifyDataSetChanged()
                return true
            }
            return false
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            (requireActivity() as MainActivity).setSelectionModeEnabled(false)
            view?.findViewById<View>(R.id.fabAdd)?.visibility = View.VISIBLE
            actionMode = null; selectedPlaylistIds.clear(); adapter.notifyDataSetChanged()
        }
    }

    private fun toggleSelection(playlist: Playlist) {
        if (!selectedPlaylistIds.remove(playlist.id)) selectedPlaylistIds.add(playlist.id)
        if (selectedPlaylistIds.isEmpty()) actionMode?.finish() else actionMode?.title = "${selectedPlaylistIds.size} selected"
        adapter.notifyDataSetChanged()
    }

    private fun startSelection(playlist: Playlist) {
        if (actionMode == null) actionMode = (requireActivity() as androidx.appcompat.app.AppCompatActivity).startSupportActionMode(actionModeCallback)
        toggleSelection(playlist)
    }

    private fun confirmDeletePlaylists(ids: List<Long>) {
        MaterialAlertDialogBuilder(requireContext()).setTitle("Delete Playlist(s)")
            .setMessage("Delete ${ids.size} playlist(s)? Tracks in them are not deleted.")
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { ids.forEach { repository.deletePlaylist(it) }; loadPlaylists(); actionMode?.finish() }
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = MusicRepository(requireContext())
        etSearch = view.findViewById(R.id.etSearch)
        
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.overScrollMode = View.OVER_SCROLL_ALWAYS
        recyclerView.isNestedScrollingEnabled = true
        
        adapter = PlaylistAdapter(
            onClick = { playlist -> (requireActivity() as MainActivity).showPlaylistDetail(playlist.id, playlist.name) },
            onLongClick = { playlist -> startSelection(playlist) },
            onToggleSelect = { playlist -> toggleSelection(playlist) },
            isSelected = { id -> selectedPlaylistIds.contains(id) },
            inSelectionMode = { actionMode != null }
        )
        recyclerView.adapter = adapter
        view.findViewById<View>(R.id.fabAdd).setOnClickListener { showNewPlaylistDialog() }
        
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterPlaylists(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        loadPlaylists()
    }
    
    private fun loadPlaylists() {
        viewLifecycleOwner.lifecycleScope.launch {
            allPlaylists = repository.getAllPlaylists()
            filterPlaylists(etSearch.text.toString())
        }
    }
    
    private fun filterPlaylists(query: String) {
        val filtered = if (query.isEmpty()) allPlaylists else allPlaylists.filter { it.name.contains(query, ignoreCase = true) }
        adapter.submitList(filtered)
    }
    
    private fun showNewPlaylistDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_playlist, null)
        val tilName = dialogView.findViewById<TextInputLayout>(R.id.tilPlaylistName)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etPlaylistName)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("New Playlist").setView(dialogView)
            .setPositiveButton("Create", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            val button = (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val name = etName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) tilName.error = "Name cannot be empty" else {
                    tilName.error = null
                    viewLifecycleOwner.lifecycleScope.launch { repository.insertPlaylist(name); loadPlaylists(); dialog.dismiss() }
                }
            }
        }
        dialog.show()
    }
}

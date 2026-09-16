package com.example.minimalplayer.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.minimalplayer.R
import com.example.minimalplayer.data.MusicRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment(R.layout.fragment_playlists) {

    private lateinit var repository: MusicRepository
    private lateinit var adapter: PlaylistAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        repository = MusicRepository(requireContext())
        
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        adapter = PlaylistAdapter(
            onDeleteClick = { playlist ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.deletePlaylist(playlist.id)
                    loadPlaylists()
                }
            },
            onClick = { playlist ->
                (requireActivity() as MainActivity).showPlaylistDetail(playlist.id, playlist.name)
            }
        )
        recyclerView.adapter = adapter
        
        view.findViewById<View>(R.id.fabAdd).setOnClickListener {
            showNewPlaylistDialog()
        }
        
        loadPlaylists()
    }
    
    private fun loadPlaylists() {
        viewLifecycleOwner.lifecycleScope.launch {
            val playlists = repository.getAllPlaylists()
            adapter.submitList(playlists)
        }
    }
    
    private fun showNewPlaylistDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_playlist, null)
        val tilName = dialogView.findViewById<TextInputLayout>(R.id.tilPlaylistName)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etPlaylistName)
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("New Playlist")
            .setView(dialogView)
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create()
            
        dialog.setOnShowListener {
            val button = (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val name = etName.text?.toString()?.trim()
                if (name.isNullOrEmpty()) {
                    tilName.error = "Name cannot be empty"
                } else {
                    tilName.error = null
                    viewLifecycleOwner.lifecycleScope.launch {
                        repository.insertPlaylist(name)
                        loadPlaylists()
                        dialog.dismiss()
                    }
                }
            }
        }
        
        dialog.show()
    }
}

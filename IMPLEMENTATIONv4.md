# IMPLEMENTATIONv4 — Card items, stretch scroll, long-press multi-select

## Decisions locked in
- **Item borders/spacing: `MaterialCardView`**, not a hand-rolled shape drawable.
  Already free — `com.google.android.material:material:1.11.0` is already a
  dependency (powers your FAB, TabLayout, AlertDialogs). Zero added bloat,
  and it gives rounded corners + stroke + a ripple that's properly clipped
  to the rounded shape, all as XML attributes instead of custom code.
- **Selection action bar: 3-dot overflow menu**, not a bare trash icon.
  Native `ActionMode`, menu items with `app:showAsAction="never"` — this
  makes AppCompat render them under an automatic overflow (3-dot) icon.
  Zero dependencies either way.
- **No `androidx.recyclerview:recyclerview-selection` library.** Selection
  state is a plain `Set<Long>` held in each Fragment. That library is built
  for far more complex selection UX (mouse band-selection, touch-and-drag)
  than two simple lists need — adding it would be the actual bloat here.

## ⚠️ Conflict found in the existing app — needs your decision before starting

**On the Tracks tab, long-press is already used** — it currently opens an
"Add to Playlist" picker for that one track (`TracksFragment.kt`,
`onTrackLongClick`). Your request to make long-press start multi-select
would silently remove that feature unless we relocate it.

**Recommended resolution (this is what the plan below implements):**
Fold "Add to Playlist" into the Tracks selection action bar as a second
overflow action, alongside a new "Remove from Library" action. Net result:
long-press a track → selection mode starts (with that track already
selected) → pick more tracks → tap the 3-dot menu → "Add to Playlist" now
adds *all* selected tracks at once (an upgrade — bulk add instead of
one-at-a-time), or "Remove from Library" deletes them from the app's
database (does not touch the actual audio files on disk).

This also means the Tracks tab's 3-dot menu will have two real actions from
day one, which is exactly the case where a 3-dot menu earns its keep over a
single trash icon.

**Not touched, by design:** `PlaylistDetailFragment` (the track list *inside*
a playlist) keeps its current long-press → "Remove from this playlist?"
confirmation dialog as-is. That's a different screen with a different job
(remove one track from one playlist), it already works well, and folding it
into the same selection system is a separate task if you want it later —
flagging so it's a deliberate choice, not an oversight.

If you don't want the Add-to-Playlist relocation, say so before the agent
starts — the alternative is leaving long-press-for-Add-to-Playlist as is
and picking a different gesture (e.g. a dedicated "Select" toggle in a
future toolbar) to enter multi-select on Tracks. That's more work for less
payoff, so the fold-in above is the recommended default.

## What changes, file by file

### 1. `app/src/main/res/layout/item_track.xml` — MaterialCardView
```xml
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/cardTrack"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginHorizontal="12dp"
    android:layout_marginVertical="6dp"
    android:clickable="true"
    android:focusable="true"
    app:cardBackgroundColor="@color/black"
    app:cardCornerRadius="12dp"
    app:cardElevation="0dp"
    app:strokeColor="@android:color/white"
    app:strokeWidth="1dp"
    app:rippleColor="@color/accent_blue">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:id="@+id/tvTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="16sp"
            android:textStyle="bold"
            android:ellipsize="end"
            android:maxLines="1" />

        <TextView
            android:id="@+id/tvDuration"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="14sp"
            android:layout_marginTop="4dp" />

    </LinearLayout>

</com.google.android.material.card.MaterialCardView>
```
Note: the old bottom-divider `<View>` is removed — the card border replaces it.

### 2. `app/src/main/res/layout/item_playlist.xml` — MaterialCardView, delete button removed
```xml
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/cardPlaylist"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginHorizontal="12dp"
    android:layout_marginVertical="6dp"
    android:clickable="true"
    android:focusable="true"
    app:cardBackgroundColor="@color/black"
    app:cardCornerRadius="12dp"
    app:cardElevation="0dp"
    app:strokeColor="@android:color/white"
    app:strokeWidth="1dp"
    app:rippleColor="@color/accent_blue">

    <TextView
        android:id="@+id/tvPlaylistName"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="16dp"
        android:textSize="18sp"
        android:textColor="?android:attr/textColorPrimary" />

</com.google.android.material.card.MaterialCardView>
```
`btnDeletePlaylist` (`ImageButton`) is fully removed — deletion moves to the
selection action bar.

### 3. Overscroll stretch — one attribute, two files
Add `android:overScrollMode="always"` to the `RecyclerView` in:
- `app/src/main/res/layout/fragment_tracks.xml`
- `app/src/main/res/layout/fragment_playlists.xml`

This is technically the platform default already, so this is a no-op safety
net rather than a functional change. The actual "stretch" visual is automatic
on API 31+ (your `minSdk` is 24, `compileSdk`/`targetSdk` 34 — devices below
31 get the classic glow edge effect instead, which is the correct graceful
fallback, no extra code needed).

### 4. New menu resources
`app/src/main/res/menu/menu_track_selection.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/action_add_to_playlist"
        android:title="Add to Playlist"
        app:showAsAction="never" />
    <item
        android:id="@+id/action_remove_from_library"
        android:title="Remove from Library"
        app:showAsAction="never" />
</menu>
```

`app/src/main/res/menu/menu_playlist_selection.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/action_delete_playlists"
        android:title="Delete"
        app:showAsAction="never" />
</menu>
```
Both items using `showAsAction="never"` is what makes AppCompat draw the
3-dot overflow icon automatically — no extra icon/drawable needed.

### 5. `MusicRepository.kt` — add bulk track delete
There's currently no way to delete a track from the library at all (only
`markTrackUnavailable`, which is for missing files, not user-initiated
removal). Add:
```kotlin
suspend fun deleteTracks(trackIds: List<Long>) = withContext(Dispatchers.IO) {
    if (trackIds.isEmpty()) return@withContext
    val db = dbHelper.writableDatabase
    val placeholders = trackIds.joinToString(",") { "?" }
    val args = trackIds.map { it.toString() }.toTypedArray()
    db.delete("tracks", "id IN ($placeholders)", args)
    db.delete("playlist_tracks", "track_id IN ($placeholders)", args)
}
```
This only removes the app's database rows (and cleans up any playlist
memberships) — it does not touch the underlying audio file.

### 6. `TrackAdapter.kt` — add selection support, keep it generic
`PlaylistDetailFragment` reuses this adapter for its own long-press dialog,
so selection support must be **opt-in via defaults**, not a breaking change.
```kotlin
class TrackAdapter(
    private val onTrackClick: (Track) -> Unit,
    private val onTrackLongClick: ((Track) -> Unit)? = null,
    private val onTrackToggleSelect: ((Track) -> Unit)? = null,
    private val isSelected: (Long) -> Boolean = { false },
    private val inSelectionMode: () -> Boolean = { false }
) : ListAdapter<Track, TrackAdapter.TrackViewHolder>(TrackDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view, onTrackClick, onTrackLongClick, onTrackToggleSelect, isSelected, inSelectionMode)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class TrackViewHolder(
        itemView: View,
        private val onTrackClick: (Track) -> Unit,
        private val onTrackLongClick: ((Track) -> Unit)?,
        private val onTrackToggleSelect: ((Track) -> Unit)?,
        private val isSelected: (Long) -> Boolean,
        private val inSelectionMode: () -> Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView as com.google.android.material.card.MaterialCardView
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

            val selected = isSelected(track.id)
            card.strokeColor = androidx.core.content.ContextCompat.getColor(
                card.context,
                if (selected) R.color.accent_blue else android.R.color.white
            )
            card.strokeWidth = if (selected) dp(card, 2) else dp(card, 1)

            itemView.setOnClickListener {
                if (inSelectionMode()) onTrackToggleSelect?.invoke(track) else onTrackClick(track)
            }
            itemView.setOnLongClickListener {
                onTrackLongClick?.invoke(track)
                true
            }
        }

        private fun dp(view: View, value: Int): Int =
            (value * view.resources.displayMetrics.density).toInt()
    }

    class TrackDiffCallback : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Track, newItem: Track) = oldItem == newItem
    }
}
```
`PlaylistDetailFragment.kt` needs **no changes** — it doesn't pass the three
new params, so they fall back to their defaults and selection mode is
permanently off there, preserving its existing single-item remove dialog.

### 7. `PlaylistAdapter.kt` — remove delete button, add selection support
```kotlin
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
```
Note the constructor signature changes shape (named params, no more
`onDeleteClick`) — `PlaylistsFragment.kt` must be updated to match (below).

### 8. `TracksFragment.kt` — ActionMode + relocated Add-to-Playlist
Key additions (merge into the existing file, keep `playMusic`/import logic
as-is):
```kotlin
private var actionMode: ActionMode? = null
private val selectedTrackIds = mutableSetOf<Long>()

private val actionModeCallback = object : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.menu_track_selection, menu)
        return true
    }
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_to_playlist -> {
                showAddToPlaylistDialog(selectedTrackIds.toList())
                true
            }
            R.id.action_remove_from_library -> {
                confirmRemoveTracks(selectedTrackIds.toList())
                true
            }
            else -> false
        }
    }
    override fun onDestroyActionMode(mode: ActionMode) {
        actionMode = null
        selectedTrackIds.clear()
        adapter.notifyDataSetChanged()
    }
}

private fun toggleSelection(track: Track) {
    if (!selectedTrackIds.remove(track.id)) selectedTrackIds.add(track.id)
    if (selectedTrackIds.isEmpty()) {
        actionMode?.finish()
    } else {
        actionMode?.title = "${selectedTrackIds.size} selected"
    }
    adapter.notifyDataSetChanged()
}

private fun startSelection(track: Track) {
    if (actionMode == null) {
        actionMode = (requireActivity() as androidx.appcompat.app.AppCompatActivity)
            .startSupportActionMode(actionModeCallback)
    }
    toggleSelection(track)
}

private fun showAddToPlaylistDialog(trackIds: List<Long>) {
    viewLifecycleOwner.lifecycleScope.launch {
        val playlists = repository.getAllPlaylists()
        if (playlists.isEmpty()) {
            Snackbar.make(requireView(), "Create a playlist first!", Snackbar.LENGTH_SHORT).show()
            return@launch
        }
        val playlistNames = playlists.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add to Playlist")
            .setItems(playlistNames) { _, which ->
                val selectedPlaylist = playlists[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    trackIds.forEach { repository.addTrackToPlaylist(selectedPlaylist.id, it) }
                    Snackbar.make(requireView(), "Added to ${selectedPlaylist.name}", Snackbar.LENGTH_SHORT).show()
                    actionMode?.finish()
                }
            }
            .show()
    }
}

private fun confirmRemoveTracks(trackIds: List<Long>) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle("Remove from Library")
        .setMessage("Remove ${trackIds.size} track(s)? This won't delete the audio files.")
        .setPositiveButton("Remove") { _, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                repository.deleteTracks(trackIds)
                refreshTrackList()
                actionMode?.finish()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}
```
Adapter construction changes to:
```kotlin
adapter = TrackAdapter(
    onTrackClick = { track ->
        val mainActivity = requireActivity() as MainActivity
        val currentList = adapter.currentList
        val index = currentList.indexOf(track)
        mainActivity.playMusic(currentList, if (index >= 0) index else 0)
    },
    onTrackLongClick = { track -> startSelection(track) },
    onTrackToggleSelect = { track -> toggleSelection(track) },
    isSelected = { id -> selectedTrackIds.contains(id) },
    inSelectionMode = { actionMode != null }
)
```
The old inline `onTrackLongClick` body (the single-track Add-to-Playlist
dialog) is removed from here — its logic moves into `showAddToPlaylistDialog`
above, now taking a list.

### 9. `PlaylistsFragment.kt` — ActionMode, drop per-row delete
```kotlin
private var actionMode: ActionMode? = null
private val selectedPlaylistIds = mutableSetOf<Long>()

private val actionModeCallback = object : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.menu_playlist_selection, menu)
        return true
    }
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId == R.id.action_delete_playlists) {
            confirmDeletePlaylists(selectedPlaylistIds.toList())
            return true
        }
        return false
    }
    override fun onDestroyActionMode(mode: ActionMode) {
        actionMode = null
        selectedPlaylistIds.clear()
        adapter.notifyDataSetChanged()
    }
}

private fun toggleSelection(playlist: Playlist) {
    if (!selectedPlaylistIds.remove(playlist.id)) selectedPlaylistIds.add(playlist.id)
    if (selectedPlaylistIds.isEmpty()) {
        actionMode?.finish()
    } else {
        actionMode?.title = "${selectedPlaylistIds.size} selected"
    }
    adapter.notifyDataSetChanged()
}

private fun startSelection(playlist: Playlist) {
    if (actionMode == null) {
        actionMode = (requireActivity() as androidx.appcompat.app.AppCompatActivity)
            .startSupportActionMode(actionModeCallback)
    }
    toggleSelection(playlist)
}

private fun confirmDeletePlaylists(ids: List<Long>) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle("Delete Playlist(s)")
        .setMessage("Delete ${ids.size} playlist(s)? Tracks in them are not deleted.")
        .setPositiveButton("Delete") { _, _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                ids.forEach { repository.deletePlaylist(it) }
                loadPlaylists()
                actionMode?.finish()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}
```
Adapter construction changes to:
```kotlin
adapter = PlaylistAdapter(
    onClick = { playlist ->
        (requireActivity() as MainActivity).showPlaylistDetail(playlist.id, playlist.name)
    },
    onLongClick = { playlist -> startSelection(playlist) },
    onToggleSelect = { playlist -> toggleSelection(playlist) },
    isSelected = { id -> selectedPlaylistIds.contains(id) },
    inSelectionMode = { actionMode != null }
)
```

## Imports the agent will need to add
`ActionMode`, `Menu`, `MenuItem` from `android.view.*` (or
`androidx.appcompat.view.ActionMode` — use whichever resolves cleanly given
`AppCompatActivity.startSupportActionMode` returns
`androidx.appcompat.view.ActionMode`, not the platform `android.view.ActionMode`)
in both fragments; `Snackbar` in `TracksFragment.kt` (already used, just
confirm the import is present since the logic moved into a new function).

## Verification steps for the agent
1. `grep -n "btnDeletePlaylist" -r app/src/main/java app/src/main/res` should
   return nothing once done — confirms the old delete button and its wiring
   are fully removed, not just hidden.
2. `grep -n "onDeleteClick" -r app/src/main/java` should return nothing.
3. `grep -rn "MaterialCardView" app/src/main/res/layout` should show both
   `item_track.xml` and `item_playlist.xml`.
4. `./gradlew assembleDebug` builds clean.
5. Manual check once installed: long-press a track → CAB appears with a
   3-dot icon → tap another track → count updates → 3-dot → both actions
   present. Same for playlists, minus Add-to-Playlist. Confirm
   `PlaylistDetailFragment`'s long-press-to-remove-from-playlist still works
   unchanged.

Only start implementing once you've confirmed the Add-to-Playlist
relocation above is acceptable.

Here is the final, consolidated **v7.0** implementation blueprint. It merges the rock-solid backend architecture with the polished OLED UI, strict lifecycle management, and zero-polling playback rules. 

You can save this directly as your `IMPLEMENTATION.md` file and feed it to your CLI agent.

***

```markdown
# 🎵 Minimal Android Music Player - Implementation Blueprint (v7.0 Final)

## 1. Core Philosophy & Architecture Rules
- **Ultra-Lightweight:** Optimize for minimal RAM/CPU footprint and small APK size. No bloated dependency graphs.
- **Zero Background Scanning:** The app performs **zero** background indexing and **zero** `MediaStore` queries. 
- **Strict Manual Curation:** A song only exists in the app if the user explicitly selects it via the native SAF picker.
- **Native Android:** No cross-platform frameworks (Flutter/React Native) and **no Jetpack Compose**. Use native XML Views.
- **Strict Threading & Lifecycle Safety:** All disk I/O must occur on `Dispatchers.IO`. Fragment UI coroutines **must** use `viewLifecycleOwner.lifecycleScope` to prevent memory leaks and `NullPointerException`s when views are destroyed.
- **Zero Polling:** The UI must react to Media3 state callbacks. No `Handler`, `Timer`, or `while(isActive)` loops to track playback position or state.

## 2. Technology Stack & Dependencies
- **Language:** Kotlin
- **UI:** Native Android XML Views (`RecyclerView`, `ViewPager2`, `TabLayout`, `MaterialAlertDialogBuilder`).
- **Icons:** Vector Drawables (XML) for crisp, scalable, zero-dependency media controls.
- **Database:** Plain `SQLiteOpenHelper` (Raw SQL, no Room ORM).
- **Threading:** Kotlin Coroutines (`Dispatchers.IO`).
- **Playback Engine:** Jetpack Media3 (ExoPlayer + `MediaSessionService`).
- **File Access:** Storage Access Framework (`ACTION_OPEN_DOCUMENT`).

### Required Gradle Dependencies
*Note: Always pull the actual current stable versions from Maven/Google release notes before building (e.g., Media3 1.11.0+, Coroutines 1.11.0+).*
```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0") // For Material Dialogs & TextInputLayouts

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    // Note: lifecycle-viewmodel-ktx is intentionally omitted to reduce bloat.

    val media3Version = "1.3.1" // Update to latest stable
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
}
```

## 3. UI & Theme Strategy: "Polished OLED"
To achieve a premium, modern look while saving battery on OLED screens, we use a strict, high-contrast color palette and lightweight vectors.

### Color Palette (`res/values/colors.xml`)
```xml
<resources>
    <color name="pure_black">#000000</color>       <!-- Main Background (Pixels off) -->
    <color name="surface_elevated">#0A0A0A</color>  <!-- Mini-Player -->
    <color name="divider">#1A1A1A</color>           <!-- Subtle List Dividers -->
    <color name="text_primary">#FFFFFF</color>
    <color name="text_secondary">#888888</color>
    <color name="accent">#1DB954</color>            <!-- Minimal Accent -->
</resources>
```

### Theme (`res/values/themes.xml`)
Fixed black theme (no `DayNight` switching).
```xml
<resources>
    <style name="Theme.MinimalPlayer" parent="Theme.MaterialComponents.NoActionBar">
        <item name="android:colorBackground">@color/pure_black</item>
        <item name="colorSurface">@color/pure_black</item>
        <item name="colorPrimary">@color/accent</item>
        <item name="android:textColorPrimary">@color/text_primary</item>
        <item name="android:textColorSecondary">@color/text_secondary</item>
        <item name="android:statusBarColor">@color/pure_black</item>
        <item name="android:navigationBarColor">@color/pure_black</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>
</resources>
```

## 4. Data Layer (SQLite & Kotlin Models)

### Data Models
```kotlin
data class Track(
    val id: Long = 0, val uri: String, val title: String,
    val durationMs: Long? = null, val isAvailable: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)
data class Playlist(val id: Long = 0, val name: String)
```

### SQLite Schema
```sql
PRAGMA foreign_keys = ON;
CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT, uri TEXT UNIQUE NOT NULL, title TEXT NOT NULL, duration_ms INTEGER, is_available INTEGER NOT NULL DEFAULT 1, added_at INTEGER NOT NULL);
CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL);
CREATE TABLE playlist_tracks (playlist_id INTEGER NOT NULL, track_id INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY (playlist_id, track_id), FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE, FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE);
CREATE INDEX idx_playlist_tracks_playlist ON playlist_tracks(playlist_id, position);
```

### Repository (Strict `Dispatchers.IO`)
All methods must be `suspend` functions.
```kotlin
class MusicRepository(context: Context) {
    private val dbHelper = MusicDatabaseHelper(context)

    // Example: Insert Playlist
    suspend fun insertPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put("name", name) }
        db.insert("playlists", null, values)
    }

    // Example: Get All Playlists
    suspend fun getAllPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val playlists = mutableListOf<Playlist>()
        dbHelper.readableDatabase.query("playlists", null, null, null, null, null, "name ASC").use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                playlists.add(Playlist(cursor.getLong(idIdx), cursor.getString(nameIdx)))
            }
        }
        playlists
    }
    // ... implement getAllTracks, insertTrack, addTrackToPlaylist, etc.
}
```

## 5. Playlist Feature (End-to-End)
- **Dialog:** Use `MaterialAlertDialogBuilder` with a custom XML layout containing `TextInputLayout` and `TextInputEditText`.
- **Validation:** If the user taps "Create" with an empty name, set an error on the `TextInputLayout` but **do not dismiss the dialog**.
- **Lifecycle:** Trigger the DB insert and list refresh using `viewLifecycleOwner.lifecycleScope.launch`.
- **Adapter:** Use `ListAdapter` + `DiffUtil.ItemCallback` for the `RecyclerView` to ensure smooth, jank-free rendering.

## 6. Playback Engine & Mini-Player Controls

### Media3 Service (`PlaybackService.kt`)
Extends `MediaSessionService`. Handles Audio Focus (`handleAudioFocus = true`) and Becoming Noisy (`setHandleAudioBecomingNoisy(true)`).

### Mini-Player Controls (No Polling)
- **Icons:** Use Vector XML (`ic_skip_previous`, `ic_play`, `ic_pause`, `ic_skip_next`).
- **Previous Button Logic:** 
  ```kotlin
  if (controller.currentPosition > 3000L) controller.seekTo(0) 
  else controller.seekToPreviousMediaItem()
  ```
  *Keep the button enabled as long as a media item exists. Do not poll the position to toggle its enabled state.*
- **Next Button Logic:** `controller.seekToNextMediaItem()`
- **State Sync:** Update the UI strictly via `Player.Listener` callbacks:
  - `onMediaItemTransition`: Update track title.
  - `onIsPlayingChanged`: Swap Play/Pause vector icon.
  - `onPlaybackStateChanged`: Handle buffering/ready states.

### Stale URI Guard
Catch `ERROR_CODE_IO_FILE_NOT_FOUND` or `ERROR_CODE_IO_NO_PERMISSION`. Mark the track as `is_available = 0` in the DB, dim it in the UI, and skip to the next track. Use a `consecutiveErrors` counter (max 3) to prevent infinite skip loops.

## 7. Full System Architecture Diagram

```text
                    ┌──────────────────────┐
                    │     MainActivity     │
                    └──────────┬───────────┘
                               │
      ┌────────────────────────┼────────────────────────┐
      │                        │                        │
TracksFragment          PlaylistsFragment         Mini-Player UI
(viewLifecycle)         (viewLifecycle)           (Media3 Callbacks)
      │                        │                        │
      └────────────────────────┴────────────────────────┘
                               │ (Coroutines / Dispatchers.IO)
                         MusicRepository
                               │
              ┌────────────────┼────────────────┐
              │                │                │
            tracks         playlists      playlist_tracks
              ▲                ▲                ▲
              │                │                │
  ACTION_OPEN_DOCUMENT         │          MaterialAlertDialog
  (SAF / Vectors)              │          (TextInputLayout)
              │                │                │
              ▼                │                │
       Persisted URIs          │                │
              │                │                │
              └────────────────┴────────────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │ MediaSessionService  │
                    │      (Media3)        │
                    │     ExoPlayer        │
                    └──────────┬───────────┘
                               │
                    Notification / BT / Lock Screen
```

## 8. Implementation Order
1. **Gradle & Manifest:** Setup dependencies, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `PlaybackService` declaration.
2. **Database & Repository:** Implement `MusicDatabaseHelper`, Models, and raw SQL `MusicRepository` methods.
3. **Playlist Backend & UI:** Implement Playlist CRUD, `PlaylistAdapter`, and the `MaterialAlertDialog` creation flow.
4. **OLED Theme:** Apply `colors.xml`, `themes.xml`, and pure black backgrounds to all Fragment roots.
5. **Mini-Player & Vectors:** Add Vector XML icons and wire Previous/Play-Pause/Next click listeners.
6. **Media3 Sync:** Implement `Player.Listener` callbacks to update the Mini-Player without polling.
7. **Lifecycle Audit:** Ensure all Fragment coroutines use `viewLifecycleOwner.lifecycleScope`.
```

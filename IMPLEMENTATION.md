```markdown
# 🎵 Minimal Android Music Player - Implementation Blueprint (v5.0 Final)

## 1. Core Philosophy & Architecture Rules
- **Ultra-Lightweight:** Optimize for minimal RAM/CPU footprint and small APK size. No bloated dependency graphs.
- **Zero Background Scanning:** The app performs **zero** background indexing and **zero** `MediaStore` queries. 
- **Strict Manual Curation (V1):** A song only exists in the app if the user explicitly selects the individual file via the native picker. *(Folder/Tree selection is deferred to V2 to avoid the "directory walking" scanning paradox).*
- **Native Android:** No cross-platform frameworks (Flutter/React Native) to avoid engine/runtime overhead.
- **Strict Threading:** All disk I/O and database operations must occur off the Main Thread to prevent UI jank and ANRs.

## 2. Technology Stack & Dependencies
- **Language:** **Kotlin** (Modern Android standard, concise, idiomatic).
- **UI:** **Native Android XML Views** (`RecyclerView`, `ViewPager2`, `TabLayout`). 
- **Database:** **Plain `SQLiteOpenHelper`** (No Room ORM; raw SQL for absolute minimal dependency overhead).
- **Threading:** **Kotlin Coroutines** (`Dispatchers.IO`) for lightweight, non-blocking background database operations.
- **Playback Engine:** **Jetpack Media3 (ExoPlayer)** (For native `MediaSession`, audio focus, and auto-advance).
- **File Access:** Storage Access Framework (`ACTION_OPEN_DOCUMENT`).

### Required Gradle Dependencies
```kotlin
dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Kotlin Coroutines & Lifecycle (for Dispatchers.IO and lifecycleScope)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Jetpack Media3 (ExoPlayer & MediaSession)
    val media3Version = "1.3.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
}
```

## 3. Data Layer (SQLite & Kotlin Models)

### Data Models
```kotlin
data class Track(
    val id: Long = 0,
    val uri: String,
    val title: String,
    val durationMs: Long? = null, // Populated lazily upon first playback
    val isAvailable: Boolean = true, // Tracks if the file still exists/permissions are valid
    val addedAt: Long = System.currentTimeMillis()
)

data class Playlist(
    val id: Long = 0,
    val name: String
)
```

### SQLite Schema
Strict enforcement of foreign keys, indexing, and the `is_available` state flag.

```sql
PRAGMA foreign_keys = ON;

CREATE TABLE tracks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uri TEXT UNIQUE NOT NULL,
    title TEXT NOT NULL,
    duration_ms INTEGER,
    is_available INTEGER NOT NULL DEFAULT 1,
    added_at INTEGER NOT NULL
);

CREATE TABLE playlists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL
);

CREATE TABLE playlist_tracks (
    playlist_id INTEGER NOT NULL,
    track_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (playlist_id, track_id),
    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
    FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
);

CREATE INDEX idx_playlist_tracks_playlist ON playlist_tracks(playlist_id, position);
CREATE INDEX idx_playlist_tracks_track ON playlist_tracks(track_id);
```

### Repository (DAO Layer with Threading & Conflict Resolution)
All database operations are `suspend` functions running on `Dispatchers.IO` to ensure the UI thread is never blocked. The `insertTrack` method includes a fallback to fetch the existing ID if a duplicate URI is picked.

```kotlin
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.getLongOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicRepository(context: Context) {
    private val dbHelper = MusicDatabaseHelper(context)

    suspend fun insertTrack(uri: String, title: String): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("uri", uri)
            put("title", title)
            put("added_at", System.currentTimeMillis())
        }
        
        val insertedId = db.insertWithOnConflict("tracks", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        
        if (insertedId == -1L) {
            // Conflict: URI already exists. Fetch and return the existing ID.
            db.query("tracks", arrayOf("id"), "uri = ?", arrayOf(uri), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            }
        } else {
            insertedId
        }
    }

    suspend fun markTrackUnavailable(trackId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put("is_available", 0) }
        db.update("tracks", values, "id = ?", arrayOf(trackId.toString()))
    }

    suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val db = dbHelper.readableDatabase
        db.query("tracks", null, null, null, null, null, "added_at DESC").use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val uriIdx = cursor.getColumnIndexOrThrow("uri")
            val titleIdx = cursor.getColumnIndexOrThrow("title")
            val durIdx = cursor.getColumnIndexOrThrow("duration_ms")
            val availIdx = cursor.getColumnIndexOrThrow("is_available")
            val addedIdx = cursor.getColumnIndexOrThrow("added_at")
            
            while (cursor.moveToNext()) {
                tracks.add(Track(
                    id = cursor.getLong(idIdx),
                    uri = cursor.getString(uriIdx),
                    title = cursor.getString(titleIdx),
                    durationMs = cursor.getLongOrNull(durIdx),
                    isAvailable = cursor.getInt(availIdx) == 1,
                    addedAt = cursor.getLong(addedIdx)
                ))
            }
        }
        return@withContext tracks
    }
    
    // Playlist CRUD and Junction Table methods follow this exact same raw SQL + Coroutines pattern...
}
```

## 4. File Picking & Storage Strategy
- **V1 Mechanism:** `Intent.ACTION_OPEN_DOCUMENT` with `type = "audio/*"` and `EXTRA_ALLOW_MULTIPLE`.
- **Permissions:** Immediately call `contentResolver.takePersistableUriPermission()` with `FLAG_GRANT_READ_URI_PERMISSION`. 
- **Metadata:** Only extract `OpenableColumns.DISPLAY_NAME` on import. Defer `duration_ms` and ID3 tag extraction until the track is actually prepared for playback to save processing time.

## 5. Playback Engine (Media3 & ExoPlayer)

By extending `MediaSessionService`, we get background playback, lock-screen controls, and Bluetooth integration natively. 

### `PlaybackService.kt`
```kotlin
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // handleAudioFocus = true (Auto-pauses for calls/notifications)
            )
            .setHandleAudioBecomingNoisy(true) // Pauses if headphones are unplugged
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
```

### UI Integration & Stale URI Guard
The UI uses a `MediaController` proxy to send commands. To prevent infinite skip loops when multiple tracks are missing, we implement a consecutive error guard and update the database availability state.

```kotlin
private var consecutiveErrors = 0
private val MAX_CONSECUTIVE_ERRORS = 3

// Note: This function must be called from within a LifecycleOwner (Activity/Fragment) 
// so that lifecycleScope is available.
private fun setupPlayerListener(controller: MediaController, repository: MusicRepository) {
    controller.addListener(object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || 
                error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) {
                
                // 1. Mark track as unavailable in DB
                val failedMediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
                failedMediaId?.let { 
                    lifecycleScope.launch { repository.markTrackUnavailable(it) }
                }
                
                // 2. Guard against infinite loop
                consecutiveErrors++
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    controller.stop()
                    // Show UI: "Playback stopped: Too many unavailable tracks."
                    return
                }

                // 3. Skip to next if available
                if (controller.hasNextMediaItem()) {
                    controller.seekToNextMediaItem()
                    controller.prepare()
                    controller.play()
                } else {
                    controller.stop()
                    // Show UI: "End of queue reached, some tracks were unavailable."
                }
            }
        }
        
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveErrors = 0 // Reset counter on successful playback
            }
        }
    })
}

private fun playMusic(controller: MediaController, tracksToPlay: List<Track>, startIndex: Int = 0) {
    val mediaItems = tracksToPlay.map { track ->
        MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(track.uri) // The SAF content:// URI
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).build())
            .build()
    }

    controller.setMediaItems(mediaItems, startIndex, 0L)
    controller.prepare()
    controller.play()
}
```

## 6. UX / Interaction Rules
- **Delete Track (from App):** Removes the track from the `tracks` table and cascades to remove it from all `playlist_tracks`. **It does NOT delete the actual audio file on the device.**
- **Remove Track (from Playlist):** Deletes only the specific row in the `playlist_tracks` junction table. The track remains in the main library.
- **Unavailable Tracks:** Tracks flagged with `is_available = 0` are visually dimmed or marked in the UI, preventing the user from trying to play them again until the file is restored or re-added.
- **Power Management:** No `WifiLock` (local files only). No manual `WakeLock` (Media3 handles CPU wake states automatically).

## 7. Full System Architecture Diagram

```text
                    ┌──────────────────────┐
                    │     MainActivity     │
                    └──────────┬───────────┘
                               │
                ┌──────────────┴──────────────┐
                │                             │
          TracksFragment               PlaylistsFragment
                │                             │
                └──────────────┬──────────────┘
                               │ (Coroutines / Dispatchers.IO)
                         MusicRepository
                               │
              ┌────────────────┼────────────────┐
              │                │                │
            tracks         playlists      playlist_tracks
              ▲                ▲                ▲
              │                │                │
  ACTION_OPEN_DOCUMENT         │          UI Playlist
  (Individual Files)           │          Management
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
                    │          │           │
                    │     ExoPlayer        │
                    │          │           │
                    │     MediaSession     │
                    └──────────┬───────────┘
                               │
                    Notification / BT /
                    lock screen / headset
```

## 8. Implementation Order
To ensure a stable build process, follow this strict sequence:
1. **Gradle/Project Setup:** Add Media3 and Coroutines dependencies.
2. **Android Manifest:** Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` permissions, and register `PlaybackService` with `foregroundServiceType="mediaPlayback"`.
3. **SQLite Helper & Schema:** Implement `MusicDatabaseHelper`.
4. **Repository Layer:** Implement `MusicRepository` with Coroutines.
5. **File Picker/Import:** Wire up `ACTION_OPEN_DOCUMENT` and SAF permissions.
6. **Tracks UI:** Build XML layouts, `RecyclerView`, and `TrackAdapter`.
7. **Playlists UI:** Build playlist creation and junction table management.
8. **Media3 Service:** Implement `PlaybackService` and `MediaController` binding.
9. **Mini-Player:** Implement the persistent bottom UI component.
10. **Edge Cases/Testing:** Test stale URIs, large libraries, and audio focus interruptions.

## 9. Known Open Items (For Future Iterations)
- **Availability Recovery:** Currently, there is no path back from `is_available = 0` to `1`. We need to decide whether tapping an unavailable track should trigger a retry (clearing the flag on success) or if re-adding the file via the picker is the only supported recovery path.
- **Coroutine Scope Context:** The `setupPlayerListener` example uses `lifecycleScope.launch`. In the actual implementation, ensure this code lives inside a `LifecycleOwner` (like the hosting `Activity` or `Fragment`), or pass a specific `CoroutineScope` to the listener setup.
```

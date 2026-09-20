package com.example.minimalplayer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.getLongOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicRepository(context: Context) {
    private val dbHelper = MusicDatabaseHelper(context)

    suspend fun insertTrack(uri: String, title: String, artist: String = "Unknown Artist"): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("uri", uri)
            put("title", title)
            put("artist", artist)
            put("added_at", System.currentTimeMillis())
        }
        
        val insertedId = db.insertWithOnConflict("tracks", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        
        if (insertedId == -1L) {
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

    suspend fun markTrackAvailable(trackId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put("is_available", 1) }
        db.update("tracks", values, "id = ?", arrayOf(trackId.toString()))
    }

    suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val db = dbHelper.readableDatabase
        // Sorted in SQL for maximum performance, avoiding Kotlin-side sorting on every search keystroke
        db.query("tracks", null, null, null, null, null, "artist COLLATE NOCASE ASC, title COLLATE NOCASE ASC").use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val uriIdx = cursor.getColumnIndexOrThrow("uri")
            val titleIdx = cursor.getColumnIndexOrThrow("title")
            val artistIdx = cursor.getColumnIndexOrThrow("artist")
            val durIdx = cursor.getColumnIndexOrThrow("duration_ms")
            val availIdx = cursor.getColumnIndexOrThrow("is_available")
            val addedIdx = cursor.getColumnIndexOrThrow("added_at")
            
            while (cursor.moveToNext()) {
                tracks.add(Track(
                    id = cursor.getLong(idIdx),
                    uri = cursor.getString(uriIdx),
                    title = cursor.getString(titleIdx),
                    artist = cursor.getString(artistIdx),
                    durationMs = cursor.getLongOrNull(durIdx),
                    isAvailable = cursor.getInt(availIdx) == 1,
                    addedAt = cursor.getLong(addedIdx)
                ))
            }
        }
        return@withContext tracks
    }

    suspend fun insertPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put("name", name) }
        db.insert("playlists", null, values)
    }

    suspend fun getAllPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val playlists = mutableListOf<Playlist>()
        val db = dbHelper.readableDatabase
        db.query("playlists", null, null, null, null, null, "name ASC").use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                playlists.add(Playlist(id = cursor.getLong(idIdx), name = cursor.getString(nameIdx)))
            }
        }
        return@withContext playlists
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("playlists", "id = ?", arrayOf(playlistId.toString()))
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long): Boolean = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        var nextPosition = 0
        db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = ?", arrayOf(playlistId.toString())).use { cursor ->
            if (cursor.moveToFirst()) nextPosition = cursor.getInt(0)
        }
        val values = ContentValues().apply {
            put("playlist_id", playlistId)
            put("track_id", trackId)
            put("position", nextPosition)
        }
        val result = db.insertWithOnConflict("playlist_tracks", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return@withContext result != -1L
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("playlist_tracks", "playlist_id = ? AND track_id = ?", arrayOf(playlistId.toString(), trackId.toString()))
    }

    suspend fun getTracksForPlaylist(playlistId: Long): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val db = dbHelper.readableDatabase
        val query = """
            SELECT t.* FROM tracks t 
            INNER JOIN playlist_tracks pt ON t.id = pt.track_id 
            WHERE pt.playlist_id = ? ORDER BY pt.position ASC
        """.trimIndent()
        db.rawQuery(query, arrayOf(playlistId.toString())).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val uriIdx = cursor.getColumnIndexOrThrow("uri")
            val titleIdx = cursor.getColumnIndexOrThrow("title")
            val artistIdx = cursor.getColumnIndexOrThrow("artist")
            val durIdx = cursor.getColumnIndexOrThrow("duration_ms")
            val availIdx = cursor.getColumnIndexOrThrow("is_available")
            val addedIdx = cursor.getColumnIndexOrThrow("added_at")
            while (cursor.moveToNext()) {
                tracks.add(Track(
                    id = cursor.getLong(idIdx), uri = cursor.getString(uriIdx), title = cursor.getString(titleIdx),
                    artist = cursor.getString(artistIdx), durationMs = cursor.getLongOrNull(durIdx),
                    isAvailable = cursor.getInt(availIdx) == 1, addedAt = cursor.getLong(addedIdx)
                ))
            }
        }
        return@withContext tracks
    }

    suspend fun removeTracksFromPlaylist(playlistId: Long, trackIds: List<Long>) = withContext(Dispatchers.IO) {
        if (trackIds.isEmpty()) return@withContext
        val db = dbHelper.writableDatabase
        val placeholders = trackIds.joinToString(",") { "?" }
        val args = listOf(playlistId.toString()) + trackIds.map { it.toString() }
        db.delete("playlist_tracks", "playlist_id = ? AND track_id IN ($placeholders)", args.toTypedArray())
    }

    suspend fun deleteTracks(trackIds: List<Long>) = withContext(Dispatchers.IO) {
        if (trackIds.isEmpty()) return@withContext
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            trackIds.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                db.delete("tracks", "id IN ($placeholders)", args)
                db.delete("playlist_tracks", "track_id IN ($placeholders)", args)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    suspend fun updateTrackDuration(trackId: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put("duration_ms", durationMs) }
        db.update("tracks", values, "id = ?", arrayOf(trackId.toString()))
    }
}

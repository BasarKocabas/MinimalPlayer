package com.example.minimalplayer.data

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
}

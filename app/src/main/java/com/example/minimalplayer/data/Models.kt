package com.example.minimalplayer.data

data class Track(
    val id: Long = 0,
    val uri: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val durationMs: Long? = null,
    val isAvailable: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)

data class Playlist(
    val id: Long = 0,
    val name: String
)

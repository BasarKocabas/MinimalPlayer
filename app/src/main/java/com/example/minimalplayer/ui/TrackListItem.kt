package com.example.minimalplayer.ui

import com.example.minimalplayer.data.Track

sealed class TrackListItem {
    data class LetterHeader(val letter: Char) : TrackListItem()
    data class ArtistHeader(val artist: String) : TrackListItem()
    data class TrackItem(val track: Track) : TrackListItem()
}

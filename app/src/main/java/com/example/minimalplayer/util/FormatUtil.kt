package com.example.minimalplayer.util

object FormatUtil {
    fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}

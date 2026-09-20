package com.example.minimalplayer.util

import android.graphics.Bitmap
import android.util.LruCache

object AlbumArtCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()

    // Small thumbnails (list rows, mini player) — ~128px each
    private val smallCacheSize = maxMemory / 8
    val lruCache = object : LruCache<Long, Bitmap>(smallCacheSize) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    // Larger art for the extended player — ~512px each
    private val largeCacheSize = maxMemory / 16
    val largeLruCache = object : LruCache<Long, Bitmap>(largeCacheSize) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }
}

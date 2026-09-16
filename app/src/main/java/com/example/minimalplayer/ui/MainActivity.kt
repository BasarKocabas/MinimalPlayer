package com.example.minimalplayer.ui

import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.minimalplayer.R
import com.example.minimalplayer.data.MusicRepository
import com.example.minimalplayer.data.Track
import com.example.minimalplayer.service.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(R.layout.activity_main) {

    private lateinit var repository: MusicRepository
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var mediaController: MediaController? = null
        private set

    private var consecutiveErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 3

    var trackRecoveryListener: (() -> Unit)? = null

    private lateinit var miniPlayerContainer: FrameLayout
    private lateinit var tvMiniPlayerTitle: TextView
    private lateinit var btnPlayPause: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MusicRepository(this)
        
        val viewPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)

        viewPager.adapter = MainPagerAdapter(this)

        com.google.android.material.tabs.TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Tracks"
                1 -> "Playlists"
                else -> ""
            }
        }.attach()

        miniPlayerContainer = findViewById(R.id.miniPlayerContainer)
        val miniPlayerView = layoutInflater.inflate(R.layout.view_mini_player, miniPlayerContainer, true)
        tvMiniPlayerTitle = miniPlayerView.findViewById(R.id.tvMiniPlayerTitle)
        btnPlayPause = miniPlayerView.findViewById(R.id.btnPlayPause)

        btnPlayPause.setOnClickListener {
            mediaController?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            mediaController?.let { setupPlayerListener(it) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupPlayerListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMiniPlayerUI(controller)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMiniPlayerUI(controller)
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMiniPlayerUI(controller)
                
                if (playbackState == Player.STATE_READY) {
                    consecutiveErrors = 0 
                    
                    val extras = controller.currentMediaItem?.mediaMetadata?.extras
                    val wasAvailable = extras?.getBoolean("isAvailable") ?: true
                    
                    if (!wasAvailable) {
                        val currentMediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
                        currentMediaId?.let { id ->
                            lifecycleScope.launch {
                                repository.markTrackAvailable(id)
                                extras?.putBoolean("isAvailable", true)
                                trackRecoveryListener?.invoke()
                            }
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || 
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) {
                    
                    val failedMediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
                    failedMediaId?.let { 
                        lifecycleScope.launch { repository.markTrackUnavailable(it) }
                    }
                    
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        controller.stop()
                        Toast.makeText(this@MainActivity, "Playback stopped: Too many unavailable tracks.", Toast.LENGTH_LONG).show()
                        return
                    }

                    if (controller.hasNextMediaItem()) {
                        controller.seekToNextMediaItem()
                        controller.prepare()
                        controller.play()
                    } else {
                        controller.stop()
                        Toast.makeText(this@MainActivity, "End of queue reached. Some tracks were unavailable.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun updateMiniPlayerUI(controller: MediaController) {
        if (controller.currentMediaItem == null) {
            miniPlayerContainer.visibility = View.GONE
            return
        }
        
        miniPlayerContainer.visibility = View.VISIBLE
        tvMiniPlayerTitle.text = controller.currentMediaItem?.mediaMetadata?.title ?: "Unknown Track"
        
        val icon = if (controller.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        btnPlayPause.setImageResource(icon)
    }

    fun playMusic(tracksToPlay: List<Track>, startIndex: Int = 0) {
        val controller = mediaController ?: return
        
        val mediaItems = tracksToPlay.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setExtras(Bundle().apply { putBoolean("isAvailable", track.isAvailable) })
                        .build()
                )
                .build()
        }

        controller.setMediaItems(mediaItems, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

package com.example.minimalplayer.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
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
import com.example.minimalplayer.util.AlbumArtCache
import com.example.minimalplayer.util.BitmapUtil
import com.example.minimalplayer.util.FormatUtil
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(R.layout.activity_main) {

    private lateinit var repository: MusicRepository
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var mediaController: MediaController? = null
        private set

    private var consecutiveErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 3
    var trackRecoveryListener: (() -> Unit)? = null

    // Mini player views
    private lateinit var miniPlayerContainer: FrameLayout
    private lateinit var tvMiniPlayerTitle: TextView
    private lateinit var tvMiniPlayerTime: TextView
    private lateinit var ivMiniAlbumArt: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton

    // Extended player views
    private lateinit var extendedPlayerContainer: FrameLayout
    private lateinit var ivExtendedAlbumArt: ImageView
    private lateinit var tvExtendedTitle: TextView
    private lateinit var tvExtendedArtist: TextView
    private lateinit var seekBarExtended: SeekBar
    private lateinit var tvExtendedCurrentTime: TextView
    private lateinit var tvExtendedTotalTime: TextView
    private lateinit var btnExtendedPlayPause: ImageButton
    private lateinit var btnExtendedRepeat: ImageButton
    private var isExtendedPlayerVisible = false

    private var repeatOne = false
    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            val controller = mediaController
            if (controller != null && controller.isPlaying && !isUserSeeking) {
                updateSeekBarProgress(controller)
                if (isExtendedPlayerVisible) updateExtendedSeekBarProgress(controller)
            }
            handler.postDelayed(this, 500)
        }
    }

    private var currentTrackUri: String? = null
    private var albumArtJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MusicRepository(this)
        
        val viewPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)
        viewPager.adapter = MainPagerAdapter(this)

        com.google.android.material.tabs.TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                0 -> { tab.text = "Tracks"; tab.setIcon(R.drawable.ic_tab_tracks) }
                1 -> { tab.text = "Playlists"; tab.setIcon(R.drawable.ic_tab_playlists) }
            }
        }.attach()

        val detailContainer = findViewById<FrameLayout>(R.id.detailContainer)
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (detailContainer.visibility == View.VISIBLE) {
                    supportFragmentManager.popBackStack()
                    detailContainer.visibility = View.GONE
                }
            }
        })

        miniPlayerContainer = findViewById(R.id.miniPlayerContainer)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isExtendedPlayerVisible) hideExtendedPlayer()
                else if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    if (supportFragmentManager.backStackEntryCount == 1) detailContainer.visibility = View.GONE
                } else {
                    isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true
                }
            }
        })

        val miniPlayerView = layoutInflater.inflate(R.layout.view_mini_player, miniPlayerContainer, true)
        tvMiniPlayerTitle = miniPlayerView.findViewById(R.id.tvMiniPlayerTitle)
        tvMiniPlayerTime = miniPlayerView.findViewById(R.id.tvMiniPlayerTime)
        ivMiniAlbumArt = miniPlayerView.findViewById(R.id.ivMiniAlbumArt)
        seekBar = miniPlayerView.findViewById(R.id.seekBar)
        btnRepeat = miniPlayerView.findViewById(R.id.btnRepeat)
        btnPrevious = miniPlayerView.findViewById(R.id.btnPrevious)
        btnPlayPause = miniPlayerView.findViewById(R.id.btnPlayPause)
        btnNext = miniPlayerView.findViewById(R.id.btnNext)

        btnRepeat.setOnClickListener {
            repeatOne = !repeatOne
            mediaController?.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            updateRepeatButton()
        }
        btnPrevious.setOnClickListener { mediaController?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
        btnPlayPause.setOnClickListener { mediaController?.let { if (it.isPlaying) it.pause() else it.play() } }
        btnNext.setOnClickListener { mediaController?.seekToNextMediaItem() }

        miniPlayerView.findViewById<ImageButton>(R.id.btnCloseMiniPlayer).setOnClickListener {
            mediaController?.stop(); mediaController?.clearMediaItems(); miniPlayerContainer.visibility = View.GONE
        }

        setupSeekBar(seekBar) { newPos, duration ->
            tvMiniPlayerTime.text = "${FormatUtil.formatTime(newPos)} / ${FormatUtil.formatTime(duration)}"
        }

        // --- EXTENDED PLAYER SETUP ---
        extendedPlayerContainer = findViewById(R.id.extendedPlayerContainer)
        val extendedPlayerView = layoutInflater.inflate(R.layout.view_extended_player, extendedPlayerContainer, false)
        extendedPlayerContainer.addView(extendedPlayerView)
        
        ivExtendedAlbumArt = extendedPlayerView.findViewById(R.id.ivExtendedAlbumArt)
        tvExtendedTitle = extendedPlayerView.findViewById(R.id.tvExtendedTitle)
        tvExtendedArtist = extendedPlayerView.findViewById(R.id.tvExtendedArtist)
        seekBarExtended = extendedPlayerView.findViewById(R.id.seekBarExtended)
        tvExtendedCurrentTime = extendedPlayerView.findViewById(R.id.tvExtendedCurrentTime)
        tvExtendedTotalTime = extendedPlayerView.findViewById(R.id.tvExtendedTotalTime)
        btnExtendedPlayPause = extendedPlayerView.findViewById(R.id.btnExtendedPlayPause)
        val btnExtendedPrevious = extendedPlayerView.findViewById<ImageButton>(R.id.btnExtendedPrevious)
        val btnExtendedNext = extendedPlayerView.findViewById<ImageButton>(R.id.btnExtendedNext)
        btnExtendedRepeat = extendedPlayerView.findViewById(R.id.btnExtendedRepeat)
        val btnCollapsePlayer = extendedPlayerView.findViewById<ImageButton>(R.id.btnCollapsePlayer)

        btnCollapsePlayer.setOnClickListener { hideExtendedPlayer() }
        btnExtendedPlayPause.setOnClickListener { mediaController?.let { if (it.isPlaying) it.pause() else it.play() } }
        btnExtendedPrevious.setOnClickListener { mediaController?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
        btnExtendedNext.setOnClickListener { mediaController?.seekToNextMediaItem() }
        btnExtendedRepeat.setOnClickListener {
            repeatOne = !repeatOne
            mediaController?.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            updateRepeatButton(); updateExtendedRepeatButton()
        }
        
        setupSeekBar(seekBarExtended) { newPos, _ -> tvExtendedCurrentTime.text = FormatUtil.formatTime(newPos) }

        miniPlayerView.findViewById<ImageButton>(R.id.btnExpandPlayer).setOnClickListener { showExtendedPlayer() }

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            mediaController?.let { 
                it.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                setupPlayerListener(it)
                updateMiniPlayerUI(it)
                if (isExtendedPlayerVisible) updateExtendedPlayerUI(it)
            }
        }, ContextCompat.getMainExecutor(this))

        handler.post(updateSeekBarRunnable)
    }

    private fun setupSeekBar(seekBar: SeekBar, onProgressUpdate: (newPosMs: Long, durationMs: Long) -> Unit) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val controller = mediaController ?: return
                    val duration = controller.duration
                    if (duration > 0) onProgressUpdate((progress.toLong() * duration) / 1000, duration)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val controller = mediaController
                if (controller != null && seekBar != null) {
                    val duration = controller.duration
                    if (duration > 0) controller.seekTo((seekBar.progress.toLong() * duration) / 1000)
                }
                isUserSeeking = false
            }
        })
    }

    fun showExtendedPlayer() {
        isExtendedPlayerVisible = true
        miniPlayerContainer.visibility = View.GONE
        extendedPlayerContainer.visibility = View.VISIBLE
        mediaController?.let { updateExtendedPlayerUI(it); loadExtendedPlayerAlbumArt(it) }
    }

    fun hideExtendedPlayer() {
        isExtendedPlayerVisible = false
        extendedPlayerContainer.visibility = View.GONE
        if (mediaController?.currentMediaItem != null) miniPlayerContainer.visibility = View.VISIBLE
    }

    private fun setupPlayerListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMiniPlayerUI(controller); loadMiniPlayerAlbumArt(controller)
                if (isExtendedPlayerVisible) { updateExtendedPlayerUI(controller); loadExtendedPlayerAlbumArt(controller) }
                updateCurrentTrackDuration(controller)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMiniPlayerUI(controller)
                if (isExtendedPlayerVisible) updateExtendedPlayerUI(controller)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMiniPlayerUI(controller)
                if (isExtendedPlayerVisible) updateExtendedPlayerUI(controller)
                if (playbackState == Player.STATE_READY) {
                    consecutiveErrors = 0; updateCurrentTrackDuration(controller)
                    val extras = controller.currentMediaItem?.mediaMetadata?.extras
                    val wasAvailable = extras?.getBoolean("isAvailable") ?: true
                    if (!wasAvailable) {
                        val currentMediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
                        currentMediaId?.let { id ->
                            lifecycleScope.launch {
                                repository.markTrackAvailable(id); extras?.putBoolean("isAvailable", true); trackRecoveryListener?.invoke()
                            }
                        }
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) {
                    val failedMediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
                    failedMediaId?.let { lifecycleScope.launch { repository.markTrackUnavailable(it) } }
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        controller.stop(); Toast.makeText(this@MainActivity, "Playback stopped: Too many unavailable tracks.", Toast.LENGTH_LONG).show(); return
                    }
                    if (controller.hasNextMediaItem()) { controller.seekToNextMediaItem(); controller.prepare(); controller.play() } 
                    else { controller.stop(); Toast.makeText(this@MainActivity, "End of queue reached. Some tracks were unavailable.", Toast.LENGTH_LONG).show() }
                }
            }
        })
    }

    private fun updateCurrentTrackDuration(controller: MediaController) {
        val duration = controller.duration
        val trackId = controller.currentMediaItem?.mediaId?.toLongOrNull()
        if (duration > 0 && trackId != null) lifecycleScope.launch { repository.updateTrackDuration(trackId, duration) }
    }

    private fun updateMiniPlayerUI(controller: MediaController) {
        if (controller.currentMediaItem == null) { miniPlayerContainer.visibility = View.GONE; return }
        miniPlayerContainer.visibility = View.VISIBLE
        tvMiniPlayerTitle.text = controller.currentMediaItem?.mediaMetadata?.title ?: "Unknown Track"
        btnPlayPause.setImageResource(if (controller.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        updateSeekBarProgress(controller)
    }

    private fun updateSeekBarProgress(controller: MediaController) {
        val duration = controller.duration; val position = controller.currentPosition
        if (duration > 0) {
            seekBar.progress = ((position * 1000) / duration).toInt()
            tvMiniPlayerTime.text = "${FormatUtil.formatTime(position)} / ${FormatUtil.formatTime(duration)}"
        } else { seekBar.progress = 0; tvMiniPlayerTime.text = "0:00 / 0:00" }
    }

    private fun updateExtendedPlayerUI(controller: MediaController) {
        if (controller.currentMediaItem == null) return
        tvExtendedTitle.text = controller.currentMediaItem?.mediaMetadata?.title ?: "Unknown Track"
        tvExtendedArtist.text = controller.currentMediaItem?.mediaMetadata?.artist ?: "Unknown Artist"
        btnExtendedPlayPause.setImageResource(if (controller.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        updateExtendedSeekBarProgress(controller); updateExtendedRepeatButton()
    }

    private fun updateExtendedSeekBarProgress(controller: MediaController) {
        val duration = controller.duration; val position = controller.currentPosition
        if (duration > 0) {
            seekBarExtended.progress = ((position * 1000) / duration).toInt()
            tvExtendedCurrentTime.text = FormatUtil.formatTime(position); tvExtendedTotalTime.text = FormatUtil.formatTime(duration)
        } else { seekBarExtended.progress = 0; tvExtendedCurrentTime.text = "0:00"; tvExtendedTotalTime.text = "0:00" }
    }

    private fun updateExtendedRepeatButton() {
        if (repeatOne) { btnExtendedRepeat.setImageResource(R.drawable.ic_repeat_one); btnExtendedRepeat.setColorFilter(ContextCompat.getColor(this, R.color.accent_blue)) } 
        else { btnExtendedRepeat.setImageResource(R.drawable.ic_repeat); btnExtendedRepeat.clearColorFilter() }
    }

    private fun loadExtendedPlayerAlbumArt(controller: MediaController) {
        val uri = controller.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        val trackId = controller.currentMediaItem?.mediaId?.toLongOrNull()

        val cachedLarge = trackId?.let { AlbumArtCache.largeLruCache.get(it) }
        if (cachedLarge != null) { ivExtendedAlbumArt.setImageBitmap(cachedLarge); return }

        ivExtendedAlbumArt.setImageResource(R.drawable.ic_music_note)

        lifecycleScope.launch(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@MainActivity, Uri.parse(uri))
                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    bitmap = BitmapUtil.decodeSampledBitmap(artBytes, 512, 512)
                    if (bitmap != null && trackId != null) AlbumArtCache.largeLruCache.put(trackId, bitmap)
                }
            } catch (_: Exception) {} finally { retriever.release() }

            withContext(Dispatchers.Main) {
                if (isExtendedPlayerVisible && controller.currentMediaItem?.mediaId?.toLongOrNull() == trackId) {
                    if (bitmap != null) ivExtendedAlbumArt.setImageBitmap(bitmap) else ivExtendedAlbumArt.setImageResource(R.drawable.ic_music_note)
                }
            }
        }
    }

    private fun loadMiniPlayerAlbumArt(controller: MediaController) {
        val uri = controller.currentMediaItem?.localConfiguration?.uri?.toString()
        if (uri == currentTrackUri) return
        currentTrackUri = uri
        albumArtJob?.cancel()

        val trackId = controller.currentMediaItem?.mediaId?.toLongOrNull()
        val cached = trackId?.let { AlbumArtCache.lruCache.get(it) }
        if (cached != null) { ivMiniAlbumArt.setImageBitmap(cached); return }

        ivMiniAlbumArt.setImageResource(R.drawable.ic_music_note)

        if (uri != null) {
            albumArtJob = lifecycleScope.launch(Dispatchers.IO) {
                var bitmap: Bitmap? = null
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, Uri.parse(uri))
                    val artBytes = retriever.embeddedPicture
                    if (artBytes != null) {
                        bitmap = BitmapUtil.decodeSampledBitmap(artBytes, 128, 128)
                        if (bitmap != null && trackId != null) AlbumArtCache.lruCache.put(trackId, bitmap)
                    }
                } catch (_: Exception) {} finally { retriever.release() }

                withContext(Dispatchers.Main) {
                    if (currentTrackUri == uri) {
                        if (bitmap != null) ivMiniAlbumArt.setImageBitmap(bitmap) else ivMiniAlbumArt.setImageResource(R.drawable.ic_music_note)
                    }
                }
            }
        }
    }

    private fun updateRepeatButton() {
        if (repeatOne) { btnRepeat.setImageResource(R.drawable.ic_repeat_one); btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.accent_blue)) } 
        else { btnRepeat.setImageResource(R.drawable.ic_repeat); btnRepeat.clearColorFilter() }
    }

    fun playMusic(tracksToPlay: List<Track>, startIndex: Int = 0) {
        val controller = mediaController ?: return
        val mediaItems = tracksToPlay.map { track ->
            MediaItem.Builder()
                // IMPORTANT: AlbumArtCache relies on this mediaId matching Track.id exactly.
                .setMediaId(track.id.toString())
                .setUri(track.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setExtras(Bundle().apply { putBoolean("isAvailable", track.isAvailable) }).build())
                .build()
        }
        controller.setMediaItems(mediaItems, startIndex, 0L)
        controller.prepare(); controller.play()
    }

    fun showPlaylistDetail(playlistId: Long, playlistName: String) {
        findViewById<FrameLayout>(R.id.detailContainer).visibility = View.VISIBLE
        supportFragmentManager.beginTransaction().replace(R.id.detailContainer, PlaylistDetailFragment.newInstance(playlistId, playlistName)).addToBackStack("playlistDetail").commit()
    }

    fun setSelectionModeEnabled(enabled: Boolean) {
        val tabLayout = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        if (enabled) { tabLayout.visibility = View.GONE; miniPlayerContainer.visibility = View.GONE; viewPager.isUserInputEnabled = false } 
        else {
            tabLayout.visibility = View.VISIBLE
            if (mediaController?.currentMediaItem != null && !isExtendedPlayerVisible) miniPlayerContainer.visibility = View.VISIBLE
            viewPager.isUserInputEnabled = true
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateSeekBarRunnable); super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

package com.cappielloantonio.tempo.service

import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.TaskStackBuilder
import android.content.Intent
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession.ControllerInfo
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.ui.activity.MainActivity
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.DownloadUtil
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.ReplayGainUtil
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

@UnstableApi
class MediaService : MediaLibraryService(), SessionAvailabilityListener {
    private lateinit var automotiveRepository: AutomotiveRepository
    private lateinit var player: ExoPlayer
    private lateinit var castPlayer: CastPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var librarySessionCallback: MediaLibrarySessionCallback

    override fun onCreate() {
        super.onCreate()

        initializeRepository()
        initializePlayer()
        initializeCastPlayer()
        initializeMediaLibrarySession()
        initializePlayerListener()

        setPlayer(
            null,
            if (this::castPlayer.isInitialized && castPlayer.isCastSessionAvailable) castPlayer else player
        )
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession.player

        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun initializeRepository() {
        automotiveRepository = AutomotiveRepository()
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(getRenderersFactory())
            .setMediaSourceFactory(getMediaSourceFactory())
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(initializeLoadControl())
            .build()

        player.shuffleModeEnabled = Preferences.isShuffleModeEnabled()
        player.repeatMode = Preferences.getRepeatMode()
    }

    private fun initializeCastPlayer() {
        if (GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        ) {
            castPlayer = CastPlayer(CastContext.getSharedInstance(this))
            castPlayer.setSessionAvailabilityListener(this)
        }
    }

    private fun initializeMediaLibrarySession() {
        val sessionActivityPendingIntent =
            TaskStackBuilder.create(this).run {
                addNextIntent(Intent(this@MediaService, MainActivity::class.java))
                getPendingIntent(0, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
            }

        librarySessionCallback = createLibrarySessionCallback()
        mediaLibrarySession =
            MediaLibrarySession.Builder(this, player, librarySessionCallback)
                .setSessionActivity(sessionActivityPendingIntent)
                .build()
    }

    private fun createLibrarySessionCallback(): MediaLibrarySessionCallback {
        return MediaLibrarySessionCallback(this, automotiveRepository)
    }

    private fun initializePlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    MediaManager.setLastPlayedTimestamp(mediaItem)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                ReplayGainUtil.setReplayGain(player, tracks)
                MediaManager.scrobble(player.currentMediaItem, false)

                if (player.currentMediaItemIndex + 1 == player.mediaItemCount)
                    MediaManager.continuousPlay(player.currentMediaItem)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    MediaManager.setPlayingPausedTimestamp(
                        player.currentMediaItem,
                        player.currentPosition
                    )
                } else {
                    MediaManager.scrobble(player.currentMediaItem, false)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)

                if (!player.hasNextMediaItem() &&
                    playbackState == Player.STATE_ENDED &&
                    player.mediaMetadata.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC
                ) {
                    MediaManager.scrobble(player.currentMediaItem, true)
                    MediaManager.saveChronology(player.currentMediaItem)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)

                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    if (oldPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.scrobble(oldPosition.mediaItem, true)
                        MediaManager.saveChronology(oldPosition.mediaItem)
                    }

                    if (newPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.setLastPlayedTimestamp(newPosition.mediaItem)
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                Preferences.setShuffleModeEnabled(shuffleModeEnabled)
                mediaLibrarySession.setCustomLayout(
                    librarySessionCallback.buildCustomLayout(player)
                )
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                Preferences.setRepeatMode(repeatMode)
                mediaLibrarySession.setCustomLayout(
                    librarySessionCallback.buildCustomLayout(player)
                )
            }
        })
    }

    private fun initializeLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                (DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * Preferences.getBufferingStrategy()).toInt(),
                (DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * Preferences.getBufferingStrategy()).toInt(),
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
    }

    private fun getQueueFromPlayer(player: Player): List<MediaItem> {
        // Helper function to get all media items from a player's queue.
        val queue = mutableListOf<MediaItem>()
        for (i in 0 until player.mediaItemCount) {
            queue.add(player.getMediaItemAt(i))
        }
        return queue
    }

    private fun setPlayer(oldPlayer: Player?, newPlayer: Player) {
        // Safely switches the player instance and handles state transfer.
        if (oldPlayer === newPlayer) return

        oldPlayer?.stop()
        mediaLibrarySession.player = newPlayer
    }

    private fun releasePlayer() {
        if (this::castPlayer.isInitialized) castPlayer.setSessionAvailabilityListener(null)
        if (this::castPlayer.isInitialized) castPlayer.release()
        player.release()
        mediaLibrarySession.release()
        automotiveRepository.deleteMetadata()
    }

    private fun getRenderersFactory() = DownloadUtil.buildRenderersFactory(this, false)

    private fun getMediaSourceFactory() =
        DefaultMediaSourceFactory(this).setDataSourceFactory(DownloadUtil.getDataSourceFactory(this))

    override fun onCastSessionAvailable() {
        // Get the current queue, item index, and position from the local player.
        val currentQueue = getQueueFromPlayer(player)
        val currentIndex = player.currentMediaItemIndex
        val currentPosition = player.currentPosition
        val isPlaying = player.playWhenReady

        // Switch the player to the CastPlayer.
        setPlayer(player, castPlayer)

        // Transfer the entire queue to the CastPlayer and start playback.
        castPlayer.setMediaItems(currentQueue, currentIndex, currentPosition)
        castPlayer.playWhenReady = isPlaying
        castPlayer.prepare()
    }

    override fun onCastSessionUnavailable() {
        // Get the current queue, item index, and position from the CastPlayer.
        val currentQueue = getQueueFromPlayer(castPlayer)
        val currentIndex = castPlayer.currentMediaItemIndex
        val currentPosition = castPlayer.currentPosition
        val isPlaying = castPlayer.playWhenReady

        // Switch the player back to the local ExoPlayer.
        setPlayer(castPlayer, player)

        // Transfer the entire queue to the local ExoPlayer and start playback.
        player.setMediaItems(currentQueue, currentIndex, currentPosition)
        player.playWhenReady = isPlaying
        player.prepare()
    }
}

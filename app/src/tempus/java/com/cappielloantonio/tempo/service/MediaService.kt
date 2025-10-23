package com.cappielloantonio.tempo.service

import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.TaskStackBuilder
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
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
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession.ControllerInfo
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.repository.QueueRepository
import com.cappielloantonio.tempo.ui.activity.MainActivity
import com.cappielloantonio.tempo.util.AssetLinkUtil
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.DownloadUtil
import com.cappielloantonio.tempo.util.DynamicMediaSourceFactory
import com.cappielloantonio.tempo.util.MappingUtil
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.ReplayGainUtil
import com.cappielloantonio.tempo.widget.WidgetUpdateManager
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
    lateinit var equalizerManager: EqualizerManager

    inner class LocalBinder : Binder() {
        fun getEqualizerManager(): EqualizerManager {
            return this@MediaService.equalizerManager
        }
    }

    private val binder = LocalBinder()

    companion object {
        const val ACTION_BIND_EQUALIZER = "com.cappielloantonio.tempo.service.BIND_EQUALIZER"
    }
    private val widgetUpdateHandler = Handler(Looper.getMainLooper())
    private var widgetUpdateScheduled = false
    private val widgetUpdateRunnable = object : Runnable {
        override fun run() {
            if (!player.isPlaying) {
                widgetUpdateScheduled = false
                return
            }
            updateWidget()
            widgetUpdateHandler.postDelayed(this, WIDGET_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        initializeRepository()
        initializePlayer()
        initializeMediaLibrarySession()
        restorePlayerFromQueue()
        initializePlayerListener()
        initializeCastPlayer()
        initializeEqualizerManager()

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
        equalizerManager.release()
        stopWidgetUpdates()
        releasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Check if the intent is for our custom equalizer binder
        if (intent?.action == ACTION_BIND_EQUALIZER) {
            return binder
        }
        // Otherwise, handle it as a normal MediaLibraryService connection
        return super.onBind(intent)
    }

    private fun initializeRepository() {
        automotiveRepository = AutomotiveRepository()
    }

    private fun initializeEqualizerManager() {
        equalizerManager = EqualizerManager()
        val audioSessionId = player.audioSessionId
        if (equalizerManager.attachToSession(audioSessionId)) {
            val enabled = Preferences.isEqualizerEnabled()
            equalizerManager.setEnabled(enabled)

            val bands = equalizerManager.getNumberOfBands()
            val savedLevels = Preferences.getEqualizerBandLevels(bands)
            for (i in 0 until bands) {
                equalizerManager.setBandLevel(i.toShort(), savedLevels[i])
            }
        }
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this)
                .setRenderersFactory(getRenderersFactory())
                .setMediaSourceFactory(DynamicMediaSourceFactory(this))
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setLoadControl(initializeLoadControl())
                .build()

        player.shuffleModeEnabled = Preferences.isShuffleModeEnabled()
        player.repeatMode = Preferences.getRepeatMode()
    }

    @Suppress("DEPRECATION")
    private fun initializeCastPlayer() {
        if (GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        ) {
            CastContext.getSharedInstance(this, ContextCompat.getMainExecutor(this))
                    .addOnSuccessListener { castContext ->
                        castPlayer = CastPlayer(castContext)
                        castPlayer.setSessionAvailabilityListener(this@MediaService)

                        if (castPlayer.isCastSessionAvailable && this::mediaLibrarySession.isInitialized) {
                            setPlayer(player, castPlayer)
                        }
                    }
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

    private fun restorePlayerFromQueue() {
        if (player.mediaItemCount > 0) return

        val queueRepository = QueueRepository()
        val storedQueue = queueRepository.media
        if (storedQueue.isNullOrEmpty()) return

        val mediaItems = MappingUtil.mapMediaItems(storedQueue)
        if (mediaItems.isEmpty()) return

        val lastIndex = try {
            queueRepository.lastPlayedMediaIndex
        } catch (_: Exception) {
            0
        }.coerceIn(0, mediaItems.size - 1)

        val lastPosition = try {
            queueRepository.lastPlayedMediaTimestamp
        } catch (_: Exception) {
            0L
        }.let { if (it < 0L) 0L else it }

        player.setMediaItems(mediaItems, lastIndex, lastPosition)
        player.prepare()
        updateWidget()
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
                updateWidget()
            }

            override fun onTracksChanged(tracks: Tracks) {
                ReplayGainUtil.setReplayGain(player, tracks)

                val currentMediaItem = player.currentMediaItem
                if (currentMediaItem != null && currentMediaItem.mediaMetadata.extras != null) {
                    MediaManager.scrobble(currentMediaItem, false)
                }

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
                if (isPlaying) {
                    scheduleWidgetUpdates()
                } else {
                    stopWidgetUpdates()
                }
                updateWidget()
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
                updateWidget()
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
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                Preferences.setRepeatMode(repeatMode)
            }
        })
        if (player.isPlaying) {
            scheduleWidgetUpdates()
        }
    }

    private fun updateWidget() {
        val mi = player.currentMediaItem
        val title = mi?.mediaMetadata?.title?.toString()
            ?: mi?.mediaMetadata?.extras?.getString("title")
        val artist = mi?.mediaMetadata?.artist?.toString()
            ?: mi?.mediaMetadata?.extras?.getString("artist")
        val album = mi?.mediaMetadata?.albumTitle?.toString()
            ?: mi?.mediaMetadata?.extras?.getString("album")
        val extras = mi?.mediaMetadata?.extras
        val coverId = extras?.getString("coverArtId")
        val songLink = extras?.getString("assetLinkSong")
            ?: AssetLinkUtil.buildLink(AssetLinkUtil.TYPE_SONG, extras?.getString("id"))
        val albumLink = extras?.getString("assetLinkAlbum")
            ?: AssetLinkUtil.buildLink(AssetLinkUtil.TYPE_ALBUM, extras?.getString("albumId"))
        val artistLink = extras?.getString("assetLinkArtist")
            ?: AssetLinkUtil.buildLink(AssetLinkUtil.TYPE_ARTIST, extras?.getString("artistId"))
        val position = player.currentPosition.takeIf { it != C.TIME_UNSET } ?: 0L
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        WidgetUpdateManager.updateFromState(
            this,
            title ?: "",
            artist ?: "",
            album ?: "",
            coverId,
            player.isPlaying,
            player.shuffleModeEnabled,
            player.repeatMode,
            position,
            duration,
            songLink,
            albumLink,
            artistLink
        )
    }

    private fun scheduleWidgetUpdates() {
        if (widgetUpdateScheduled) return
        widgetUpdateHandler.postDelayed(widgetUpdateRunnable, WIDGET_UPDATE_INTERVAL_MS)
        widgetUpdateScheduled = true
    }

    private fun stopWidgetUpdates() {
        if (!widgetUpdateScheduled) return
        widgetUpdateHandler.removeCallbacks(widgetUpdateRunnable)
        widgetUpdateScheduled = false
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
        val queue = mutableListOf<MediaItem>()
        for (i in 0 until player.mediaItemCount) {
            queue.add(player.getMediaItemAt(i))
        }
        return queue
    }

    private fun setPlayer(oldPlayer: Player?, newPlayer: Player) {
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

    override fun onCastSessionAvailable() {
        val currentQueue = getQueueFromPlayer(player)
        val currentIndex = player.currentMediaItemIndex
        val currentPosition = player.currentPosition
        val isPlaying = player.playWhenReady

        setPlayer(player, castPlayer)

        castPlayer.setMediaItems(currentQueue, currentIndex, currentPosition)
        castPlayer.playWhenReady = isPlaying
        castPlayer.prepare()
    }

    override fun onCastSessionUnavailable() {
        val currentQueue = getQueueFromPlayer(castPlayer)
        val currentIndex = castPlayer.currentMediaItemIndex
        val currentPosition = castPlayer.currentPosition
        val isPlaying = castPlayer.playWhenReady

        setPlayer(castPlayer, player)

        player.setMediaItems(currentQueue, currentIndex, currentPosition)
        player.playWhenReady = isPlaying
        player.prepare()
    }
}

private const val WIDGET_UPDATE_INTERVAL_MS = 1000L

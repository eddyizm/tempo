package com.cappielloantonio.tempo.service

import android.annotation.SuppressLint
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.TaskStackBuilder
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.TrackSelectionArray
import androidx.media3.session.*
import androidx.media3.session.MediaSession.ControllerInfo
import com.cappielloantonio.tempo.R
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
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture


@UnstableApi
class MediaService : MediaLibraryService() {
    private val librarySessionCallback = CustomMediaLibrarySessionCallback()

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var shuffleCommands: List<CommandButton>
    private lateinit var repeatCommands: List<CommandButton>
    lateinit var equalizerManager: EqualizerManager

    private var customLayout = ImmutableList.of<CommandButton>()
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

    inner class LocalBinder : Binder() {
        fun getEqualizerManager(): EqualizerManager {
            return this@MediaService.equalizerManager
        }
    }

    private val binder = LocalBinder()

    companion object {
        private const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON =
            "android.media3.session.demo.SHUFFLE_ON"
        private const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF =
            "android.media3.session.demo.SHUFFLE_OFF"
        private const val CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF =
            "android.media3.session.demo.REPEAT_OFF"
        private const val CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE =
            "android.media3.session.demo.REPEAT_ONE"
        private const val CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL =
            "android.media3.session.demo.REPEAT_ALL"
        const val ACTION_BIND_EQUALIZER = "com.cappielloantonio.tempo.service.BIND_EQUALIZER"
    }

    override fun onCreate() {
        super.onCreate()

        initializeCustomCommands()
        initializePlayer()
        initializeMediaLibrarySession()
        restorePlayerFromQueue()
        initializePlayerListener()
        initializeEqualizerManager()

        setPlayer(player)
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
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

    private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()

            (shuffleCommands + repeatCommands).forEach { commandButton ->
                commandButton.sessionCommand?.let { availableSessionCommands.add(it) }
            }

            customLayout = buildCustomLayout(session.player)

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableSessionCommands.build())
                .setAvailablePlayerCommands(connectionResult.availablePlayerCommands)
                .setCustomLayout(customLayout)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: ControllerInfo) {
            if (!customLayout.isEmpty() && controller.controllerVersion != 0) {
                ignoreFuture(mediaLibrarySession.setCustomLayout(controller, customLayout))
            }
        }

        fun buildCustomLayout(player: Player): ImmutableList<CommandButton> {
            val shuffle = shuffleCommands[if (player.shuffleModeEnabled) 1 else 0]
            val repeat = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> repeatCommands[1]
                Player.REPEAT_MODE_ALL -> repeatCommands[2]
                else -> repeatCommands[0]
            }
            return ImmutableList.of(shuffle, repeat)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON -> player.shuffleModeEnabled = true
                CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF -> player.shuffleModeEnabled = false
                CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF,
                CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL,
                CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE -> {
                    val nextMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    player.repeatMode = nextMode
                }
            }

            customLayout = librarySessionCallback.buildCustomLayout(player)
            session.setCustomLayout(customLayout)

            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val updatedMediaItems = mediaItems.map { mediaItem ->
                val mediaMetadata = mediaItem.mediaMetadata

                val newMetadata = mediaMetadata.buildUpon()
                    .setArtist(
                        if (mediaMetadata.artist != null) mediaMetadata.artist
                        else mediaMetadata.extras?.getString("uri") ?: ""
                    )
                    .build()

                mediaItem.buildUpon()
                    .setUri(mediaItem.requestMetadata.mediaUri)
                    .setMediaMetadata(newMetadata)
                    .setMimeType(MimeTypes.BASE_TYPE_AUDIO)
                    .build()
            }
            return Futures.immediateFuture(updatedMediaItems)
        }
    }

    private fun initializeCustomCommands() {
        shuffleCommands = listOf(
            getShuffleCommandButton(
                SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON, Bundle.EMPTY)
            ),
            getShuffleCommandButton(
                SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF, Bundle.EMPTY)
            )
        )

        repeatCommands = listOf(
            getRepeatCommandButton(
                SessionCommand(CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF, Bundle.EMPTY)
            ),
            getRepeatCommandButton(
                SessionCommand(CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE, Bundle.EMPTY)
            ),
            getRepeatCommandButton(
                SessionCommand(CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL, Bundle.EMPTY)
            )
        )

        customLayout = ImmutableList.of(shuffleCommands[0], repeatCommands[0])
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

    private fun initializeMediaLibrarySession() {
        val sessionActivityPendingIntent =
            TaskStackBuilder.create(this).run {
                addNextIntent(Intent(this@MediaService, MainActivity::class.java))
                getPendingIntent(0, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
            }

        mediaLibrarySession =
            MediaLibrarySession.Builder(this, player, librarySessionCallback)
                .setSessionActivity(sessionActivityPendingIntent)
                .build()

        if (!customLayout.isEmpty()) {
            mediaLibrarySession.setCustomLayout(customLayout)
        }
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
                customLayout = librarySessionCallback.buildCustomLayout(player)
                mediaLibrarySession.setCustomLayout(customLayout)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                Preferences.setRepeatMode(repeatMode)
                customLayout = librarySessionCallback.buildCustomLayout(player)
                mediaLibrarySession.setCustomLayout(customLayout)
            }
        })
        if (player.isPlaying) {
            scheduleWidgetUpdates()
        }
    }

    private fun setPlayer(player: Player) {
        mediaLibrarySession.player = player
    }

    private fun releasePlayer() {
        player.release()
        mediaLibrarySession.release()
    }

    @SuppressLint("PrivateResource")
    private fun getShuffleCommandButton(sessionCommand: SessionCommand): CommandButton {
        val isOn = sessionCommand.customAction == CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON
        return CommandButton.Builder()
            .setDisplayName(
                getString(
                    if (isOn) R.string.exo_controls_shuffle_on_description
                    else R.string.exo_controls_shuffle_off_description
                )
            )
            .setSessionCommand(sessionCommand)
            .setIconResId(if (isOn) R.drawable.exo_icon_shuffle_off else R.drawable.exo_icon_shuffle_on)
            .build()
    }

    @SuppressLint("PrivateResource")
    private fun getRepeatCommandButton(sessionCommand: SessionCommand): CommandButton {
        val icon = when (sessionCommand.customAction) {
            CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE -> R.drawable.exo_icon_repeat_one
            CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL -> R.drawable.exo_icon_repeat_all
            else -> R.drawable.exo_icon_repeat_off
        }
        val description = when (sessionCommand.customAction) {
            CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE -> R.string.exo_controls_repeat_one_description
            CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL -> R.string.exo_controls_repeat_all_description
            else -> R.string.exo_controls_repeat_off_description
        }
        return CommandButton.Builder()
            .setDisplayName(getString(description))
            .setSessionCommand(sessionCommand)
            .setIconResId(icon)
            .build()
    }

    private fun ignoreFuture(@Suppress("UNUSED_PARAMETER") customLayout: ListenableFuture<SessionResult>) {
        /* Do nothing. */
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


    private fun getRenderersFactory() = DownloadUtil.buildRenderersFactory(this, false)

    private fun getMediaSourceFactory(): MediaSource.Factory = DynamicMediaSourceFactory(this)
}

private const val WIDGET_UPDATE_INTERVAL_MS = 1000L

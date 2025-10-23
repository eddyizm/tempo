package com.cappielloantonio.tempo.service

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.subsonic.base.ApiResponse
import com.cappielloantonio.tempo.util.Constants.CUSTOM_COMMAND_TOGGLE_HEART_LOADING
import com.cappielloantonio.tempo.util.Constants.CUSTOM_COMMAND_TOGGLE_HEART_OFF
import com.cappielloantonio.tempo.util.Constants.CUSTOM_COMMAND_TOGGLE_HEART_ON
import com.cappielloantonio.tempo.util.Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL
import com.cappielloantonio.tempo.util.Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF
import com.cappielloantonio.tempo.util.Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE
import com.cappielloantonio.tempo.util.Constants.CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF
import com.cappielloantonio.tempo.util.Constants.CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON
import com.google.common.collect.ImmutableList
import com.cappielloantonio.tempo.util.Preferences
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

open class MediaLibrarySessionCallback(
    context: Context,
    automotiveRepository: AutomotiveRepository
) :
    MediaLibraryService.MediaLibrarySession.Callback {

    init {
        MediaBrowserTree.initialize(automotiveRepository)
    }

    private val customCommandToggleShuffleModeOn = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_shuffle_on_description))
        .setSessionCommand(
            SessionCommand(
                CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON, Bundle.EMPTY
            )
        ).setIconResId(R.drawable.exo_icon_shuffle_off).build()

    private val customCommandToggleShuffleModeOff = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_shuffle_off_description))
        .setSessionCommand(
            SessionCommand(
                CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF, Bundle.EMPTY
            )
        ).setIconResId(R.drawable.exo_icon_shuffle_on).build()

    private val customCommandToggleRepeatModeOff = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_repeat_off_description))
        .setSessionCommand(SessionCommand(CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF, Bundle.EMPTY))
        .setIconResId(R.drawable.exo_icon_repeat_off)
        .build()

    private val customCommandToggleRepeatModeOne = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_repeat_one_description))
        .setSessionCommand(SessionCommand(CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE, Bundle.EMPTY))
        .setIconResId(R.drawable.exo_icon_repeat_one)
        .build()

    private val customCommandToggleRepeatModeAll = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_repeat_all_description))
        .setSessionCommand(SessionCommand(CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL, Bundle.EMPTY))
        .setIconResId(R.drawable.exo_icon_repeat_all)
        .build()

    private val customCommandToggleHeartOn = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_heart_on_description))
        .setSessionCommand(
            SessionCommand(
                CUSTOM_COMMAND_TOGGLE_HEART_ON, Bundle.EMPTY
            )
        )
        .setIconResId(R.drawable.ic_favorite)
        .build()

    private val customCommandToggleHeartOff = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_heart_off_description))
        .setSessionCommand(
            SessionCommand(CUSTOM_COMMAND_TOGGLE_HEART_OFF, Bundle.EMPTY)
        )
        .setIconResId(R.drawable.ic_favorites_outlined)
        .build()

    // Fake Command while waiting for like update command
    private val customCommandToggleHeartLoading = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.cast_expanded_controller_loading))
        .setSessionCommand(
            SessionCommand(CUSTOM_COMMAND_TOGGLE_HEART_LOADING, Bundle.EMPTY)
        )
        .setIconResId(R.drawable.ic_bookmark_sync)
        .build()

    private val customLayoutCommandButtons = listOf(
        customCommandToggleShuffleModeOn,
        customCommandToggleShuffleModeOff,
        customCommandToggleRepeatModeOff,
        customCommandToggleRepeatModeOne,
        customCommandToggleRepeatModeAll,
        customCommandToggleHeartOn,
        customCommandToggleHeartOff,
        customCommandToggleHeartLoading,
    )

    @OptIn(UnstableApi::class)
    val mediaNotificationSessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .also { builder ->
                customLayoutCommandButtons.forEach { commandButton ->
                    commandButton.sessionCommand?.let { builder.add(it) }
                }
            }.build()

    @OptIn(UnstableApi::class)
    override fun onConnect(
        session: MediaSession, controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        session.player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateMediaNotificationCustomLayout(session)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updateMediaNotificationCustomLayout(session)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateMediaNotificationCustomLayout(session)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMediaNotificationCustomLayout(session)
            }
        })

        // FIXME: I'm not sure this if is required anymore
        if (session.isMediaNotificationController(controller) || session.isAutomotiveController(
                controller
            ) || session.isAutoCompanionController(controller)
        ) {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(mediaNotificationSessionCommands)
                .setCustomLayout(buildCustomLayout(session.player))
                .build()
        }

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
    }

    // Update the mediaNotification after some changes
    @OptIn(UnstableApi::class)
    private fun updateMediaNotificationCustomLayout(
        session: MediaSession,
        isRatingPending: Boolean = false
    ) {
        session.setCustomLayout(
            session.mediaNotificationControllerInfo!!,
            buildCustomLayout(session.player, isRatingPending)
        )
    }

    private fun buildCustomLayout(player: Player, isRatingPending: Boolean = false): ImmutableList<CommandButton> {
        val customLayout = mutableListOf<CommandButton>()

        val showShuffle = Preferences.showShuffleInsteadOfHeart()

        if (!showShuffle) {
            if (player.currentMediaItem != null && !isRatingPending) {
                if ((player.mediaMetadata.userRating as HeartRating?)?.isHeart == true) {
                    customLayout.add(customCommandToggleHeartOn)
                } else {
                    customLayout.add(customCommandToggleHeartOff)
                }
            }
        } else {
            customLayout.add(
                if (player.shuffleModeEnabled) customCommandToggleShuffleModeOff else customCommandToggleShuffleModeOn
            )
        }

        // Add repeat button
        val repeatButton = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> customCommandToggleRepeatModeOne
            Player.REPEAT_MODE_ALL -> customCommandToggleRepeatModeAll
            else -> customCommandToggleRepeatModeOff
        }

        customLayout.add(repeatButton)
        return ImmutableList.copyOf(customLayout)
    }

    // Setting rating without a mediaId will set the currently listened mediaId
    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        rating: Rating
    ): ListenableFuture<SessionResult> {
        return onSetRating(session, controller, session.player.currentMediaItem!!.mediaId, rating)
    }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaId: String,
        rating: Rating
    ): ListenableFuture<SessionResult> {
        val isStaring = (rating as HeartRating).isHeart

        val networkCall = if (isStaring)
            App.getSubsonicClientInstance(false)
                .mediaAnnotationClient
                .star(mediaId, null, null)
        else
            App.getSubsonicClientInstance(false)
                .mediaAnnotationClient
                .unstar(mediaId, null, null)

        return CallbackToFutureAdapter.getFuture { completer ->
            networkCall.enqueue(object : Callback<ApiResponse?> {
                @OptIn(UnstableApi::class)
                override fun onResponse(
                    call: Call<ApiResponse?>,
                    response: Response<ApiResponse?>
                ) {
                    if (response.isSuccessful) {

                        // Search if the media item in the player should be updated
                        for (i in 0 until session.player.mediaItemCount) {
                            val mediaItem = session.player.getMediaItemAt(i)
                            if (mediaItem.mediaId == mediaId) {
                                val newMetadata = mediaItem.mediaMetadata.buildUpon()
                                    .setUserRating(HeartRating(isStaring)).build()
                                session.player.replaceMediaItem(
                                    i,
                                    mediaItem.buildUpon().setMediaMetadata(newMetadata).build()
                                )
                            }
                        }

                        updateMediaNotificationCustomLayout(session)
                        completer.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        updateMediaNotificationCustomLayout(session)
                        completer.set(
                            SessionResult(
                                SessionError(
                                    response.code(),
                                    response.message()
                                )
                            )
                        )
                    }
                }

                @OptIn(UnstableApi::class)
                override fun onFailure(call: Call<ApiResponse?>, t: Throwable) {
                    updateMediaNotificationCustomLayout(session)
                    completer.set(
                        SessionResult(
                            SessionError(
                                SessionError.ERROR_UNKNOWN,
                                "An error as occurred"
                            )
                        )
                    )
                }
            })
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {

        when (customCommand.customAction) {
            CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON -> {
                session.player.shuffleModeEnabled = true
                updateMediaNotificationCustomLayout(session)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF -> {
                session.player.shuffleModeEnabled = false
                updateMediaNotificationCustomLayout(session)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF,
            CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL,
            CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ONE -> {
                val nextMode = when (session.player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                session.player.repeatMode = nextMode
                updateMediaNotificationCustomLayout(session)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            CUSTOM_COMMAND_TOGGLE_HEART_ON,
            CUSTOM_COMMAND_TOGGLE_HEART_OFF -> {
                val currentRating = session.player.mediaMetadata.userRating as? HeartRating
                val isCurrentlyLiked = currentRating?.isHeart ?: false

                val newLikedState = !isCurrentlyLiked

                updateMediaNotificationCustomLayout(
                    session,
                    isRatingPending = true // Show loading state
                )
                return onSetRating(session, controller, HeartRating(newLikedState))
            }
            else -> return Futures.immediateFuture(
                SessionResult(
                    SessionError(
                        SessionError.ERROR_NOT_SUPPORTED,
                        customCommand.customAction
                    )
                )
            )
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return Futures.immediateFuture(LibraryResult.ofItem(MediaBrowserTree.getRootItem(), params))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return MediaBrowserTree.getChildren(parentId)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        return super.onAddMediaItems(
            mediaSession,
            controller,
            MediaBrowserTree.getItems(mediaItems)
        )
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 60, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return MediaBrowserTree.search(query)
    }
}

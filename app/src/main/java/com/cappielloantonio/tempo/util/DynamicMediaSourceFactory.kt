package com.cappielloantonio.tempo.util

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory

@UnstableApi
class DynamicMediaSourceFactory(
    private val context: Context
) : MediaSource.Factory {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val mediaType: String? = mediaItem.mediaMetadata.extras?.getString("type", "")

        val streamingCacheSize = Preferences.getStreamingCacheSize()
        val bypassCache = mediaType == Constants.MEDIA_TYPE_RADIO

        val useUpstream = when {
            streamingCacheSize.toInt() == 0 -> true
            streamingCacheSize > 0 && bypassCache -> true
            streamingCacheSize > 0 && !bypassCache -> false
            else -> true
        }

        val dataSourceFactory: DataSource.Factory = if (useUpstream) {
            DownloadUtil.getUpstreamDataSourceFactory(context)
        } else {
            DownloadUtil.getCacheDataSourceFactory(context)
        }

        return when {
            mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 ||
                    mediaItem.localConfiguration?.uri?.lastPathSegment?.endsWith(".m3u8", ignoreCase = true) == true -> {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            else -> {
                val extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory()
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                    .createMediaSource(mediaItem)
            }
        }
    }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        TODO("Not yet implemented")
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        TODO("Not yet implemented")
    }

    override fun getSupportedTypes(): IntArray {
        return intArrayOf(
            C.CONTENT_TYPE_HLS,
            C.CONTENT_TYPE_OTHER
        )
    }
}
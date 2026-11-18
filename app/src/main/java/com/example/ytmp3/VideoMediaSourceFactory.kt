package com.example.ytmp3

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource

/**
 * 動画の試聴をいつものプレーヤーで流すための作り方。
 * 映像と音声が別々の URL の時(requestMetadata.extras の音声 URL)は、2 つを合わせて 1 曲として流す。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoMediaSourceFactory(private val base: MediaSource.Factory) : MediaSource.Factory by base {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val audio = mediaItem.requestMetadata.extras?.getString(EXTRA_AUDIO_URL)
            ?: return base.createMediaSource(mediaItem)
        val video = base.createMediaSource(mediaItem)
        val sound = base.createMediaSource(MediaItem.Builder().setUri(audio).setMediaId(mediaItem.mediaId + "#audio").build())
        return MergingMediaSource(video, sound)
    }

    companion object {
        /** 映像と別の音声 URL */
        const val EXTRA_AUDIO_URL = "video_audio_url"
        /** 動画として流している印 */
        const val EXTRA_IS_VIDEO = "is_video"

        fun isVideo(item: MediaItem?) = item?.requestMetadata?.extras?.getBoolean(EXTRA_IS_VIDEO) == true
    }
}

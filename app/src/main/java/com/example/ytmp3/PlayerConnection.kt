package com.example.ytmp3

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File

/** 画面側から PlaybackService を操作するための接続 */
class PlayerConnection(private val context: Context, private val onReady: (MediaController) -> Unit) {
    var controller: MediaController? = null
        private set
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            // 待っている間に release() された(画面を閉じた)なら使わずに解放(古い接続を握ったままにしない)
            if (future !== f) { runCatching { c.release() }; return@addListener }
            controller = c
            onReady(c)
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    /** 曲リストを再生キューにして index から再生 */
    fun play(tracks: List<Track>, index: Int, shuffle: Boolean = false, ref: PlayLog.Ref? = null) {
        val c = controller
        if (c == null) {
            Toast.makeText(context, "プレーヤーを準備中です。もう一度お試しください", Toast.LENGTH_SHORT).show()
            connect()
            return
        }
        if (tracks.isEmpty()) return
        // 再生できない曲(ファイルが無く、ネットからも流せない)は飛ばす
        val missing = tracks.filterNot { Net.canPlayNow(it) }.map { it.id }.toSet()
        val target = tracks.getOrNull(index)
        if (target != null && target.id in missing && !shuffle) {
            Toast.makeText(context, if (Repository.isOnline(target.id)) "☁ オンラインの曲です(${Net.reason()})" else "ファイルが見つかりません: ${target.title}",
                Toast.LENGTH_SHORT).show()
            return
        }
        val playable = tracks.filterNot { it.id in missing }
        if (playable.isEmpty()) {
            Toast.makeText(context, "再生できる曲がありません", Toast.LENGTH_SHORT).show()
            return
        }
        val skipped = tracks.size - playable.size
        if (skipped > 0) Toast.makeText(context, "今は再生できない $skipped 曲をとばします" + Net.reason().let { if (it.isNotEmpty()) "($it)" else "" }, Toast.LENGTH_SHORT).show()
        StreamDownload.previewList = null
        // ホームの「最近再生したもの」用に、どこから聴いたかを覚えておく
        PlayLog.setCurrent(ref ?: PlayLog.infer(playable), playable.map { it.id.toString() })
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle && index == 0) playable.indices.random()
        else playable.indexOfFirst { it.id == target?.id }.coerceAtLeast(0)
        c.setMediaItems(playable.map { it.toMediaItem() }, start.coerceIn(playable.indices), 0L)
        c.prepare()
        c.play()
    }

    /** ライブラリの曲と試聴曲を混ぜたキューをそのまま再生(ミックス用) */
    fun playItems(items: List<MediaItem>, ref: PlayLog.Ref? = null) {
        val c = controller ?: run { connect(); return }
        if (items.isEmpty()) return
        StreamDownload.previewList = null
        PlayLog.setCurrent(ref, items.map { it.mediaId })
        c.shuffleModeEnabled = false
        c.setMediaItems(items, 0, 0L)
        c.prepare()
        c.play()
    }

    /**
     * 検索結果の試聴。検索リストをそのまま再生キューにする(⏭ で検索の次の曲へ)。
     * ライブラリにある曲は保存済みファイルで、ない曲は再生する時に URL を取る(ytstream:)。
     */
    fun playSearch(results: List<Searcher.Result>, index: Int, list: Searcher.Result? = null, ref: PlayLog.Ref? = null) {
        val c = controller ?: run { connect(); return }
        val target = results.getOrNull(index)
        val playable = results.filter { !it.isList && it.videoId != null && Net.canPlayNow(it) }
        if (playable.isEmpty() && results.isNotEmpty()) {
            Toast.makeText(context, "ネットから再生できません(${Net.reason()})", Toast.LENGTH_SHORT).show(); return
        }
        val items = playable.map { r ->
            val local = VideoAlias.trackFor(r.videoId)
            local?.toMediaItem() ?: streamItem(r)
        }
        if (items.isEmpty()) return
        // アルバム/プレイリストを試聴中なら、まとめて追加ボタン用に覚えておく
        StreamDownload.previewList = list
        StreamDownload.previewListIds = if (list != null) items.map { it.mediaId }.toSet() else emptySet()
        PlayLog.setCurrent(ref ?: list?.let { PlayLog.streamRef(it) }, items.map { it.mediaId })
        val start = playable.indexOf(target).coerceAtLeast(0)
        c.shuffleModeEnabled = false
        c.setMediaItems(items, start, 0L)
        c.prepare()
        c.play()
    }

    /**
     * 動画の試聴もいつものプレーヤーで(キュー・ミニプレーヤー・通知・ウィジェットと同じ扱い)。
     * queue があれば検索結果をそのままキューに(動画以外の曲は音声で、番が来た時に取得)。
     */
    fun playVideo(r: Searcher.Result, s: InnerTube.VideoSources, queue: List<Searcher.Result> = listOf(r)) {
        val c = controller ?: run { connect(); return }
        val list = queue.filter { !it.isList && it.videoId != null }.ifEmpty { listOf(r) }
        val start = list.indexOfFirst { it.videoId == r.videoId }.coerceAtLeast(0)
        val items = list.mapIndexed { i, x -> if (i == start) videoItem(x, s) else streamItem(x) }
        StreamDownload.previewList = null
        PlayLog.setCurrent(null, emptyList())
        c.shuffleModeEnabled = false
        c.setMediaItems(items, start, 0L)
        c.prepare()
        c.play()
    }

    /**
     * 曲を同じ位置のまま動画に差し替える。曲そのもの(mediaId・曲名など)はそのままなので、
     * 再生の記録・♡・プレイリストは同じ曲として扱われる。s は映像と音声の URL
     */
    fun upgradeToVideo(index: Int, s: InnerTube.VideoSources) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        val old = c.getMediaItemAt(index)
        val split = s.video != null && s.audio != null
        val uri = if (split) s.video!! else s.progressive ?: s.audio ?: return
        val extras = android.os.Bundle().apply {
            putBoolean(VideoMediaSourceFactory.EXTRA_IS_VIDEO, true)
            if (split) putString(VideoMediaSourceFactory.EXTRA_AUDIO_URL, s.audio)
        }
        replaceAt(index, old.buildUpon().setUri(Uri.parse(uri))
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build()).build())
    }

    /** 保存した動画ファイルに差し替える(通信なし) */
    fun useLocalVideo(index: Int, videoUri: String) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        val old = c.getMediaItemAt(index)
        val extras = android.os.Bundle().apply { putBoolean(VideoMediaSourceFactory.EXTRA_IS_VIDEO, true) }
        replaceAt(index, old.buildUpon().setUri(Uri.parse(videoUri))
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build()).build())
    }

    /** 🎵 に戻した時: ライブラリの曲を、いつもの音声ファイルに戻す */
    fun restoreAudio(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        val old = c.getMediaItemAt(index)
        if (!VideoMediaSourceFactory.isVideo(old)) return
        val t = old.mediaId.toLongOrNull()?.let { Repository.track(it) } ?: return
        if (t.uri == t.videoUri) return // 音声ファイルを消した曲は動画の音のまま
        replaceAt(index, t.toMediaItem())
    }

    private fun replaceAt(index: Int, item: MediaItem) {
        val c = controller ?: return
        val pos = if (index == c.currentMediaItemIndex) c.currentPosition else 0L
        val playing = c.playWhenReady
        c.replaceMediaItem(index, item)
        if (index == c.currentMediaItemIndex) { c.seekTo(index, pos); if (playing) c.play() }
    }

    /** 今の曲の次に再生 */
    fun playNext(track: Track) {
        val c = controller ?: return
        if (!Net.canPlayNow(track)) {
            Toast.makeText(context, if (Repository.isOnline(track.id)) "☁ オンラインの曲です(${Net.reason()})" else "ファイルが見つかりません",
                Toast.LENGTH_SHORT).show(); return
        }
        if (c.mediaItemCount == 0) {
            play(listOf(track), 0); return
        }
        c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
        Toast.makeText(context, "次に再生します", Toast.LENGTH_SHORT).show()
    }

    fun addToQueue(all: List<Track>) {
        val c = controller ?: return
        val tracks = all.filter { Net.canPlayNow(it) }
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) {
            play(tracks, 0); return
        }
        c.addMediaItems(tracks.map { it.toMediaItem() })
        Toast.makeText(context, "再生キューに追加しました", Toast.LENGTH_SHORT).show()
    }

    /** 取得済みの URL で 1 曲だけ試聴(動画プレビューの「音楽で聴く」) */
    fun playStream(streamUrl: String, r: Searcher.Result, startMs: Long = 0L) {
        val c = controller ?: run { connect(); return }
        PlayLog.setCurrent(null, emptyList())
        c.shuffleModeEnabled = false
        c.setMediaItem(streamItem(r).buildUpon().setUri(Uri.parse(streamUrl)).build(), startMs.coerceAtLeast(0L))
        c.prepare()
        c.play()
    }

    companion object {
        fun videoItem(r: Searcher.Result, s: InnerTube.VideoSources): MediaItem {
            val split = s.video != null && s.audio != null
            val uri = if (split) s.video!! else s.progressive ?: s.audio!!
            val extras = android.os.Bundle().apply {
                putBoolean(VideoMediaSourceFactory.EXTRA_IS_VIDEO, true)
                if (split) putString(VideoMediaSourceFactory.EXTRA_AUDIO_URL, s.audio)
            }
            return streamItem(r).buildUpon()
                .setUri(Uri.parse(uri))
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build())
                .build()
        }

        fun streamItem(r: Searcher.Result): MediaItem {
            // 前に別の音源で流せた曲は、最初からそちらを使う
            val vid = (r.videoId ?: r.id).let { VideoAlias.get(it) ?: it }
            val meta = MediaMetadata.Builder()
                .setTitle(r.title)
                .setArtist(r.artist.ifBlank { null })
                .apply { r.thumb?.let { setArtworkUri(Uri.parse(bigArt(it))) } }
                .build()
            return MediaItem.Builder()
                .setMediaId(STREAM_PREFIX + vid)
                .setUri(Uri.parse(STREAM_SCHEME + "://v/" + vid))
                .setMediaMetadata(meta)
                .build()
        }

        /** YouTube Music のジャケット URL を大きいサイズに(ロック画面・通知・再生画面用) */
        fun bigArt(url: String): String =
            if (url.contains("googleusercontent") || url.contains("ggpht")) url.replace(Regex("=w\\d+-h\\d+"), "=w1000-h1000")
            else url

        /** 試聴中(ストリーミング)の mediaId の接頭辞 */
        const val STREAM_PREFIX = "stream:"
        /** 再生時に本当の URL に置き換える仮の URL */
        const val STREAM_SCHEME = "ytstream"

        /** 動画が読めない時の代わり: 同じ曲を音声だけで(番が来た時に URL を取る) */
        fun audioOnly(item: MediaItem, vid: String): MediaItem = item.buildUpon()
            .setUri(Uri.parse("$STREAM_SCHEME://v/$vid"))
            .setRequestMetadata(MediaItem.RequestMetadata.EMPTY)
            .build()

        fun currentTrack(p: Player?): Track? =
            p?.currentMediaItem?.mediaId?.toLongOrNull()?.let { Repository.track(it) }
    }
}

fun Track.toMediaItem(): MediaItem {
    val meta = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist.ifBlank { null })
        .setAlbumTitle(album.ifBlank { null })
        // ロック画面・通知用: 高解像度のジャケットがあればそちらを
        .apply {
            val local = (HiArt.cachedPath(this@toMediaItem) ?: thumbPath)?.takeIf { File(it).exists() }
            // ジャケットが無い YouTube 由来の曲(☁ オンライン追加の直後など)はネットのサムネイル
            val net = if (local == null) youtubeVideoId(sourceUrl)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" } else null
            when {
                local != null -> setArtworkUri(Uri.fromFile(File(local)))
                net != null -> setArtworkUri(Uri.parse(net))
            }
        }
        .build()
    // ファイルが無くても YouTube 由来ならネットから(オンラインの曲)。番号はライブラリの曲のまま
    val vid = if (Repository.isMissing(id)) youtubeVideoId(sourceUrl) else null
    val playUri = if (vid != null) PlayerConnection.STREAM_SCHEME + "://v/" + (VideoAlias.get(vid) ?: vid) else uri
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(Uri.parse(playUri))
        .setMediaMetadata(meta)
        .build()
}

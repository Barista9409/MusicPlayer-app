package com.example.ytmp3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.launch

/**
 * 再生画面用の高解像度ジャケット(一覧用の小さい画像とは別に、最大 1000px)。
 * ライブラリの曲: ファイルに埋め込まれた画像 → だめなら YouTube の高画質サムネイル。
 * 試聴の曲: 大きいサイズの URL。取得した画像は端末に保存して使い回す。
 */
object HiArt {
    private const val MAX = 1000

    /** 保存済みの高解像度画像(無ければ null)。編集で選んだジャケットがある曲は使わない */
    fun cachedPath(t: Track): String? {
        if (t.thumbPath?.contains("edit_") == true) return null
        val f = File(File(Repository.thumbDir.parentFile, "thumbs_hi"), "${t.id}.jpg")
        return f.takeIf { it.exists() }?.absolutePath
    }

    /** まとめて取得の進み具合(取得中でなければ null) */
    val progress = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Int>?>(null)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** 全曲の高解像度ジャケットを先に取っておく(取得済みは飛ばす)。戻り値: 新しく取れた数 */
    fun prefetchAll(c: Context, done: (Int) -> Unit = {}) {
        if (progress.value != null) return
        val app = c.applicationContext
        val list = Repository.tracks.value.filter { cachedPath(it) == null && it.thumbPath?.contains("edit_") != true }
        progress.value = 0 to list.size
        scope.launch {
            var got = 0
            val sem = kotlinx.coroutines.sync.Semaphore(3)
            var n = 0
            kotlinx.coroutines.coroutineScope {
                list.forEach { t ->
                    launch {
                        sem.acquire()
                        try {
                            if (runCatching { load(app, t.id.toString(), null) }.getOrNull() != null) synchronized(this@HiArt) { got++ }
                        } finally {
                            sem.release()
                            synchronized(this@HiArt) { n++; progress.value = n to list.size }
                        }
                    }
                }
            }
            progress.value = null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { done(got) }
        }
    }

    private fun dir(c: Context) = File(c.filesDir, "thumbs_hi").apply { mkdirs() }

    private fun fileFor(c: Context, key: String) = File(dir(c), key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    /** ダウンロード時に元の大きい画像を保存しておく */
    fun save(c: Context, trackId: Long, src: Bitmap) {
        runCatching { write(fileFor(c, trackId.toString()), square(src)) }
    }

    /** 高解像度の画像(取れなければ null)。通信やファイル読み込みがあるので裏で呼ぶ */
    fun load(c: Context, mediaId: String, artworkUri: String?): Bitmap? {
        val trackId = mediaId.toLongOrNull()
        // 「情報を編集」で選んだジャケットを優先
        trackId?.let { Repository.track(it) }?.thumbPath?.takeIf { it.contains("edit_") }?.let { p ->
            return BitmapFactory.decodeFile(p)
        }
        val key = trackId?.toString() ?: mediaId
        val f = fileFor(c, key)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { return it }

        val bmp: Bitmap? = if (trackId != null) {
            val t = Repository.track(trackId) ?: return null
            embedded(c, t.uri)?.takeIf { minOf(it.width, it.height) >= 500 }
                ?: youtubeVideoId(t.sourceUrl)?.let { fromYouTube(it) }
        } else {
            val big = artworkUri?.takeIf { it.contains("googleusercontent") || it.contains("ggpht") }
                ?.replace(Regex("=w\\d+-h\\d+[^/]*$"), "=w${MAX}-h${MAX}")
            big?.let { download(it) } ?: StreamDownload.videoId(androidx.media3.common.MediaItem.Builder().setMediaId(mediaId).build())
                ?.let { fromYouTube(it) }
        }
        bmp ?: return null
        val sq = square(bmp)
        runCatching { write(f, sq) }
        return sq
    }

    private fun embedded(c: Context, uri: String): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(c, Uri.parse(uri))
            mmr.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** YouTube の高画質サムネイル(大きい順に試す) */
    private fun fromYouTube(vid: String): Bitmap? =
        listOf("maxresdefault", "sddefault", "hqdefault").firstNotNullOfOrNull { name ->
            download("https://i.ytimg.com/vi/$vid/$name.jpg")?.takeIf { it.width >= 400 }
        }

    private fun download(url: String): Bitmap? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 8000
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** 真ん中を正方形に切り抜いて最大 1000px に */
    private fun square(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return if (side > MAX) Bitmap.createScaledBitmap(sq, MAX, MAX, true) else sq
    }

    private fun write(f: File, bmp: Bitmap) {
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }
}

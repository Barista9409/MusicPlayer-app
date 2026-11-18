package com.example.ytmp3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import coil.dispose
import coil.load
import java.util.Locale

object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(path: String?): Bitmap? {
        if (path == null) return null
        cache.get(path)?.let { return it }
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        cache.put(path, bmp)
        return bmp
    }

    /** ネット上の画像(検索結果・試聴中) */
    fun bindUrl(view: ImageView, url: String?) {
        if (url.isNullOrBlank()) { view.setImageResource(R.drawable.art_placeholder); return }
        view.load(url) {
            crossfade(true)
            placeholder(R.drawable.art_placeholder)
            error(R.drawable.art_placeholder)
        }
    }

    /** ライブラリの曲ならローカル、試聴中ならネットの画像 */
    fun bindItem(view: ImageView, mediaId: String?, artworkUri: android.net.Uri?) {
        val t = mediaId?.toLongOrNull()?.let { Repository.track(it) }
        val local = t?.thumbPath?.takeIf { java.io.File(it).exists() }
        // ライブラリの曲でもジャケットが無ければ、ネットのサムネイル(☁ オンラインの曲など)
        if (local != null || artworkUri == null || artworkUri.scheme?.startsWith("http") != true) bind(view, t?.thumbPath)
        else bindUrl(view, artworkUri.toString())
    }

    /**
     * 端末の画像を表示。読み込みは裏で、表示の大きさに縮めて(一覧のスクロールや曲の切り替わりでカクつかないように)。
     * 一度読んだものは記憶から、すぐに出る
     */
    fun bind(view: ImageView, path: String?) {
        if (path == null) { view.dispose(); view.setImageResource(R.drawable.art_placeholder); return }
        cache.get(path)?.let { view.dispose(); view.setImageBitmap(it); return }
        view.load(java.io.File(path)) {
            crossfade(120)
            placeholder(R.drawable.art_placeholder)
            error(R.drawable.art_placeholder)
        }
    }
}

fun formatTime(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun formatTotal(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60}時間${m % 60}分" else "${m}分"
}

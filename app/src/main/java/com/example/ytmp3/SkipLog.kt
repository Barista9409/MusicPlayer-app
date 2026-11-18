package com.example.ytmp3

import android.content.Context
import androidx.media3.common.MediaItem
import org.json.JSONObject

/**
 * すぐ飛ばした曲(再生して 30 秒以内に ⏭ で次へ進んだ曲)の記録。
 * あなたのミックス・ラジオで、しばらく(30 日)出にくくするのに使う
 */
object SkipLog {
    private const val KEEP_MS = 30L * 86_400_000L
    private lateinit var app: Context
    /** 曲の鍵("v:動画ID" / "t:曲名|アーティスト") → 飛ばした時刻 */
    private val map = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun init(c: Context) {
        app = c.applicationContext
        runCatching {
            val o = JSONObject(prefs().getString("skips", "{}") ?: "{}")
            val cut = System.currentTimeMillis() - KEEP_MS
            o.keys().forEach { k -> o.getLong(k).takeIf { it > cut }?.let { map[k] = it } }
            val p = JSONObject(prefs().getString("partial", "{}") ?: "{}")
            p.keys().forEach { k -> p.getLong(k).takeIf { it > cut }?.let { partial[k] = it } }
        }
    }

    private fun prefs() = app.getSharedPreferences("skip_log", Context.MODE_PRIVATE)

    /** 曲名とアーティストから作る鍵(表記ゆれに強く) */
    fun titleKey(title: String, artist: String) =
        "t:" + normalizeForDup(title) + "|" + normalizeForDup(Radio.mainArtist(artist))

    fun keysOf(vid: String?, title: String, artist: String) =
        listOfNotNull(vid?.let { "v:$it" }, title.takeIf { it.isNotBlank() }?.let { titleKey(it, artist) })

    fun keysOf(item: MediaItem): List<String> {
        val track = item.mediaId.toLongOrNull()?.let { Repository.track(it) }
        val vid = StreamDownload.videoId(item) ?: track?.sourceUrl?.let { youtubeVideoId(it) }
        val title = track?.title ?: item.mediaMetadata.title?.toString().orEmpty()
        val artist = track?.artist ?: item.mediaMetadata.artist?.toString().orEmpty()
        return keysOf(vid, title, artist)
    }

    fun record(item: MediaItem) {
        if (!::app.isInitialized) return
        val now = System.currentTimeMillis()
        keysOf(item).forEach { map[it] = now }
        // 古いものを片付けて、多すぎる時は減らす
        val cut = now - KEEP_MS
        map.entries.removeIf { it.value < cut }
        if (map.size > 600) map.entries.sortedBy { it.value }.take(map.size - 500).forEach { map.remove(it.key) }
        runCatching { prefs().edit().putString("skips", JSONObject(map as Map<*, *>).toString()).apply() }
    }

    /** ミックス・ラジオで自動で流れて、8 割まで聴かずに次へ進んだ曲(曲調の種に数えない) → 時刻 */
    private val partial = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun recordPartial(item: MediaItem) {
        if (!::app.isInitialized) return
        val now = System.currentTimeMillis()
        keysOf(item).forEach { partial[it] = now }
        partial.entries.removeIf { it.value < now - KEEP_MS }
        runCatching { prefs().edit().putString("partial", JSONObject(partial as Map<*, *>).toString()).apply() }
    }

    /** 最後まで(8 割以上)聴いた: 途中で飛ばした記録を消す(また曲調の種に数える) */
    fun clearPartial(item: MediaItem) {
        if (!::app.isInitialized) return
        val keys = keysOf(item)
        if (keys.none { partial.containsKey(it) }) return
        keys.forEach { partial.remove(it) }
        runCatching { prefs().edit().putString("partial", JSONObject(partial as Map<*, *>).toString()).apply() }
    }

    fun isPartial(keys: List<String>): Boolean {
        val cut = System.currentTimeMillis() - KEEP_MS
        return keys.any { k -> (partial[k] ?: 0L) > cut }
    }

    /** 最近すぐ飛ばした曲か */
    fun isSkipped(keys: List<String>): Boolean {
        val cut = System.currentTimeMillis() - KEEP_MS
        return keys.any { k -> (map[k] ?: 0L) > cut }
    }
}

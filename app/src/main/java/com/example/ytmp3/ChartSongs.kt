package com.example.ytmp3

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * ホームの「いま人気の曲」: YouTube Music の日本の週間ランキング(上位 100 曲)。
 * ランキングは週 1 回しか変わらないので、取った結果は 6 時間覚えておく(ホームを開くたびに通信しない)。
 * 表示するのは、まだ知らない曲だけ(ライブラリにある・お気に入り・No Good・最近試聴した曲は外す)
 */
object ChartSongs {
    /** ランキングの全曲(順位順) */
    val all = MutableStateFlow<List<Searcher.Result>>(emptyList())
    val loading = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    private const val KEEP_MS = 6 * 3600_000L
    private const val KEY = "chart_songs"
    private const val KEY_AT = "chart_songs_at"

    fun refresh(c: Context, force: Boolean = false) {
        val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (all.value.isEmpty()) all.value = read(p.getString(KEY, null))
        if (running) return
        if (!force && all.value.isNotEmpty() && System.currentTimeMillis() - p.getLong(KEY_AT, 0) < KEEP_MS) return
        running = true
        loading.value = true
        scope.launch {
            try {
                val songs = fetch()
                if (songs.isNotEmpty()) {
                    all.value = songs
                    p.edit().putString(KEY, save(songs)).putLong(KEY_AT, System.currentTimeMillis()).apply()
                }
            } catch (e: Exception) {
                android.util.Log.w("ChartSongs", "ランキングを取れませんでした", e)
            } finally {
                running = false
                loading.value = false
            }
        }
    }

    /** ランキングのページから「曲」の一覧(トップ 100 ソング)を選んで、その曲を取る */
    private fun fetch(): List<Searcher.Result> {
        val lists = InnerTube.charts()
        // 曲のランキングを選ぶ(ミュージックビデオ・急上昇・アーティストではないもの)。見つからなければ最初の一覧
        val songs = lists.firstOrNull { l ->
            val t = l.title.lowercase()
            (t.contains("ソング") || t.contains("song")) && !t.contains("急上昇") && !t.contains("trending")
        } ?: lists.firstOrNull() ?: return emptyList()
        return InnerTube.listTracks(songs).filter { it.videoId != null }.distinctBy { it.videoId }.take(100)
    }

    /** 表示する曲: まだ知らない曲だけ(順位順のまま) */
    fun fresh(): List<Searcher.Result> {
        val owned = VideoAlias.ownedIds(Repository.tracks.value)
        val recentStreams = PlayLog.recent().filter { it.kind == PlayLog.Kind.STREAM }.map { it.key }.toSet()
        return all.value.filter { r ->
            val v = r.videoId ?: return@filter false
            v !in owned && v !in recentStreams && !Liked.contains(v) && !NoGood.isSong(v) &&
                !NoGood.isArtist(Radio.mainArtist(r.artist)) && !LibraryMatch.hasSong(r.title, r.artist)
        }
    }

    private fun read(s: String?): List<Searcher.Result> {
        val a = runCatching { JSONArray(s ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val id = o.optString("id")
            Searcher.Result(Searcher.Kind.SONG, id, "https://music.youtube.com/watch?v=$id", o.optString("title"),
                o.optString("artist"), o.optLong("dur"), o.optString("thumb").ifBlank { null })
        }
    }

    private fun save(l: List<Searcher.Result>) = JSONArray().apply {
        l.forEach { r ->
            put(JSONObject().put("id", r.videoId).put("title", r.title).put("artist", r.artist)
                .put("dur", r.durationSec).put("thumb", r.thumb ?: ""))
        }
    }.toString()
}

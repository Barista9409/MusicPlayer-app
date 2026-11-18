package com.example.ytmp3

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 今日のディスカバー: よく聴く曲のラジオから、ライブラリに無い曲を 30 曲(1 日 1 回入れ替え) */
object DailyDiscover {
    val list = MutableStateFlow<List<Searcher.Result>>(emptyList())
    /** 取り直し中(ホームで「更新中」と出す) */
    val loading = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    /** 候補のたまり場(ラジオで見つけた曲。「↻ 更新」はここから選び直すのですぐ切り替わる) */
    private var pool: List<Searcher.Result> = emptyList()

    private fun today() = android.text.format.DateFormat.format("yyyyMMdd", System.currentTimeMillis()).toString()

    /** force = ホームの「↻ 更新」: 今日の分があっても入れ替える(違う曲に) */
    fun refresh(c: Context, force: Boolean = false) {
        val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (list.value.isEmpty()) list.value = read(p.getString("discover", null))
        if (pool.isEmpty()) pool = read(p.getString("discover_pool", null))
        if (running) return
        if (!force && p.getString("discover_day", "") == today()) return
        // 候補が十分あれば、通信せずにすぐ選び直す(裏で次の分の候補を集めておく)
        if (force) {
            val quick = pick(p, exclude = list.value.mapNotNull { it.videoId }.toSet())
            if (quick.size >= 15) {
                show(p, quick)
                collect(p, showWhenDone = false)
                return
            }
        }
        collect(p, showWhenDone = true)
    }

    /** No Good にした曲をすぐ消す */
    fun drop(vid: String) { list.value = list.value.filterNot { it.videoId == vid } }

    private fun show(p: android.content.SharedPreferences, l: List<Searcher.Result>) {
        list.value = l
        // 最近出した曲(次は避ける)
        val seen = (l.mapNotNull { it.videoId } + p.getString("discover_seen", "").orEmpty().split(",").filter { it.isNotBlank() })
            .distinct().take(300)
        p.edit().putString("discover", save(l)).putString("discover_day", today()).putString("discover_seen", seen.joinToString(",")).apply()
    }

    /** 候補から 30 曲: 持っていない・お気に入りでない・No Good でない曲を、最近出していないものを優先して、同じアーティストは 2 曲まで */
    private fun pick(p: android.content.SharedPreferences, exclude: Set<String>): List<Searcher.Result> {
        val owned = VideoAlias.ownedIds(Repository.tracks.value)
        val seen = p.getString("discover_seen", "").orEmpty().split(",").toSet()
        val ok = pool.filter { r ->
            val v = r.videoId ?: return@filter false
            v !in owned && v !in exclude && !Liked.contains(v) && !NoGood.isSong(v) && !NoGood.isArtist(Radio.mainArtist(r.artist)) &&
                !LibraryMatch.hasSong(r.title, r.artist) // 動画が違っても、同じ曲がライブラリにあれば省く
        }
        val (fresh, old) = ok.shuffled().partition { it.videoId !in seen }
        // 同じアーティストは 2 曲まで
        return RecoRules.capPerArtist(fresh + old, 2) { it.artist }.take(30)
    }

    /** ラジオで候補を集め直す。種はよく聴く曲・最近の曲から毎回ちがう組み合わせで */
    private fun collect(p: android.content.SharedPreferences, showWhenDone: Boolean) {
        running = true
        if (showWhenDone) loading.value = true
        scope.launch {
            try {
                val tracks = Repository.tracks.value
                val counts = PlayLog.counts(60)
                val top = tracks.sortedByDescending { counts[it.id] ?: 0 }.mapNotNull { youtubeVideoId(it.sourceUrl) }.distinct().take(30)
                val recent = tracks.sortedByDescending { it.savedAt }.mapNotNull { youtubeVideoId(it.sourceUrl) }.distinct().take(20)
                val liked = Liked.list.value.mapNotNull { it.videoId }.take(20)
                // 初期設定で選んだ好きなアーティストの人気曲も種に(まだ曲が少ない人でもおすすめが出るように)
                val fav = p.getString("seed_artists", "").orEmpty().split("\n").filter { it.isNotBlank() }.shuffled().take(3)
                    .flatMap { a -> runCatching { InnerTube.artistTopSongs(a)?.songs.orEmpty() }.getOrDefault(emptyList()).mapNotNull { it.videoId }.shuffled().take(2) }
                val seeds = (top.shuffled().take(4) + recent.shuffled().take(1) + liked.shuffled().take(1) +
                    fav.take(if (tracks.size < 30) 4 else 1)).distinct()
                if (seeds.isEmpty()) return@launch
                val found = seeds.flatMap { s -> runCatching { InnerTube.radio(s) }.getOrDefault(emptyList()) }
                    .filter { it.videoId != null && it.videoId !in seeds }
                    .distinctBy { it.videoId }
                if (found.isEmpty()) return@launch
                pool = found.take(200)
                p.edit().putString("discover_pool", save(pool)).apply()
                if (showWhenDone) {
                    val l = pick(p, exclude = list.value.mapNotNull { it.videoId }.toSet()).ifEmpty { pick(p, emptySet()) }
                    if (l.isNotEmpty()) show(p, l)
                }
            } finally {
                running = false
                loading.value = false
            }
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

package com.example.ytmp3

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** ホームの「新作」: よく聴くアーティストの、ライブラリに無い最近のアルバム・シングル(12 時間ごとに更新) */
object NewReleases {
    val list = MutableStateFlow<List<InnerTube.Release>>(emptyList())
    /** 似ているアーティスト(ライブラリにいないアーティスト) */
    val related = MutableStateFlow<List<InnerTube.ArtistRef>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    /** 取り直し中(ホームで「更新中」と出す) */
    val loading = MutableStateFlow(false)
    /** おすすめのアーティストの候補(「↻ 更新」はここから選び直すのですぐ切り替わる) */
    private var relatedPool: List<InnerTube.ArtistRef> = emptyList()

    /** おすすめのアーティストだけ入れ替える。候補が十分あれば通信なしですぐ */
    fun refreshRelated(c: Context) {
        val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (relatedPool.isEmpty()) relatedPool = readRelated(p.getString("related_pool", null))
        val quick = pickRelated(p, exclude = related.value.map { it.id }.toSet())
        if (quick.size >= 8) {
            related.value = quick
            p.edit().putString("related_artists", saveRelated(quick)).apply()
            return
        }
        refresh(c, force = true)
    }

    /** No Good にしたアーティストをすぐ消す */
    fun dropArtist(id: String) { related.value = related.value.filterNot { it.id == id } }

    /** 候補から 12 人: ライブラリにいない・No Good でない人を、最近出していない人を優先してランダムに */
    private fun pickRelated(p: android.content.SharedPreferences, exclude: Set<String>): List<InnerTube.ArtistRef> {
        val seen = p.getString("related_seen", "").orEmpty().split(",").toSet()
        // ライブラリに曲がある人(共演者も含む・表記ゆれも見る)は省く
        val ok = relatedPool.filter { !LibraryMatch.hasArtist(it.name) && it.id !in exclude && !NoGood.isArtist(it.id) && !NoGood.isArtist(it.name) }
        val (fresh, old) = ok.shuffled().partition { it.id !in seen }
        val picked = (fresh + old).take(12)
        val s2 = (picked.map { it.id } + seen.filter { it.isNotBlank() }).distinct().take(200)
        p.edit().putString("related_seen", s2.joinToString(",")).apply()
        return picked
    }

    /** force = ホームの「↻ 更新」: 時間が経っていなくても取り直す */
    fun refresh(c: Context, force: Boolean = false) {
        val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (list.value.isEmpty()) load(p.getString("new_releases", null))
        if (related.value.isEmpty()) loadRelated(p.getString("related_artists", null))
        if (running || (!force && System.currentTimeMillis() - p.getLong("new_releases_at", 0L) < 12 * 3600_000L)) return
        running = true
        if (force) loading.value = true
        scope.launch {
            try {
                val tracks = Repository.tracks.value
                val ids = tracks.associateBy { it.id }
                val score = HashMap<String, Int>()
                PlayLog.counts(60).forEach { (id, n) ->
                    ids[id]?.takeIf { it.artist.isNotBlank() }?.let { val a = Radio.mainArtist(it.artist); score[a] = (score[a] ?: 0) + n }
                }
                val artists = (score.entries.sortedByDescending { it.value }.map { it.key } +
                    tracks.filter { it.artist.isNotBlank() }.groupingBy { Radio.mainArtist(it.artist) }.eachCount()
                        .entries.sortedByDescending { it.value }.map { it.key }).distinct()
                    // よく聴く 8 人 + それ以外から毎回ちがう 4 人(いつも同じにならないように)
                    .let { l -> l.take(8) + l.drop(8).take(40).shuffled().take(4) }
                    // 初期設定で選んだ好きなアーティストも
                    .let { l -> (l + p.getString("seed_artists", "").orEmpty().split("\n").filter { it.isNotBlank() }).distinct().take(16) }
                val owned = tracks.map { it.album.lowercase() }.filter { it.isNotBlank() }.toSet()
                val minYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 1
                val rel = mutableListOf<InnerTube.ArtistRef>()
                val all = artists.flatMap { name ->
                    runCatching {
                        val (real, id) = InnerTube.findArtist(name) ?: return@runCatching emptyList()
                        val (releases, similar) = InnerTube.artistPage(real, id)
                        rel += similar.take(10)
                        releases
                    }.getOrDefault(emptyList())
                }.toMutableList()
                // 少なければ、似ているアーティストの新作も足す(ディスカバーと同じくらい並ぶように)
                if (all.count { it.year >= minYear } < 25) {
                    val extra = rel.groupBy { it.id }.entries.sortedByDescending { it.value.size }.map { it.value.first() }.take(8)
                    extra.forEach { a ->
                        runCatching { InnerTube.artistPage(a.name, a.id).first }.getOrNull()?.let { all += it }
                    }
                }
                val fresh = all.filter { it.result.title.lowercase() !in owned }.distinctBy { it.result.id }
                val recent = fresh.filter { it.year >= minYear }
                // それでも少なければ 1 年前まで広げる
                val found = (if (recent.size >= 20) recent else fresh.filter { it.year >= minYear - 1 })
                    .sortedByDescending { it.year }.take(30)
                list.value = found
                // 似ているアーティスト: 候補をためておき、その中からランダムに(毎回ちがう顔ぶれ)
                relatedPool = rel.groupBy { it.id }.entries.sortedByDescending { it.value.size }.map { it.value.first() }.take(100)
                p.edit().putString("related_pool", saveRelated(relatedPool)).apply()
                val relList = pickRelated(p, exclude = if (force) related.value.map { it.id }.toSet() else emptySet())
                    .ifEmpty { pickRelated(p, emptySet()) }
                related.value = relList
                p.edit().putString("new_releases", save(found)).putString("related_artists", saveRelated(relList))
                    .putLong("new_releases_at", System.currentTimeMillis()).apply()
            } finally {
                running = false
                loading.value = false
            }
        }
    }

    private fun saveRelated(l: List<InnerTube.ArtistRef>) = JSONArray().apply {
        l.forEach { put(JSONObject().put("name", it.name).put("id", it.id).put("thumb", it.thumb ?: "")) }
    }.toString()

    private fun loadRelated(s: String?) { related.value = readRelated(s) }

    private fun readRelated(s: String?): List<InnerTube.ArtistRef> {
        val a = runCatching { JSONArray(s ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { InnerTube.ArtistRef(it.optString("name"), it.optString("id"), it.optString("thumb").ifBlank { null }) }
    }

    private fun save(l: List<InnerTube.Release>) = JSONArray().apply {
        l.forEach { r ->
            put(JSONObject().put("id", r.result.id).put("title", r.result.title).put("artist", r.result.artist)
                .put("thumb", r.result.thumb ?: "").put("year", r.year))
        }
    }.toString()

    private fun load(s: String?) {
        val a = runCatching { JSONArray(s ?: return) }.getOrNull() ?: return
        list.value = (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            InnerTube.Release(Searcher.Result(Searcher.Kind.ALBUM, id, "https://music.youtube.com/browse/$id",
                o.optString("title"), o.optString("artist"), 0, o.optString("thumb").ifBlank { null }), o.optInt("year"))
        }
    }
}

package com.example.ytmp3

import android.content.Context
import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 再生の記録(ホーム画面用)。スマホの中(playlog.json)だけに保存する。
 * - 30 秒以上 または 曲の半分以上 聴いたら「1 回再生」
 * - どのプレイリスト/アルバム/アーティスト/試聴から聴いたかも記録
 * - ホームにピン留めしたもの
 */
object PlayLog {
    enum class Kind { PLAYLIST, ALBUM, ARTIST, TRACK, STREAM }

    /** 再生のまとまり(プレイリスト・アルバムなど)。STREAM は試聴(data に検索結果の JSON) */
    data class Ref(val kind: Kind, val key: String, val title: String, val subtitle: String = "", val thumb: String? = null, val data: String = "") {
        val id get() = "${kind.name}:$key"
    }

    private data class Event(val trackId: Long, val time: Long, val ctx: String?)
    private data class Recent(val ref: Ref, val time: Long)

    private lateinit var file: File
    private val events = mutableListOf<Event>()
    private val recents = mutableListOf<Recent>()
    private val pins = mutableListOf<Ref>()

    /** 変わったら増える(ホーム画面の更新用) */
    val changed = MutableStateFlow(0L)

    /** 今のキューのまとまり(再生を始めた時に設定) */
    @Volatile var current: Ref? = null

    private const val DAY = 24 * 60 * 60 * 1000L
    private const val KEEP_DAYS = 90
    private const val MAX_RECENTS = 20

    private var curFile: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "playlog.json")
        curFile = File(context.filesDir, "playlog_current.json")
        load()
        // 前回の「どこから聴いていたか」(アプリを閉じた後にウィジェットや車から続きを聴いた時も正しく数える)
        runCatching {
            val o = JSONObject(curFile!!.readText())
            val ids = o.optJSONArray("ids")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            val ref = o.optJSONObject("ref")?.let { refFromJson(it) }
            if (ref != null) { current = ref; ctxIds = ids.toSet(); AutoContinue.restoreQueue(ref.kind == Kind.PLAYLIST) }
        }
    }

    // ───────── まとまりの作り方 ─────────

    fun playlistRef(p: Playlist) = Ref(Kind.PLAYLIST, p.id.toString(), p.name)
    fun albumRef(t: Track) = Ref(Kind.ALBUM, t.album.lowercase(), t.album, t.artist)
    fun artistRef(name: String) = Ref(Kind.ARTIST, name, name)

    /** キューの曲から推測(全部同じアルバム/アーティストなら) */
    fun infer(tracks: List<Track>): Ref? {
        if (tracks.size < 2) return null
        val albums = tracks.map { it.album.lowercase() }.distinct()
        if (albums.size == 1 && albums[0].isNotBlank()) return albumRef(tracks[0])
        val artists = tracks.map { it.artistOrUnknown }.distinct()
        if (artists.size == 1 && tracks[0].artist.isNotBlank()) return artistRef(artists[0])
        return null
    }

    fun streamRef(r: Searcher.Result): Ref {
        val sub = when {
            r.id.startsWith("RDAMVM") -> "ラジオ"
            r.kind == Searcher.Kind.ALBUM -> "アルバム · 試聴"
            r.kind == Searcher.Kind.PLAYLIST -> "プレイリスト · 試聴"
            else -> "試聴"
        }.let { if (r.artist.isNotBlank()) "$it · ${r.artist}" else it }
        return Ref(Kind.STREAM, r.id, r.title, sub, r.thumb, resultToJson(r).toString())
    }

    fun resultOf(ref: Ref): Searcher.Result? = runCatching { resultFromJson(JSONObject(ref.data)) }.getOrNull()

    // ───────── 記録 ─────────

    /** 再生サービスから: この曲を 1 回聴いた */
    @Synchronized
    fun onPlayed(item: MediaItem) {
        if (!::file.isInitialized) return
        val now = System.currentTimeMillis()
        val ctx = current?.takeIf { item.mediaId in ctxIds || ctxIds.isEmpty() }
        val trackId = item.mediaId.toLongOrNull()
        if (trackId != null) {
            events += Event(trackId, now, ctx?.id)
            val t = Repository.track(trackId)
            addRecent(ctx ?: t?.let { Ref(Kind.TRACK, it.id.toString(), it.title, it.artist) }, now)
        } else {
            // 試聴: 回数には数えず「最近再生したもの」にだけ
            val vid = StreamDownload.videoId(item)
            val single = vid?.let {
                val m = item.mediaMetadata
                streamRef(Searcher.Result(Searcher.Kind.SONG, it, "https://music.youtube.com/watch?v=$it",
                    m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), 0, m.artworkUri?.toString()))
            }
            addRecent(ctx ?: single, now)
        }
        save()
        changed.value = now
    }

    /** 今のまとまりに入っている mediaId(違う曲に変わったらまとまり外として扱う) */
    @Volatile var ctxIds: Set<String> = emptySet()

    /** 再生を始める時に呼ぶ */
    fun setCurrent(ref: Ref?, mediaIds: Collection<String>) {
        current = ref
        AutoContinue.newQueue(ref?.kind == Kind.PLAYLIST)
        ctxIds = if (ref == null) emptySet() else mediaIds.toSet()
        runCatching {
            val o = JSONObject()
            if (ref != null) o.put("ref", refToJson(ref)).put("ids", JSONArray(ctxIds.toList()))
            curFile?.writeText(o.toString())
        }
    }

    private fun addRecent(ref: Ref?, time: Long) {
        ref ?: return
        // あなたのミックスは「最近再生したもの」に入れない(ホームの欄にいつもあり、中身も名前も変わるため)
        if (isMix(ref)) return
        recents.removeAll { it.ref.id == ref.id }
        recents.add(0, Recent(ref, time))
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
    }

    // ───────── 読み出し ─────────

    @Synchronized
    fun recent(): List<Ref> = recents.map { it.ref }.filterNot { isMix(it) }

    private fun isMix(ref: Ref) = ref.kind == Kind.STREAM && ref.key.startsWith("MIX")

    /** 最近 days 日の再生回数(曲ごと) */
    @Synchronized
    fun counts(days: Int = 30): Map<Long, Int> {
        val from = System.currentTimeMillis() - days * DAY
        return events.filter { it.time >= from }.groupingBy { it.trackId }.eachCount()
    }

    /** 最近 days 日によく聴いたまとまり(プレイリスト・アルバム・アーティスト)の id → 回数 */
    @Synchronized
    fun groupScores(days: Int = 30): Map<String, Int> {
        val from = System.currentTimeMillis() - days * DAY
        val out = HashMap<String, Int>()
        for (e in events) {
            if (e.time < from) continue
            val key = e.ctx?.takeIf { !it.startsWith("TRACK:") && !it.startsWith("STREAM:") }
                ?: Repository.track(e.trackId)?.takeIf { it.album.isNotBlank() }?.let { albumRef(it).id }
                ?: continue
            out[key] = (out[key] ?: 0) + 1
        }
        return out
    }

    /** 以前はよく聴いたのに最近 30 日は聴いていない曲(以前の回数が多い順) */
    @Synchronized
    fun rediscover(): List<Long> {
        val cut = System.currentTimeMillis() - 30 * DAY
        val recentIds = events.filter { it.time >= cut }.map { it.trackId }.toSet()
        return events.filter { it.time < cut && it.trackId !in recentIds }
            .groupingBy { it.trackId }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    /** 記録に残っている曲(最近 90 日に 1 回でも聴いた曲) */
    @Synchronized
    fun playedIds(): Set<Long> = events.map { it.trackId }.toSet()

    /** from 以降の再生(曲 ID と時刻) */
    @Synchronized
    fun playsSince(from: Long): List<Pair<Long, Long>> = events.filter { it.time >= from }.map { it.trackId to it.time }

    /** 最近聴いた順の曲(重複なし) */
    @Synchronized
    fun recentTrackIds(limit: Int = 100): List<Long> =
        events.asReversed().map { it.trackId }.distinct().take(limit)

    @Synchronized
    fun pins(): List<Ref> = pins.toList()

    fun isPinned(ref: Ref) = synchronized(this) { pins.any { it.id == ref.id } }

    @Synchronized
    fun togglePin(ref: Ref): Boolean {
        val removed = pins.removeAll { it.id == ref.id }
        if (!removed) pins += ref
        save()
        changed.value = System.currentTimeMillis()
        return !removed
    }

    /** 再生の記録とピン留めを全部消す(ライブラリの初期化) */
    @Synchronized
    fun clearAll() {
        events.clear(); recents.clear(); pins.clear()
        save()
        changed.value = System.currentTimeMillis()
    }

    @Synchronized
    fun clearHistory() {
        events.clear(); recents.clear()
        save()
        changed.value = System.currentTimeMillis()
    }

    // ───────── 保存 ─────────

    private fun refToJson(r: Ref) = JSONObject().put("kind", r.kind.name).put("key", r.key).put("title", r.title)
        .put("sub", r.subtitle).put("thumb", r.thumb ?: "").put("data", r.data)

    private fun refFromJson(o: JSONObject) = Ref(
        Kind.valueOf(o.getString("kind")), o.getString("key"), o.optString("title"), o.optString("sub"),
        o.optString("thumb").ifBlank { null }, o.optString("data")
    )

    private fun resultToJson(r: Searcher.Result) = JSONObject().put("kind", r.kind.name).put("id", r.id).put("url", r.url)
        .put("title", r.title).put("artist", r.artist).put("dur", r.durationSec).put("thumb", r.thumb ?: "")

    private fun resultFromJson(o: JSONObject) = Searcher.Result(
        Searcher.Kind.valueOf(o.getString("kind")), o.getString("id"), o.getString("url"), o.optString("title"),
        o.optString("artist"), o.optLong("dur"), o.optString("thumb").ifBlank { null }
    )

    @Synchronized
    private fun save() {
        val from = System.currentTimeMillis() - KEEP_DAYS * DAY
        events.removeAll { it.time < from }
        val o = JSONObject()
        o.put("events", JSONArray().apply {
            events.forEach { put(JSONArray().put(it.trackId).put(it.time).put(it.ctx ?: "")) }
        })
        o.put("recents", JSONArray().apply { recents.forEach { put(refToJson(it.ref).put("time", it.time)) } })
        o.put("pins", JSONArray().apply { pins.forEach { put(refToJson(it)) } })
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    @Synchronized
    private fun load() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        events.clear(); recents.clear(); pins.clear()
        o.optJSONArray("events")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONArray(i) ?: continue
                events += Event(e.optLong(0), e.optLong(1), e.optString(2).ifBlank { null })
            }
        }
        o.optJSONArray("recents")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                runCatching { recents += Recent(refFromJson(r), r.optLong("time")) }
            }
        }
        o.optJSONArray("pins")?.let { a ->
            for (i in 0 until a.length()) runCatching { pins += refFromJson(a.getJSONObject(i)) }
        }
    }
}

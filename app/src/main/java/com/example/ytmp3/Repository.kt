package com.example.ytmp3

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ライブラリ(保存した曲)とプレイリストを JSON で永続化する */
object Repository {
    private lateinit var tracksFile: File
    private lateinit var ignoredFile: File

    /** 「ライブラリのみ削除」したファイル。フォルダ読み込みで再追加しない */
    private val _ignored = MutableStateFlow<Set<String>>(emptySet())
    val ignored: StateFlow<Set<String>> = _ignored.asStateFlow()
    private lateinit var playlistsFile: File
    lateinit var thumbDir: File
        private set

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /** ファイルが見つからない曲の id */
    private val _missing = MutableStateFlow<Set<Long>>(emptySet())
    val missing: StateFlow<Set<Long>> = _missing.asStateFlow()

    private val lock = Any()

    fun init(context: Context) {
        // history.json は v1.x からの互換
        tracksFile = File(context.filesDir, "history.json")
        playlistsFile = File(context.filesDir, "playlists.json")
        ignoredFile = File(context.filesDir, "ignored.json")
        _ignored.value = loadIgnored()
        thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }
        _tracks.value = loadTracks()
        _playlists.value = loadPlaylists()
        onlineFile = File(context.filesDir, "online.json")
        onlineIds = runCatching {
            val a = JSONArray(onlineFile.readText()); (0 until a.length()).map { a.getLong(it) }.toSet()
        }.getOrDefault(emptySet())
        _missing.value = _missing.value + onlineIds
    }

    /** ダウンロードを削除してオンラインに戻した曲(フォルダの読み込み直しでも覚えておく) */
    private lateinit var onlineFile: File
    @Volatile private var onlineIds: Set<Long> = emptySet()

    private fun saveOnline() = runCatching { onlineFile.writeText(JSONArray(onlineIds.toList()).toString()) }

    /** ダウンロードし直した時など: オンラインの印を外す */
    fun clearOnline(id: Long) {
        if (id !in onlineIds) return
        onlineIds = onlineIds - id; saveOnline()
        _missing.value = _missing.value - id
    }

    fun track(id: Long): Track? = index().byId[id]

    /** その動画 ID の曲(ライブラリの並び順) */
    fun tracksByVideo(vid: String): List<Track> = index().byVid[vid].orEmpty()

    /**
     * 曲を番号・動画 ID からすぐ引ける索引(曲の一覧が変わった時だけ作り直す)。
     * 毎回ライブラリ全体を先頭から探すと、全曲を調べる処理の中で呼ばれた時に曲数の 2 乗の手間になり、画面が固まるため
     */
    private class Index(val tracks: List<Track>) {
        val byId: Map<Long, Track> = tracks.associateBy { it.id }
        val byVid: Map<String, List<Track>> = tracks.mapNotNull { t -> youtubeVideoId(t.sourceUrl)?.let { it to t } }
            .groupBy({ it.first }, { it.second })
    }
    @Volatile private var idx: Index? = null
    private fun index(): Index {
        val ts = _tracks.value
        idx?.let { if (it.tracks === ts) return it }
        return Index(ts).also { idx = it }
    }
    fun playlist(id: Long): Playlist? = _playlists.value.firstOrNull { it.id == id }

    fun tracksOf(p: Playlist): List<Track> {
        val map = _tracks.value.associateBy { it.id }
        return p.trackIds.mapNotNull { map[it] }
    }

    // ───────── tracks ─────────

    fun addTrack(t: Track) = synchronized(lock) {
        _tracks.value = listOf(t) + _tracks.value
        saveTracks()
    }

    fun addTracks(list: List<Track>) = synchronized(lock) {
        if (list.isEmpty()) return
        _tracks.value = list + _tracks.value
        saveTracks()
    }

    /** id が一致する曲を置き換える */
    fun updateTracks(list: List<Track>) = synchronized(lock) {
        if (list.isEmpty()) return
        val map = list.associateBy { it.id }
        _tracks.value = _tracks.value.map { map[it.id] ?: it }
        saveTracks()
    }

    fun setMissing(ids: Set<Long>) {
        val all = ids + onlineIds
        if (_missing.value != all) _missing.value = all
    }

    fun isMissing(id: Long) = id in _missing.value

    /** ファイルは無いが YouTube から流せる(オンラインの曲) */
    fun isOnline(id: Long) = id in _missing.value && track(id)?.let { youtubeVideoId(it.sourceUrl) != null } == true

    /** 自分でオンラインにした(またはオンラインのまま追加した)曲。ダウンロードを勧めない */
    fun isIntentionalOnline(id: Long) = id in onlineIds

    /** 再生できない(ファイルが無く、ネットからも流せない) */
    fun cantPlay(id: Long) = id in _missing.value && !isOnline(id)

    /** ダウンロードを削除してオンラインに戻した(ファイルが無いのは意図したもの) */
    fun markOnline(ids: Set<Long>) {
        if (ids.isEmpty()) return
        onlineIds = onlineIds + ids; saveOnline()
        _missing.value = _missing.value + ids
    }

    fun clearIgnored() = synchronized(lock) {
        _ignored.value = emptySet()
        atomicWrite(ignoredFile, "[]")
    }

    /**
     * 曲をライブラリから外す。
     * @param ignore true ならファイルを覚えておき、フォルダ読み込みで再追加しない
     */
    fun removeTracks(ids: Set<Long>, ignore: Boolean = false) = synchronized(lock) {
        if (ids.isEmpty()) return
        if (ignore) {
            val keys = _tracks.value.filter { it.id in ids }.flatMap { it.allKeys }
            if (keys.isNotEmpty()) {
                _ignored.value = _ignored.value + keys
                atomicWrite(ignoredFile, JSONArray(_ignored.value.toList()).toString())
            }
        }
        val remaining = _tracks.value.filterNot { it.id in ids }
        val stillUsed = remaining.mapNotNull { it.thumbPath }.toSet()
        _tracks.value.filter { it.id in ids }.forEach { t ->
            t.thumbPath?.takeIf { it !in stillUsed }?.let { File(it).delete() }
        }
        _tracks.value = remaining
        _missing.value = _missing.value - ids
        _playlists.value = _playlists.value.map { p -> p.copy(trackIds = p.trackIds.filterNot { it in ids }) }
        saveTracks()
        savePlaylists()
    }

    /** 予備ファイルを本体に昇格(本体が見つからない時) */
    fun promoteAlt(id: Long, altUri: String, altKey: String?) = synchronized(lock) {
        _tracks.value = _tracks.value.map { t ->
            if (t.id != id) t else {
                val (folder, name) = altKey?.let { k -> k.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } to k.substringAfterLast('/') }
                    ?: (t.folder to t.fileName)
                t.copy(
                    uri = altUri, folder = folder, fileName = name,
                    altUris = (t.altUris - altUri) + t.uri,
                    altKeys = (t.altKeys - (altKey ?: "")).plus(t.fileKey).filter { it.isNotEmpty() },
                )
            }
        }
        saveTracks()
    }

    /**
     * 重複した曲(曲名 + アーティストが同じで長さの差が 3 秒以内)を 1 つにまとめる。
     * まとめた側のファイルは予備として残し、プレイリストの参照も付け替える。
     * @return まとめた曲数
     */
    fun mergeDuplicates(): Int = synchronized(lock) {
        val tracks = _tracks.value
        val missing = _missing.value
        val remap = HashMap<Long, Long>()
        val updated = HashMap<Long, Track>()
        tracks.filter { it.dupKey.isNotEmpty() }.groupBy { it.dupKey }.values.filter { it.size > 1 }.forEach { group ->
            val clusters = mutableListOf<MutableList<Track>>()
            for (t in group.sortedBy { it.id }) {
                val c = clusters.firstOrNull { durationClose(it[0], t) }
                if (c != null) c += t else clusters += mutableListOf(t)
            }
            for (c in clusters.filter { it.size > 1 }) {
                // 残す方: ファイルがある → ジャケットがある → 古い方
                val keep = c.sortedWith(compareBy({ it.id in missing }, { it.thumbPath == null }, { it.id })).first()
                var k = keep
                for (o in c) {
                    if (o.id == keep.id) continue
                    k = k.copy(
                        altUris = (k.altUris + o.uri + o.altUris).filter { it != k.uri }.distinct(),
                        altKeys = (k.altKeys + o.allKeys).filter { it.isNotEmpty() && it != k.fileKey }.distinct(),
                        album = k.album.ifBlank { o.album },
                        artist = k.artist.ifBlank { o.artist },
                        trackNo = if (k.trackNo > 0) k.trackNo else o.trackNo,
                        discNo = if (k.discNo > 0) k.discNo else o.discNo,
                        thumbPath = k.thumbPath ?: o.thumbPath,
                        sourceUrl = k.sourceUrl.ifBlank { o.sourceUrl },
                        durationMs = if (k.durationMs > 0) k.durationMs else o.durationMs,
                    )
                    remap[o.id] = keep.id
                }
                updated[keep.id] = k
            }
        }
        if (remap.isEmpty()) return 0
        val newTracks = tracks.filterNot { it.id in remap }.map { updated[it.id] ?: it }
        val stillUsed = newTracks.mapNotNull { it.thumbPath }.toSet()
        tracks.filter { it.id in remap }.forEach { t -> t.thumbPath?.takeIf { it !in stillUsed }?.let { File(it).delete() } }
        _tracks.value = newTracks
        _playlists.value = _playlists.value.map { p -> p.copy(trackIds = p.trackIds.map { remap[it] ?: it }.distinct()) }
        _missing.value = _missing.value - remap.keys
        saveTracks()
        savePlaylists()
        remap.size
    }

    private fun durationClose(a: Track, b: Track) =
        a.durationMs <= 0 || b.durationMs <= 0 || kotlin.math.abs(a.durationMs - b.durationMs) <= 3000

    // ───────── playlists ─────────

    fun createPlaylist(name: String, trackIds: List<Long> = emptyList()): Playlist = synchronized(lock) {
        val p = Playlist(newId(), name.ifBlank { "新しいプレイリスト" }, trackIds, System.currentTimeMillis())
        // 新しいプレイリストは一番上に
        _playlists.value = listOf(p) + _playlists.value
        savePlaylists()
        p
    }

    /** プレイリスト一覧の並び順(手動)を保存 */
    fun setPlaylistsOrder(ids: List<Long>) = synchronized(lock) {
        val map = _playlists.value.associateBy { it.id }
        val ordered = ids.mapNotNull { map[it] }
        _playlists.value = ordered + _playlists.value.filterNot { it.id in ids }
        savePlaylists()
    }

    /** v3.4 以前は作成順に保存していたので、初回だけ新しい順に揃える */
    fun migratePlaylistOrder(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("playlist_order_v2", false)) return
        synchronized(lock) {
            _playlists.value = _playlists.value.sortedByDescending { it.createdAt }
            savePlaylists()
        }
        prefs.edit().putBoolean("playlist_order_v2", true).apply()
    }

    fun renamePlaylist(id: Long, name: String) = updatePlaylist(id) { it.copy(name = name) }

    fun deletePlaylist(id: Long) = synchronized(lock) {
        _playlists.value = _playlists.value.filterNot { it.id == id }
        savePlaylists()
    }

    /** 重複は追加しない。追加した件数を返す */
    fun addToPlaylist(id: Long, trackIds: List<Long>): Int {
        var added = 0
        updatePlaylist(id) { p ->
            val news = trackIds.filterNot { it in p.trackIds }.distinct()
            added = news.size
            p.copy(trackIds = p.trackIds + news)
        }
        return added
    }

    /** 位置が分かっている曲を位置順に並べる(位置不明の曲は末尾に元の順で) */
    fun reorderByPositions(id: Long, positions: Map<Long, Int>) = updatePlaylist(id) { p ->
        p.copy(trackIds = p.trackIds.withIndex()
            .sortedWith(compareBy({ positions[it.value] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value })
    }

    fun setPlaylistOrder(id: Long, trackIds: List<Long>) = updatePlaylist(id) { it.copy(trackIds = trackIds) }

    private fun updatePlaylist(id: Long, f: (Playlist) -> Playlist) = synchronized(lock) {
        _playlists.value = _playlists.value.map { if (it.id == id) f(it) else it }
        savePlaylists()
    }

    private var lastId = 0L
    fun newId(): Long = synchronized(lock) {
        val now = System.currentTimeMillis()
        lastId = if (now > lastId) now else lastId + 1
        lastId
    }

    // ───────── persistence ─────────

    private fun loadTracks(): List<Track> = try {
        if (!tracksFile.exists()) emptyList() else {
            val arr = JSONArray(tracksFile.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Track(
                    id = o.getLong("id"),
                    title = o.getString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    uri = o.getString("uri"),
                    savedAt = o.getLong("savedAt"),
                    sizeBytes = o.optLong("sizeBytes"),
                    durationMs = o.optLong("durationMs"),
                    location = o.optString("location"),
                    thumbPath = o.optString("thumbPath").ifEmpty { null },
                    sourceUrl = o.optString("sourceUrl"),
                    folder = o.optString("folder"),
                    fileName = o.optString("fileName"),
                    source = o.optString("source").ifEmpty { Track.SOURCE_DOWNLOAD },
                    trackNo = o.optInt("trackNo"),
                    discNo = o.optInt("discNo"),
                    altUris = strings(o.optJSONArray("altUris")),
                    altKeys = strings(o.optJSONArray("altKeys")),
                    edited = o.optBoolean("edited"),
                    videoUri = o.optString("videoUri"),
                    videoBytes = o.optLong("videoBytes"),
                    videoVid = o.optString("videoVid"),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveTracks() {
        val arr = JSONArray()
        _tracks.value.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("title", it.title).put("artist", it.artist)
                    .put("album", it.album).put("uri", it.uri).put("savedAt", it.savedAt)
                    .put("sizeBytes", it.sizeBytes).put("durationMs", it.durationMs)
                    .put("location", it.location).put("thumbPath", it.thumbPath ?: "")
                    .put("sourceUrl", it.sourceUrl).put("folder", it.folder)
                    .put("fileName", it.fileName).put("source", it.source)
                    .put("trackNo", it.trackNo).put("discNo", it.discNo)
                    .put("altUris", JSONArray(it.altUris)).put("altKeys", JSONArray(it.altKeys))
                    .put("edited", it.edited)
                    .put("videoUri", it.videoUri).put("videoBytes", it.videoBytes).put("videoVid", it.videoVid)
            )
        }
        atomicWrite(tracksFile, arr.toString())
    }

    private fun loadPlaylists(): List<Playlist> = try {
        if (!playlistsFile.exists()) emptyList() else {
            val arr = JSONArray(playlistsFile.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val ids = o.getJSONArray("trackIds")
                Playlist(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    trackIds = List(ids.length()) { ids.getLong(it) },
                    createdAt = o.optLong("createdAt"),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun savePlaylists() {
        val arr = JSONArray()
        _playlists.value.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("createdAt", p.createdAt)
                    .put("trackIds", JSONArray(p.trackIds))
            )
        }
        atomicWrite(playlistsFile, arr.toString())
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() }

    private fun loadIgnored(): Set<String> = try {
        if (!ignoredFile.exists()) emptySet() else strings(JSONArray(ignoredFile.readText())).toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}

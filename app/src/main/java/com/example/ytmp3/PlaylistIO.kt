package com.example.ytmp3

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * プレイリストのファイル保存・読み込み(JSON)。
 * 曲ごとにダウンロード元 URL を入れておくので、無い曲は読み込み時にダウンロードできる。
 */
object PlaylistIO {
    private const val FORMAT = "musicplayer-playlist"

    data class ImportResult(
        val playlists: List<String>,
        val total: Int,
        val matched: Int,
        val downloading: Int,
        val unavailable: Int,
    )

    fun fileName(playlists: List<Playlist>): String {
        val base = if (playlists.size == 1) FileNames.safe(playlists[0].name, 80).ifEmpty { "playlist" }
        else "MusicPlayer_playlists"
        return "$base.json"
    }

    fun export(context: Context, playlists: List<Playlist>, uri: Uri) {
        val arr = JSONArray()
        for (p in playlists) {
            val tracks = JSONArray()
            Repository.tracksOf(p).forEach { t ->
                tracks.put(
                    JSONObject()
                        .put("title", t.title)
                        .put("artist", t.artist)
                        .put("album", t.album)
                        .put("url", t.sourceUrl)
                        .put("durationMs", t.durationMs)
                        .put("trackNo", t.trackNo)
                )
            }
            arr.put(JSONObject().put("name", p.name).put("tracks", tracks))
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("playlists", arr)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(root.toString(2).toByteArray()) }
    }

    data class Entry(val url: String, val title: String, val artist: String)
    data class Parsed(val name: String, val entries: List<Entry>)

    /** ファイルを読んで中のプレイリストを返す(まだ作らない) */
    fun parse(context: Context, uri: Uri): List<Parsed> {
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(text)
        val lists = root.optJSONArray("playlists")
            ?: throw IllegalArgumentException("プレイリストのファイルではありません")
        return List(lists.length()) { i ->
            val o = lists.getJSONObject(i)
            val tracks = o.optJSONArray("tracks") ?: JSONArray()
            Parsed(
                name = o.optString("name").ifBlank { "読み込んだプレイリスト" },
                entries = List(tracks.length()) { j ->
                    val e = tracks.getJSONObject(j)
                    Entry(e.optString("url"), e.optString("title"), e.optString("artist"))
                },
            )
        }
    }

    /**
     * 読み込んだ内容を反映する。足りない曲はダウンロード一覧へ。
     * @param target 既存のプレイリストに入れる場合はその id(null なら新しく作る)
     * @param replace target の中身を置き換える(false なら末尾に追加)
     */
    fun apply(context: Context, parsed: List<Parsed>, target: Long? = null, replace: Boolean = false): ImportResult {
        val library = Repository.tracks.value.filterNot { Repository.cantPlay(it.id) }
        val byVideo = HashMap<String, Track>()
        library.forEach { t -> youtubeVideoId(t.sourceUrl)?.let { byVideo.putIfAbsent(it, t) } }
        val byDup = library.filter { it.dupKey.isNotEmpty() }.associateBy { it.dupKey }

        val names = mutableListOf<String>()
        var total = 0; var matched = 0; var downloading = 0; var unavailable = 0

        // 既存のプレイリストに入れる時は全プレイリストの曲を 1 つにまとめる
        val groups: List<Pair<Long, List<Entry>>> = if (target != null) {
            val p = Repository.playlist(target) ?: throw IllegalStateException("プレイリストが見つかりません")
            names += p.name
            listOf(target to parsed.flatMap { it.entries })
        } else parsed.map { pl ->
            val name = uniqueName(pl.name)
            names += name
            Repository.createPlaylist(name).id to pl.entries
        }

        for ((pid, entries) in groups) {
            val base = if (target != null && !replace) Repository.playlist(pid)?.trackIds.orEmpty() else emptyList()
            val ids = base.toMutableList()
            val positions = HashMap<Long, Int>()
            base.forEachIndexed { i, id -> positions[id] = i }
            val offset = base.size
            val toDownload = mutableListOf<Triple<String, String, Int>>()
            entries.forEachIndexed { j, e ->
                total++
                // ライブラリにある曲: 動画 ID → 曲名 + アーティスト の順で探す
                val hit = youtubeVideoId(e.url)?.let { byVideo[it] }
                    ?: normalizeForDup(e.title).takeIf { it.isNotEmpty() }?.let { byDup[it + "|" + normalizeForDup(e.artist)] }
                when {
                    hit != null -> {
                        if (hit.id !in ids) { ids += hit.id; positions[hit.id] = offset + j }
                        matched++
                    }
                    e.url.isNotBlank() && youtubeVideoId(e.url) != null -> {
                        toDownload += Triple(e.url, e.title.ifBlank { e.url }, offset + j)
                        downloading++
                    }
                    else -> unavailable++
                }
            }
            Repository.setPlaylistOrder(pid, ids)
            DownloadManager.registerPositions(pid, positions)
            DownloadManager.addForPlaylist(context, pid, toDownload)
        }
        return ImportResult(names, total, matched, downloading, unavailable)
    }

    fun summary(res: ImportResult): String = buildString {
        append(res.playlists.joinToString("、") { "「$it」" })
        append(" に読み込みました。\n\n全 ${res.total} 曲\n")
        append("・ライブラリにあった曲: ${res.matched}\n")
        append("・ダウンロードを開始: ${res.downloading}\n")
        if (res.unavailable > 0) append("・ダウンロード元が無く追加できない曲: ${res.unavailable}\n")
        if (res.downloading > 0) append("\nダウンロードが終わった曲から順にプレイリストに入ります(並び順は元のまま)。")
    }

    private fun uniqueName(name: String): String {
        val existing = Repository.playlists.value.map { it.name }.toSet()
        if (name !in existing) return name
        var n = 2
        while ("$name ($n)" in existing) n++
        return "$name ($n)"
    }
}

/** ダウンロード元 URL をクリップボードへ */
fun copySourceUrl(context: Context, t: Track) {
    if (t.sourceUrl.isBlank()) {
        android.widget.Toast.makeText(context, "この曲にはダウンロード元がありません(フォルダから読み込んだ曲)", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText(t.title, t.sourceUrl))
    // Android 13 以降はシステムがコピーを表示するので重ねて出さない
    if (android.os.Build.VERSION.SDK_INT < 33) {
        android.widget.Toast.makeText(context, "ダウンロード元をコピーしました", android.widget.Toast.LENGTH_SHORT).show()
    }
}

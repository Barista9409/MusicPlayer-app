package com.example.ytmp3

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * アプリを使う人どうしの共有リンク(Share)も、この画面で確かめてから追加する(曲は ID で決まっているので探さない)。
 *
 * Spotify のリンク(曲・アルバム・プレイリスト)を読み込んで、YouTube Music / YouTube の同じ曲を探して追加する。
 * 追加する前に一覧で「✓ 一致 / ? 要確認 / × 見つからない」を見せ、タップで候補を選び直せる
 */
class SpotifyImportActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        /** name: 共有元が付けてくれた題名(YouTube のプレイリスト名など。無ければ null) */
        fun open(c: Context, url: String, name: String? = null) =
            c.startActivity(Intent(c, SpotifyImportActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_NAME, name))
    }

    /** 1 曲ぶんの状態 */
    private class Row(var song: Spotify.Song, val vid: String? = null, val preset: Searcher.Result? = null) {
        var done = false
        var found: Spotify.Found? = null
        var chosen: Searcher.Result? = null
        /** 自分で選び直した(要確認の印を外す) */
        var picked = false
        var skip = false
        /** すでにライブラリにある曲 */
        var existing: Track? = null
    }

    private var rows: List<Row> = emptyList()
    private var collection: Spotify.Collection? = null
    /** 共有リンクから開いた(曲は決まっているので、情報を読むだけ) */
    private var fromShare = false
    private val adapter = Adapter()
    private lateinit var status: TextView
    private lateinit var addButton: MaterialButton
    private lateinit var player: PlayerConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spotify_import)
        player = PlayerConnection(this) {}
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        status = findViewById(R.id.status)
        addButton = findViewById(R.id.addButton)
        addButton.setOnClickListener { addDialog() }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@SpotifyImportActivity)
            adapter = this@SpotifyImportActivity.adapter
        }
        load(intent.getStringExtra(EXTRA_URL).orEmpty())
    }

    override fun onStart() { super.onStart(); player.connect() }
    override fun onStop() { player.release(); super.onStop() }

    // ───────── 読み込みと照合 ─────────

    private fun load(url: String) {
        lifecycleScope.launch {
            // YouTube / YouTube Music の共有: 曲 1 つ、またはプレイリスト・アルバム(list=)。曲は決まっているので探さない
            if (Regex("https?://\\S*(youtube\\.com|youtu\\.be)").containsMatchIn(url)) {
                fromShare = true
                findViewById<MaterialToolbar>(R.id.toolbar).title = "YouTube から追加"
                val ytUrl = Regex("https?://\\S+").find(url)?.value ?: url
                val given = intent.getStringExtra(EXTRA_NAME)?.trim()?.takeIf { it.isNotBlank() }
                val rawList = Regex("[?&]list=([A-Za-z0-9_-]+)").find(ytUrl)?.groupValues?.get(1)
                // RD で始まるのは「ミックス」(自動で作られるラジオ風の再生リスト)。ただし RDCLAK… は YouTube Music の普通のプレイリスト
                val vid = youtubeVideoId(ytUrl)
                val mixList = rawList != null && rawList.startsWith("RD") && !rawList.startsWith("RDCLAK")
                // 曲の ID(v=)が付いていれば、ミックス再生中に共有した「その曲」なので 1 曲として扱う(これまでどおり)
                val isMix = mixList && vid == null
                val listId = rawList?.takeUnless { mixList }
                // ミックス: 元の曲(ミックスの ID の後ろ 11 文字)から、似た曲の一覧を作る
                if (isMix) {
                    val seed = Regex("^RD(?:AMVM|MM|EM)?([A-Za-z0-9_-]{11})$").find(rawList!!)?.groupValues?.get(1)
                    status.text = "ミックスを読み込んでいます…"
                    // まず YouTube のミックスそのものを読む。だめなら YouTube Music のラジオ(元の曲から似た曲を集める)
                    var fromYouTube = true
                    var songs = seed?.let { sv -> runCatching { withContext(Dispatchers.IO) { InnerTube.youtubeMix(rawList, sv) } }.getOrNull() }
                        .orEmpty().filter { it.videoId != null }
                    if (songs.size < 5) {
                        fromYouTube = false
                        songs = seed?.let { sv -> runCatching { withContext(Dispatchers.IO) { InnerTube.radio(sv) } }.getOrNull() }
                            .orEmpty().filter { it.videoId != null }
                    }
                    // No Good にした曲・アーティストは外す
                    songs = songs.filterNot { NoGood.isSong(it.videoId) || NoGood.isArtist(Radio.mainArtist(it.artist)) }
                    if (songs.isEmpty()) {
                        status.text = "このミックスは読み込めませんでした(元の曲が分からないか、通信に失敗しました)"
                        findViewById<View>(R.id.progress).visibility = View.GONE
                        return@launch
                    }
                    val name = given ?: "「${songs.first().title}」のミックス"
                    collection = Spotify.Collection(Spotify.Type.PLAYLIST, name, "", emptyList())
                    findViewById<MaterialToolbar>(R.id.toolbar).title = name
                    rows = songs.map { Row(Spotify.Song(it.title, it.artist, it.durationSec * 1000), it.videoId, it) }
                    adapter.notifyDataSetChanged()
                    matchAll()
                    // YouTube で見ていたミックスとは中身が違うことを知らせる(画面下に出て自然に消える)
                    android.widget.Toast.makeText(this@SpotifyImportActivity,
                        if (fromYouTube) "YouTube のミックスを読み込みました。ミックスは見る人ごとに中身が変わるため、YouTube で見ていた曲目と一部違うことがあります"
                        else "ミックスをそのまま読めなかったので、元の曲から似た曲を集め直しました。YouTube で見ていた曲目とは違うことがあります",
                        android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                // アーティスト・チャンネル(/channel/UC… や @名前): YouTube Music の人気曲
                if (listId == null && vid == null && Regex("youtube\\.com/(channel/|@|c/|user/)").containsMatchIn(ytUrl)) {
                    status.text = "アーティストの人気曲を読み込んでいます…"
                    val top = runCatching { withContext(Dispatchers.IO) { InnerTube.channelTopSongs(ytUrl) } }.getOrNull()
                    if (top == null || top.songs.isEmpty()) {
                        status.text = "このチャンネルの曲を読み込めませんでした(音楽のアーティストではないチャンネルかもしれません)"
                        findViewById<View>(R.id.progress).visibility = View.GONE
                        return@launch
                    }
                    collection = Spotify.Collection(Spotify.Type.ARTIST, top.artist, "", emptyList())
                    findViewById<MaterialToolbar>(R.id.toolbar).title = top.artist
                    rows = top.songs.filter { it.videoId != null }.map { Row(Spotify.Song(it.title, it.artist, it.durationSec * 1000), it.videoId, it) }
                    adapter.notifyDataSetChanged()
                    matchAll()
                    return@launch
                }
                if (listId != null) {
                    val name = given ?: "YouTube のプレイリスト"
                    collection = Spotify.Collection(Spotify.Type.PLAYLIST, name, "", emptyList())
                    findViewById<MaterialToolbar>(R.id.toolbar).title = name
                    status.text = "プレイリストを読み込んでいます…"
                    val list = Searcher.Result(Searcher.Kind.PLAYLIST, listId, "https://music.youtube.com/playlist?list=$listId", name, "", 0, null)
                    val tracks = runCatching { withContext(Dispatchers.IO) { Searcher.listTracks(list) } }.getOrDefault(emptyList())
                        .filter { it.videoId != null }
                    if (tracks.isEmpty()) {
                        status.text = "プレイリストを読み込めませんでした。非公開のプレイリストかもしれません。"
                        findViewById<View>(R.id.progress).visibility = View.GONE
                        return@launch
                    }
                    rows = tracks.map { Row(Spotify.Song(it.title, it.artist, it.durationSec * 1000), it.videoId, it) }
                } else if (vid != null) {
                    collection = Spotify.Collection(Spotify.Type.TRACK, given ?: "共有された曲", "", emptyList())
                    findViewById<MaterialToolbar>(R.id.toolbar).title = "YouTube から追加"
                    rows = listOf(Row(Spotify.Song("読み込み中…", "", 0), vid))
                } else {
                    status.text = "YouTube の曲・プレイリストのリンクではないようです"
                    findViewById<View>(R.id.progress).visibility = View.GONE
                    return@launch
                }
                adapter.notifyDataSetChanged()
                matchAll()
                return@launch
            }
            // アプリを使う人どうしの共有リンク
            Share.decode(url)?.let { p ->
                fromShare = true
                val type = when (p.kind) { Share.Kind.PLAYLIST -> Spotify.Type.PLAYLIST; Share.Kind.ALBUM -> Spotify.Type.ALBUM; Share.Kind.SONG -> Spotify.Type.TRACK }
                collection = Spotify.Collection(type, p.name, "", emptyList())
                findViewById<MaterialToolbar>(R.id.toolbar).title = "共有された${p.kind.label}「${p.name}」"
                rows = p.ids.map { Row(Spotify.Song("読み込み中…", "", 0), it) }
                adapter.notifyDataSetChanged()
                matchAll()
                return@launch
            }
            if (Share.isShare(url)) {
                status.text = "共有のリンクが壊れているようです。もう一度送ってもらってください。"
                findViewById<View>(R.id.progress).visibility = View.GONE
                return@launch
            }
            val got = runCatching {
                withContext(Dispatchers.IO) {
                    val link = Spotify.parse(url) ?: error("Spotify の曲・アルバム・プレイリストのリンクではありません")
                    Spotify.load(link)
                }
            }
            got.onFailure { e ->
                ErrorLog.add("Spotify", "読み込めない: $url · ${e.message}", e)
                status.text = "読み込めませんでした: ${e.message}\n\nリンクが正しいか、ネットにつながっているか確かめてください。"
                findViewById<View>(R.id.progress).visibility = View.GONE
                return@launch
            }
            val c = got.getOrThrow()
            collection = c
            findViewById<MaterialToolbar>(R.id.toolbar).title = c.name.ifBlank { "Spotify から追加" }
            rows = c.songs.map { Row(it) }
            adapter.notifyDataSetChanged()
            matchAll()
        }
    }

    private suspend fun matchAll() {
        val total = rows.size
        var finished = 0
        updateStatus(0)
        val gate = Semaphore(3)
        rows.mapIndexed { i, row ->
            lifecycleScope.async {
                gate.withPermit {
                    val vid = row.vid
                    // 共有リンク: 曲は ID で決まっている。情報(曲名・アーティスト・ジャケット)だけ読む
                    if (vid != null) {
                        val r = row.preset ?: withContext(Dispatchers.IO) { runCatching { InnerTube.songInfo(vid) }.getOrNull() }
                            ?: Searcher.Result(Searcher.Kind.SONG, vid, "https://music.youtube.com/watch?v=$vid", "曲 ($vid)", "", 0,
                                "https://i.ytimg.com/vi/$vid/hqdefault.jpg")
                        row.song = Spotify.Song(r.title, r.artist, r.durationSec * 1000)
                        row.found = Spotify.Found(r, listOf(r), Spotify.Match.OK)
                        row.chosen = r
                        row.existing = VideoAlias.trackFor(vid, includeMissing = true)
                        row.done = true
                        finished++
                        adapter.notifyItemChanged(i)
                        updateStatus(finished)
                        return@withPermit
                    }
                    // 先にライブラリを見る(同じ曲名・アーティストがあれば探さない)
                    val lib = withContext(Dispatchers.Default) { LibraryMatch.findSong(row.song.title, row.song.artist) }
                    if (lib != null) row.existing = lib
                    else {
                        val f = withContext(Dispatchers.IO) { runCatching { Spotify.findOnYouTube(row.song) }.getOrNull() }
                        row.found = f
                        row.chosen = f?.best
                        row.existing = row.chosen?.videoId?.let { VideoAlias.trackFor(it, includeMissing = true) }
                    }
                    row.done = true
                    finished++
                    adapter.notifyItemChanged(i)
                    updateStatus(finished)
                }
            }
        }.awaitAll()
        findViewById<View>(R.id.progress).visibility = View.GONE
        updateStatus(total)
    }

    private fun updateStatus(finished: Int) {
        val c = collection ?: return
        val total = rows.size
        val kind = when (c.type) {
            Spotify.Type.PLAYLIST -> "プレイリスト"; Spotify.Type.ALBUM -> "アルバム"; Spotify.Type.TRACK -> "曲"
            Spotify.Type.ARTIST -> "アーティスト(人気曲)"
        }
        if (finished < total) {
            status.text = if (fromShare) "$kind「${c.name}」 $total 曲 · 曲の情報を読み込んでいます… $finished / $total"
                else "$kind「${c.name}」 $total 曲 · YouTube で同じ曲を探しています… $finished / $total"
            addButton.isEnabled = false
            return
        }
        val ok = rows.count { it.existing == null && !it.skip && it.chosen != null && (it.picked || it.found?.match == Spotify.Match.OK) }
        val check = rows.count { it.existing == null && !it.skip && it.chosen != null && !it.picked && it.found?.match == Spotify.Match.CHECK }
        val none = rows.count { it.existing == null && !it.skip && it.chosen == null }
        val have = rows.count { it.existing != null && !it.skip }
        status.text = buildString {
            append("$kind「${c.name}」 $total 曲を読み込みました\n")
            append(listOfNotNull(
                "✓ $ok 曲",
                if (check > 0) "? 要確認 $check 曲" else null,
                if (none > 0) "× 見つからない $none 曲" else null,
                if (have > 0) "ライブラリにあり $have 曲" else null,
            ).joinToString(" · "))
            if (check > 0 || none > 0) append("\n? や × の曲はタップして候補を選び直せます")
        }
        val n = ok + check
        addButton.isEnabled = n > 0 || (have > 0 && c.type != Spotify.Type.TRACK)
        addButton.text = if (n > 0) "$n 曲を追加…" else "プレイリストを作る…"
    }

    // ───────── 候補を選び直す ─────────

    private fun choose(i: Int) {
        val row = rows[i]
        if (!row.done) return
        val cands = row.found?.candidates.orEmpty()
        val labels = cands.map { r ->
            val src = if (r.url.contains("music.youtube")) "YouTube Music" else "YouTube"
            "${r.title}\n${r.artist} · ${if (r.durationSec > 0) formatTime(r.durationSec * 1000) + " · " else ""}$src"
        }.toMutableList()
        val extra = mutableListOf<() -> Unit>()
        if (row.existing != null) { labels += "✓ ライブラリの曲を使う"; extra += { row.chosen = null; row.skip = false } }
        labels += "別の言葉で探す…"; extra += { searchAgain(i) }
        labels += if (row.skip) "やっぱり追加する" else "この曲は追加しない"; extra += { row.skip = !row.skip }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("${row.song.title} / ${row.song.artist}")
            .setItems(labels.toTypedArray()) { _, w ->
                if (w < cands.size) {
                    row.chosen = cands[w]; row.picked = true; row.skip = false
                    row.existing = cands[w].videoId?.let { VideoAlias.trackFor(it, includeMissing = true) }
                } else extra[w - cands.size]()
                adapter.notifyItemChanged(i); updateStatus(rows.size)
            }
            .setNegativeButton("閉じる", null)
        row.chosen?.let { r -> b.setNeutralButton("▶ 今の候補を試聴") { _, _ -> player.playSearch(listOf(r), 0) } }
        b.show()
    }

    private fun searchAgain(i: Int) {
        val row = rows[i]
        val input = EditText(this).apply {
            setText("${row.song.title} ${row.song.artist.split(", ").first()}")
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle("別の言葉で探す").setView(box)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("探す") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val found = withContext(Dispatchers.IO) {
                        val m = runCatching { InnerTube.search(q, Searcher.Source.MUSIC, Searcher.Section.SONGS).results }.getOrDefault(emptyList())
                        val y = runCatching { InnerTube.search(q, Searcher.Source.YOUTUBE, Searcher.Section.VIDEOS).results }.getOrDefault(emptyList())
                        (m.take(6) + y.take(6)).filter { it.videoId != null }.distinctBy { it.videoId }
                    }
                    if (found.isEmpty()) { toast("見つかりませんでした"); return@launch }
                    row.found = Spotify.Found(row.found?.best, found, row.found?.match ?: Spotify.Match.NONE)
                    choose(i)
                }
            }.show()
    }

    // ───────── 追加 ─────────

    private fun addDialog() {
        val c = collection ?: return
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        val group = RadioGroup(this)
        val modes = listOf(
            "☁ オンラインで追加(容量を使わない)",
            "⬇ 音声をダウンロード(オフラインでも聴ける)",
            "⬇ 音声と動画をダウンロード(容量を多く使う)",
        )
        modes.forEachIndexed { i, m -> group.addView(RadioButton(this).apply { id = 1000 + i; text = m }) }
        group.check(1000)
        box.addView(group)
        val makeList = CheckBox(this).apply {
            text = "プレイリスト「${c.name}」を作る"
            isChecked = c.type != Spotify.Type.TRACK
            visibility = if (c.type == Spotify.Type.TRACK) View.GONE else View.VISIBLE
        }
        box.addView(makeList)
        // ? 要確認の曲を除いて追加(自分で選び直した曲は含める)
        val unsure = rows.count { isUnsure(it) }
        val skipUnsure = CheckBox(this).apply {
            text = "? 要確認の $unsure 曲は追加しない"
            isChecked = true
            visibility = if (unsure > 0) View.VISIBLE else View.GONE
        }
        box.addView(skipUnsure)
        MaterialAlertDialogBuilder(this).setTitle("追加のしかた").setView(box)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("追加") { _, _ -> addAll(group.checkedRadioButtonId - 1000, makeList.isChecked, skipUnsure.isChecked) }
            .show()
    }

    /** まだ確かめていない「? 要確認」の曲 */
    private fun isUnsure(row: Row) = !row.skip && row.existing == null && row.chosen != null && !row.picked &&
        row.found?.match == Spotify.Match.CHECK

    private fun addAll(mode: Int, makeList: Boolean, skipUnsure: Boolean = false) {
        val c = collection ?: return
        val ids = mutableListOf<Long>()
        val added = mutableListOf<Track>()
        for (row in rows) {
            if (row.skip) continue
            if (skipUnsure && isUnsure(row)) continue
            val ex = row.existing
            val r = row.chosen
            // ライブラリにある曲はそのまま使う(プレイリストには入れる)
            if (ex != null && (r == null || r.videoId?.let { v -> VideoAlias.trackFor(v, includeMissing = true) } != null)) {
                ids += ex.id
                continue
            }
            if (r == null) continue
            // ライブラリには Spotify の曲名・アーティストで入れる(YouTube の「(Official Video)」などが付かないように)
            val t = OnlineLibrary.add(r.copy(title = row.song.title, artist = row.song.artist.ifBlank { r.artist })) ?: continue
            ids += t.id
            added += t
        }
        if (mode >= 1) {
            val online = added.filter { Repository.isOnline(it.id) }
            if (online.isNotEmpty()) DownloadManager.redownload(this, online)
            if (mode == 2) DownloadManager.addVideos(this, added)
        }
        if (makeList && ids.isNotEmpty()) Repository.createPlaylist(c.name.ifBlank { "Spotify" }, ids.distinct())
        toast(buildString {
            append("${added.size} 曲を追加しました")
            if (mode == 1) append("(ダウンロードします)")
            if (mode == 2) append("(動画もダウンロードします)")
            if (makeList && ids.isNotEmpty()) append(" · プレイリスト「${c.name}」を作りました")
        })
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ───────── 一覧 ─────────

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            val row = rows[position]
            v.findViewById<View>(R.id.check)?.visibility = View.GONE
            v.findViewById<View>(R.id.drag)?.visibility = View.GONE
            v.findViewById<TextView>(R.id.title).text = row.song.title
            val art = v.findViewById<ImageView>(R.id.art)
            val r = row.chosen
            val ex = row.existing
            when {
                ex != null && (r == null || r.videoId?.let { VideoAlias.trackFor(it, includeMissing = true) } != null) -> Thumbs.bind(art, ex.thumbPath)
                r != null -> Thumbs.bindUrl(art, r.thumb)
                else -> art.setImageDrawable(null)
            }
            val time = { x: Searcher.Result -> if (x.durationSec > 0) " · " + formatTime(x.durationSec * 1000) else "" }
            v.findViewById<TextView>(R.id.subtitle).text = when {
                !row.done -> "照合中… · ${row.song.artist}"
                row.skip -> "— 追加しない · ${row.song.artist}"
                ex != null && (r == null || r.videoId?.let { VideoAlias.trackFor(it, includeMissing = true) } != null) ->
                    "✓ ライブラリにあり · ${row.song.artist}"
                r == null -> "× 見つかりませんでした(タップで探す) · ${row.song.artist}"
                row.picked || row.found?.match == Spotify.Match.OK -> "✓ ${r.title} · ${r.artist}${time(r)}"
                else -> "? 要確認: ${r.title} · ${r.artist}${time(r)}"
            }
            v.alpha = if (row.skip) 0.5f else 1f
            v.setOnClickListener { choose(h.bindingAdapterPosition.takeIf { it >= 0 } ?: return@setOnClickListener) }
            v.findViewById<View>(R.id.more).setOnClickListener { choose(h.bindingAdapterPosition.takeIf { it >= 0 } ?: return@setOnClickListener) }
        }
    }
}

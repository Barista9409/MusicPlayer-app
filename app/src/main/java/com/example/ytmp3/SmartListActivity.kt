package com.example.ytmp3

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ホームの欄の「全部の一覧」(お気に入り・最近追加した曲・よく聴く曲・最近再生・久しぶりに聴く・おすすめの曲・ニューリリース)。
 * 見た目と操作はプレイリストの画面と同じ: 上に ▶ 再生・シャッフル・⋮、下に曲。
 */
class SmartListActivity : AppCompatActivity() {

    companion object {
        const val LIKED = "liked"
        const val ADDED = "added"
        const val TOP = "top"
        const val RECENT = "recent"
        const val REDISCOVER = "rediscover"
        const val DISCOVER = "discover"
        const val NEW = "new"
        /** ネットのアルバム・プレイリストの中身(検索などから「開く」) */
        const val ONLINE = "online"
        private const val EXTRA_KIND = "kind"

        fun open(c: Context, kind: String) =
            c.startActivity(Intent(c, SmartListActivity::class.java).putExtra(EXTRA_KIND, kind))

        /** アルバム・プレイリストを開いて、中の曲の一覧を見る */
        fun openList(c: Context, r: Searcher.Result) =
            c.startActivity(Intent(c, SmartListActivity::class.java).putExtra(EXTRA_KIND, ONLINE)
                .putExtra("l_kind", r.kind.name).putExtra("l_id", r.id).putExtra("l_url", r.url)
                .putExtra("l_title", r.title).putExtra("l_artist", r.artist).putExtra("l_thumb", r.thumb))
    }

    /** 一覧の行: 区切りの見出し / 曲(ライブラリの曲 か ネットの曲) */
    private sealed class Row {
        data class Head(val text: String) : Row()
        data class Song(val track: Track?, val result: Searcher.Result?, val note: String) : Row()
    }

    private lateinit var kind: String
    private lateinit var player: PlayerConnection
    private lateinit var mini: MiniPlayer
    private lateinit var actions: TrackActions
    private var rows: List<Row> = emptyList()
    private val adapter = Adapter()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    /** ONLINE: 開いたアルバム・プレイリストと、その中の曲(null = 読み込み中) */
    private var srcList: Searcher.Result? = null
    private var onlineSongs: List<Searcher.Result>? = null

    private val listTitle get() = when (kind) {
        LIKED -> "お気に入り"
        ADDED -> "最近追加した曲"
        TOP -> "よく聴く曲"
        RECENT -> "最近再生"
        REDISCOVER -> "久しぶりに聴く"
        DISCOVER -> "おすすめの曲"
        ONLINE -> srcList?.title.orEmpty()
        else -> "ニューリリース"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_detail)
        kind = intent.getStringExtra(EXTRA_KIND) ?: LIKED
        if (kind == ONLINE) srcList = runCatching {
            Searcher.Result(Searcher.Kind.valueOf(intent.getStringExtra("l_kind")!!), intent.getStringExtra("l_id")!!,
                intent.getStringExtra("l_url").orEmpty(), intent.getStringExtra("l_title").orEmpty(),
                intent.getStringExtra("l_artist").orEmpty(), 0, intent.getStringExtra("l_thumb"))
        }.getOrNull()
        if (kind == ONLINE && srcList == null) { finish(); return }
        actions = TrackActions(this)
        mini = MiniPlayer(this, findViewById(R.id.miniPlayer))
        player = PlayerConnection(this) { mini.attach(it) }

        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav).apply {
            selectedItemId = R.id.nav_library
            setOnItemSelectedListener { item ->
                val tab = when (item.itemId) {
                    R.id.nav_playlists -> MainActivity.TAB_PLAYLISTS
                    R.id.nav_downloads -> MainActivity.TAB_DOWNLOADS
                    R.id.nav_search -> MainActivity.TAB_SEARCH
                    else -> MainActivity.TAB_LIBRARY
                }
                startActivity(Intent(this@SmartListActivity, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, tab)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
                false
            }
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = listTitle
        toolbar.setNavigationOnClickListener { finish() }
        sortItem(toolbar)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Repository.tracks, Liked.list, DailyDiscover.list, NewReleases.list) { _, _, _, _ -> }.collect { rebuild() }
            }
        }
        // 🔒 の曲の別の音源が見つかった・無かった時に描き直す
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { AltSources.changed.collect { rebuild() } }
        }
        // ネットのアルバム・プレイリスト: 中の曲を取ってくる
        srcList?.let { src ->
            lifecycleScope.launch {
                val got = runCatching { withContext(Dispatchers.IO) { Searcher.listTracks(src) } }.getOrNull().orEmpty()
                onlineSongs = got
                // 再生ボタンを押したらすぐ鳴るように、最初の何曲かの再生 URL を先に取っておく
                if (got.isNotEmpty()) Searcher.prefetch(got, 3)
                // 聴けない曲(🔒)の別の音源を先に探しておく(押す前に「見つかった/無い」が分かるように)
                AltSources.lookAhead(got)
                if (got.isEmpty()) toast("曲の一覧を取得できませんでした")
                rebuild()
            }
        }
    }

    override fun onStart() { super.onStart(); player.connect() }
    override fun onStop() { mini.detach(); player.release(); super.onStop() }

    // ───────── 中身 ─────────

    private fun playable() = Repository.tracks.value.filterNot { Repository.cantPlay(it.id) }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when {
            m < 1 -> "たった今"
            m < 60 -> "${m}分前"
            m < 60 * 24 -> "${m / 60}時間前"
            m < 60 * 24 * 31 -> "${m / 60 / 24}日前"
            else -> "${m / 60 / 24 / 30}か月前"
        }
    }

    private fun rebuild() {
        val out = mutableListOf<Row>()
        when (kind) {
            LIKED -> {
                val liked = Liked.list.value
                val sorted = when (prefs.getString("list_sort_liked", "added")) {
                    "title" -> liked.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.JAPANESE)) { it.title })
                    "artist" -> liked.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.JAPANESE)) { it.artist })
                    else -> liked
                }
                sorted.forEach { r -> out += Row.Song(null, r, "") }
            }
            ADDED -> {
                val now = java.util.Calendar.getInstance()
                fun dayStart(daysAgo: Int) = (now.clone() as java.util.Calendar).apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
                    add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
                }.timeInMillis
                val today = dayStart(0); val yesterday = dayStart(1); val week = dayStart(7); val month = dayStart(30)
                var last = ""
                playable().sortedByDescending { it.savedAt }.forEach { t ->
                    val g = when {
                        t.savedAt >= today -> "今日"
                        t.savedAt >= yesterday -> "昨日"
                        t.savedAt >= week -> "今週"
                        t.savedAt >= month -> "今月"
                        else -> "それより前"
                    }
                    if (g != last) { out += Row.Head(g); last = g }
                    out += Row.Song(t, null, "")
                }
            }
            TOP -> {
                val days = prefs.getInt("list_top_days", 30)
                PlayLog.counts(days).entries.sortedByDescending { it.value }.forEach { e ->
                    Repository.track(e.key)?.takeIf { !Repository.cantPlay(it.id) }?.let { out += Row.Song(it, null, "${e.value}回") }
                }
            }
            RECENT -> {
                val lastTime = PlayLog.playsSince(0).groupBy { it.first }.mapValues { e -> e.value.maxOf { it.second } }
                PlayLog.recentTrackIds(200).forEach { id ->
                    Repository.track(id)?.takeIf { !Repository.cantPlay(it.id) }?.let { out += Row.Song(it, null, lastTime[id]?.let { t -> ago(t) }.orEmpty()) }
                }
            }
            REDISCOVER -> {
                val lastTime = PlayLog.playsSince(0).groupBy { it.first }.mapValues { e -> e.value.maxOf { it.second } }
                PlayLog.rediscover().forEach { id ->
                    Repository.track(id)?.takeIf { !Repository.cantPlay(it.id) }?.let {
                        out += Row.Song(it, null, lastTime[id]?.let { t -> "最後に聴いた: ${ago(t)}" } ?: "しばらく聴いていません")
                    }
                }
                // 少ない時は、まだ聴いたことのない曲も(古く追加した順)
                if (out.size < 20) {
                    val played = PlayLog.playedIds()
                    val fresh = playable().filter { it.id !in played }.sortedBy { it.savedAt }.take(50 - out.size)
                    if (fresh.isNotEmpty()) {
                        out += Row.Head("まだ聴いていない曲")
                        fresh.forEach { out += Row.Song(it, null, "") }
                    }
                }
            }
            DISCOVER -> DailyDiscover.list.value.filter { r ->
                !LibraryMatch.hasSong(r.title, r.artist) && !NoGood.isSong(r.videoId) &&
                    r.videoId?.let { VideoAlias.trackFor(it, includeMissing = true) } == null
            }.forEach { out += Row.Song(null, it, "") }
            NEW -> NewReleases.list.value.forEach { nr -> out += Row.Song(null, nr.result, if (nr.year > 0) "${nr.year}" else "") }
            ONLINE -> onlineSongs?.forEach { out += Row.Song(null, it, "") }
        }
        rows = out
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.empty).apply {
            if (kind == ONLINE) text = if (onlineSongs == null) "曲の一覧を読み込んでいます…" else "曲の一覧を取得できませんでした"
            visibility = if (songs().isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun songs() = rows.filterIsInstance<Row.Song>()
    private fun tracks() = songs().mapNotNull { it.track }
    private fun results() = songs().mapNotNull { it.result }

    // ───────── 再生 ─────────

    /** 一覧の i 曲目から(一覧の残りが再生キュー) */
    private fun playFrom(song: Row.Song?, shuffle: Boolean = false) {
        val ts = tracks()
        if (ts.isNotEmpty()) {
            val i = song?.track?.let { t -> ts.indexOfFirst { it.id == t.id } }?.coerceAtLeast(0) ?: 0
            player.play(ts, if (shuffle) (ts.indices).random() else i, shuffle = shuffle)
            return
        }
        val rs = results()
        if (rs.isEmpty()) return
        val r = song?.result ?: (if (shuffle) rs.random() else rs.first())
        // ニューリリース(アルバム・シングル): 中の曲を取ってから
        if (r.isList) {
            lifecycleScope.launch {
                val list = runCatching { withContext(Dispatchers.IO) { Searcher.listTracks(r) } }.getOrNull().orEmpty()
                if (list.isEmpty()) toast("曲の一覧を取得できませんでした")
                else player.playSearch(if (shuffle) list.shuffled() else list, 0, list = r)
            }
            return
        }
        val q = if (shuffle) rs.shuffled() else rs
        // 開いたアルバム・プレイリストなら、まとまりとして(まとめて追加・再生の記録)
        player.playSearch(q, q.indexOf(r).coerceAtLeast(0), list = srcList)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ───────── メニュー ─────────

    private fun sortItem(toolbar: MaterialToolbar) {
        when (kind) {
            LIKED -> toolbar.menu.add("並べ替え…").setOnMenuItemClickListener {
                val keys = arrayOf("added", "title", "artist")
                MaterialAlertDialogBuilder(this).setTitle("並べ替え")
                    .setSingleChoiceItems(arrayOf("♡ した新しい順", "曲名", "アーティスト"),
                        keys.indexOf(prefs.getString("list_sort_liked", "added")).coerceAtLeast(0)) { d, i ->
                        prefs.edit().putString("list_sort_liked", keys[i]).apply(); d.dismiss(); rebuild()
                    }.show()
                true
            }
            TOP -> toolbar.menu.add("期間…").setOnMenuItemClickListener {
                val days = intArrayOf(7, 30, 90)
                MaterialAlertDialogBuilder(this).setTitle("期間")
                    .setSingleChoiceItems(arrayOf("最近 7 日", "最近 30 日", "最近 90 日"),
                        days.indexOf(prefs.getInt("list_top_days", 30)).coerceAtLeast(0)) { d, i ->
                        prefs.edit().putInt("list_top_days", days[i]).apply(); d.dismiss(); rebuild()
                    }.show()
                true
            }
        }
    }

    private fun moreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            val ts = tracks()
            val rs = results()
            if (ts.isNotEmpty()) menu.add("キューに追加").setOnMenuItemClickListener { player.addToQueue(ts); true }
            srcList?.let { src -> menu.add("まとめて追加…").setOnMenuItemClickListener { StreamDownload.addListDialog(this@SmartListActivity, src) {}; true } }
            if (kind != NEW) menu.add("プレイリストとして保存").setOnMenuItemClickListener { saveAsPlaylist(); true }
            val online = ts.filter { Repository.isOnline(it.id) }
            val notInLib = rs.filter { !it.isList && it.videoId != null && VideoAlias.trackFor(it.videoId, includeMissing = true) == null }
            if (online.isNotEmpty() || notInLib.isNotEmpty()) menu.add("まとめてダウンロード").setOnMenuItemClickListener {
                if (online.isNotEmpty()) DownloadManager.redownload(this@SmartListActivity, online)
                notInLib.forEach { DownloadManager.add(this@SmartListActivity, it.url, wholeList = false, makePlaylist = false) }
                toast("${online.size + notInLib.size} 曲をダウンロードします"); true
            }
            val libForVideo = ts.ifEmpty { rs.mapNotNull { r -> r.videoId?.let { VideoAlias.trackFor(it, includeMissing = true) } } }
            if (libForVideo.isNotEmpty()) menu.add("🎬 動画もまとめて保存").setOnMenuItemClickListener { actions.saveVideos(libForVideo); true }
        }.show()
    }

    /** 共有(アプリを使う人どうし): 今の中身をそのままの順で */
    private fun shareList() {
        val ts = tracks()
        if (ts.isNotEmpty()) { actions.share(listTitle, Share.Kind.PLAYLIST, ts); return }
        actions.shareIds(listTitle, Share.Kind.PLAYLIST, results().filter { !it.isList }.mapNotNull { r ->
            r.videoId?.let { it to listOf(r.title, r.artist).filter { x -> x.isNotBlank() }.joinToString(" - ") } }.distinctBy { it.first })
    }

    /** 今の中身を、そのままの順で普通のプレイリストに(あとで中身は変わらない) */
    private fun saveAsPlaylist() {
        val ids = tracks().map { it.id }.ifEmpty {
            results().filter { !it.isList }.mapNotNull { OnlineLibrary.add(it)?.id }
        }
        if (ids.isEmpty()) { toast("曲がありません"); return }
        val name = listTitle + " " + java.text.SimpleDateFormat("M/d", java.util.Locale.JAPAN).format(java.util.Date())
        Repository.createPlaylist(name, ids.distinct())
        toast("プレイリスト「$name」を作りました(${ids.distinct().size} 曲)")
    }

    private fun rowMenu(anchor: View, song: Row.Song) {
        PopupMenu(this, anchor).apply {
            song.track?.let { t ->
                menu.add("次に再生").setOnMenuItemClickListener { player.playNext(t); true }
                menu.add("キューに追加").setOnMenuItemClickListener { player.addToQueue(listOf(t)); true }
                menu.add("プレイリストに追加…").setOnMenuItemClickListener { actions.addToPlaylist(listOf(t.id)); true }
                youtubeVideoId(t.sourceUrl)?.let { vid ->
                    menu.add(if (Liked.contains(vid)) "♡ お気に入りから外す" else "♡ お気に入りに追加").setOnMenuItemClickListener {
                        Liked.toggle(Searcher.Result(Searcher.Kind.SONG, vid, t.sourceUrl, t.title, t.artist, t.durationMs / 1000, null)); true
                    }
                    if (Repository.isOnline(t.id)) menu.add("⬇ ダウンロード").setOnMenuItemClickListener {
                        DownloadManager.redownload(this@SmartListActivity, listOf(t)); true
                    } else if (!Repository.isMissing(t.id)) menu.add("☁ ダウンロードを削除").setOnMenuItemClickListener {
                        actions.removeDownload(listOf(t)); true
                    }
                }
                actions.addVideoMenu(menu, t)
                menu.add("ライブラリから削除…").setOnMenuItemClickListener { actions.confirmDelete(listOf(t)); true }
            }
            song.result?.let { r ->
                if (r.isList) menu.add("開く(中の曲を見る)").setOnMenuItemClickListener { openList(this@SmartListActivity, r); true }
                menu.add(if (r.isList) "再生" else "ここから試聴").setOnMenuItemClickListener { playFrom(song); true }
                if (kind == DISCOVER) menu.add("No Good(この曲を今後おすすめしない)").setOnMenuItemClickListener {
                    NoGood.addSong(r); r.videoId?.let { DailyDiscover.drop(it) }
                    toast("おすすめに出さないようにしました(設定で解除できます)"); true
                }
                if (r.isList) menu.add("まとめて追加…").setOnMenuItemClickListener { StreamDownload.addListDialog(this@SmartListActivity, r) {}; true }
                else {
                    menu.add(if (Liked.contains(r.videoId)) "♡ お気に入りから外す" else "♡ お気に入りに追加").setOnMenuItemClickListener {
                        Liked.toggle(r); true
                    }
                    if (r.videoId?.let { VideoAlias.trackFor(it, includeMissing = true) } == null)
                        menu.add("＋ ライブラリに追加").setOnMenuItemClickListener {
                            OnlineLibrary.addSong(this@SmartListActivity, r) {
                                DownloadManager.add(this@SmartListActivity, r.url, wholeList = false, makePlaylist = false)
                            }; true
                        }
                    if (r.videoId != null && VideoCheck.known(r.videoId) != false)
                        menu.add("🎬 動画を保存").setOnMenuItemClickListener {
                            DownloadManager.addVideoForUrl(this@SmartListActivity, "https://www.youtube.com/watch?v=${r.videoId}", r.title)
                            toast("「${r.title}」の動画を保存します"); true
                        }
                }
            }
            NoGoodUi.addArtistItem(menu, this@SmartListActivity, song.track?.artist ?: song.result?.artist)
        }.show()
    }

    // ───────── 一覧 ─────────

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size + 1
        override fun getItemViewType(position: Int) = when {
            position == 0 -> 0
            rows[position - 1] is Row.Head -> 1
            else -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            val v = when (viewType) {
                0 -> inf.inflate(R.layout.playlist_header, parent, false)
                1 -> TextView(parent.context).apply {
                    val d = resources.displayMetrics.density
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(parent, androidx.appcompat.R.attr.colorPrimary))
                }
                else -> inf.inflate(R.layout.item_track, parent, false)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            if (position == 0) { bindHeader(v); return }
            when (val row = rows[position - 1]) {
                is Row.Head -> (v as TextView).text = row.text
                is Row.Song -> bindSong(v, row)
            }
        }
    }

    private fun bindHeader(v: View) {
        val ts = tracks()
        val rs = results()
        val art = v.findViewById<ImageView>(R.id.art)
        if (ts.isNotEmpty()) Covers.bind(art, ts, 480) else Thumbs.bindUrl(art, rs.firstOrNull()?.thumb)
        if (srcList != null) Thumbs.bindUrl(art, srcList?.thumb ?: rs.firstOrNull()?.thumb)
        v.findViewById<TextView>(R.id.name).text = listTitle
        val n = songs().size
        val totalMs = ts.sumOf { it.durationMs } + rs.sumOf { it.durationSec * 1000 }
        v.findViewById<TextView>(R.id.info).text =
            (srcList?.artist?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") +
            (if (kind == ONLINE && onlineSongs == null) "読み込み中…" else "$n 曲" + if (totalMs > 0) " · ${formatTotal(totalMs)}" else "")
        v.findViewById<View>(R.id.dl)?.visibility = View.GONE
        v.findViewById<View>(R.id.addSongs)?.visibility = View.GONE
        v.findViewById<View>(R.id.editHint)?.visibility = View.GONE
        v.findViewById<View>(R.id.play).setOnClickListener { playFrom(null) }
        v.findViewById<View>(R.id.shuffle).setOnClickListener { playFrom(null, shuffle = true) }
        v.findViewById<View>(R.id.more).setOnClickListener { moreMenu(it) }
        v.findViewById<View>(R.id.share)?.apply {
            visibility = if (kind == NEW) View.GONE else View.VISIBLE
            setOnClickListener { shareList() }
        }
    }

    private fun bindSong(v: View, row: Row.Song) {
        val art = v.findViewById<ImageView>(R.id.art)
        val titleV = v.findViewById<TextView>(R.id.title)
        val sub = v.findViewById<TextView>(R.id.subtitle)
        v.findViewById<View>(R.id.check)?.visibility = View.GONE
        v.findViewById<View>(R.id.drag)?.visibility = View.GONE
        val t = row.track
        val r = row.result
        if (t != null) {
            titleV.text = t.title
            Thumbs.bind(art, t.thumbPath)
            sub.text = buildList {
                if (row.note.isNotEmpty()) add(row.note)
                if (Repository.isOnline(t.id)) add("☁")
                if (t.hasVideo) add("🎬")
                DownloadManager.progressFor(t)?.let { add(it) }
                if (t.artist.isNotBlank()) add(t.artist)
                if (t.durationMs > 0) add(formatTime(t.durationMs))
            }.joinToString(" · ")
        } else if (r != null) {
            titleV.text = r.title
            Thumbs.bindUrl(art, r.thumb)
            val inLib = r.videoId?.let { VideoAlias.trackFor(it, includeMissing = true) }
            val lock = AltSources.label(r)
            sub.text = buildList {
                lock?.let { add(it.first) }
                if (row.note.isNotEmpty()) add(row.note)
                if (r.isList) add(if (r.kind == Searcher.Kind.ALBUM) "アルバム" else "プレイリスト")
                if (inLib != null) add(if (Repository.isOnline(inLib.id)) "☁ ライブラリ" else "✓ ライブラリ")
                if (r.artist.isNotBlank()) add(r.artist)
                if (r.durationSec > 0) add(formatTime(r.durationSec * 1000))
            }.joinToString(" · ")
        }
        v.alpha = r?.let { AltSources.label(it)?.second } ?: 1f
        // アルバム・シングル(ニューリリースなど)は開いて中の曲を見る。曲は再生
        v.setOnClickListener { if (r != null && r.isList) openList(this, r) else playFrom(row) }
        v.setOnLongClickListener { rowMenu(it, row); true }
        v.findViewById<ImageButton>(R.id.more).setOnClickListener { rowMenu(it, row) }
    }
}

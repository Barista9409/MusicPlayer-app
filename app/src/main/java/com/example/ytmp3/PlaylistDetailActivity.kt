package com.example.ytmp3

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** プレイリストの中身。ドラッグで並べ替え、スワイプで外す */
class PlaylistDetailActivity : AppCompatActivity() {
    private var playlistId = 0L
    private var warmed = false

    /** 先頭から 3 曲ぶん、ネットから流す曲(☁)の再生 URL を先に取る */
    private fun warmFirst(ts: List<Track>) {
        if (!Net.streamAllowed(this)) return
        ts.asSequence().filter { Repository.isMissing(it.id) && Repository.isOnline(it.id) }.take(3)
            .mapNotNull { youtubeVideoId(it.sourceUrl) }.forEach { runCatching { Searcher.warm(it) } }
    }

    private fun ref() = Repository.playlist(playlistId)?.let { PlayLog.playlistRef(it) }
    private lateinit var player: PlayerConnection
    private lateinit var actions: TrackActions
    private lateinit var editor: TrackEditor
    private lateinit var mini: MiniPlayer
    private lateinit var adapter: TrackAdapter
    private lateinit var list: RecyclerView
    private lateinit var header: View

    // ───────── 上の背景をジャケットの色に ─────────
    private var tintedFor: String? = null
    private fun tintHeader(ts: List<Track>) {
        val path = ts.firstOrNull { it.thumbPath != null }?.thumbPath ?: return
        if (tintedFor == path) return
        tintedFor = path
        lifecycleScope.launch {
            val accent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val b = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
                    val p = androidx.palette.graphics.Palette.from(b).generate()
                    (p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch)?.rgb
                }.getOrNull()
            } ?: return@launch
            val surface = com.google.android.material.color.MaterialColors.getColor(header, com.google.android.material.R.attr.colorSurface)
            val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val top = androidx.core.graphics.ColorUtils.blendARGB(surface, accent, if (night) 0.55f else 0.35f)
            header.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(top, androidx.core.graphics.ColorUtils.setAlphaComponent(surface, 0)))
        }
    }

    // ───────── このプレイリストに合う曲 ─────────
    private var recoLoaded = false
    private var reco: List<Searcher.Result> = emptyList()
    private val recoAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = if (reco.isEmpty()) 0 else reco.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = if (viewType == 0) TextView(parent.context).apply {
                text = "このプレイリストに合う曲"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                val d = resources.displayMetrics.density
                setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (6 * d).toInt())
            } else android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) return
            val r = reco[position - 1]
            val v = h.itemView
            Thumbs.bindUrl(v.findViewById(R.id.art), r.thumb)
            v.findViewById<TextView>(R.id.title).text = r.title
            v.findViewById<TextView>(R.id.subtitle).text = r.artist
            v.findViewById<View>(R.id.drag).visibility = View.GONE
            v.findViewById<View>(R.id.check).visibility = View.GONE
            v.findViewById<android.widget.ImageButton>(R.id.more).apply {
                setImageResource(R.drawable.ic_add)
                contentDescription = "このプレイリストに追加"
                setOnClickListener { addOnline(r) }
            }
            v.setOnClickListener { player.playSearch(reco, position - 1) }
        }
    }

    private fun loadReco(ts: List<Track>) {
        recoLoaded = true
        val seed = ts.mapNotNull { youtubeVideoId(it.sourceUrl) }.shuffled().firstOrNull() ?: return
        val have = VideoAlias.ownedIds(ts)
        lifecycleScope.launch {
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { InnerTube.radio(seed) }.getOrDefault(emptyList())
            }.filter { it.videoId != null && it.videoId !in have }.distinctBy { it.videoId }.take(10)
            reco = list
            recoAdapter.notifyDataSetChanged()
        }
    }

    /** 合う曲をこのプレイリストに追加(ダウンロードせず、オンラインの曲としてライブラリにも入れる) */
    private fun addOnline(r: Searcher.Result) {
        val vid = r.videoId ?: return
        val p = Repository.playlist(playlistId) ?: return
        val t = OnlineLibrary.add(r) ?: return
        if (t.id !in p.trackIds) Repository.setPlaylistOrder(playlistId, p.trackIds + t.id)
        reco = reco.filterNot { it.videoId == vid }
        recoAdapter.notifyDataSetChanged()
        Snackbar.make(list, "☁「${r.title}」を追加しました", Snackbar.LENGTH_SHORT).show()
    }
    private var dragging = false

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importInto(uri) }

    /** このプレイリストにファイルから読み込む(末尾に追加 / 置き換え) */
    private fun importInto(uri: android.net.Uri) {
        lifecycleScope.launch {
            val parsed = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PlaylistIO.parse(this@PlaylistDetailActivity, uri) }
            }.getOrElse {
                MaterialAlertDialogBuilder(this@PlaylistDetailActivity).setTitle("読み込めませんでした")
                    .setMessage(it.message ?: "").setPositiveButton("OK", null).show()
                return@launch
            }
            val count = parsed.sumOf { it.entries.size }
            if (count == 0) { Snackbar.make(list, "曲が入っていません", Snackbar.LENGTH_SHORT).show(); return@launch }
            val from = parsed.joinToString("、") { "「${it.name}」" }
            MaterialAlertDialogBuilder(this@PlaylistDetailActivity)
                .setTitle("このプレイリストに読み込む")
                .setMessage("$from の $count 曲をどう入れますか？\n(ライブラリに無い曲はダウンロードします)")
                .setNegativeButton("キャンセル", null)
                .setNeutralButton("置き換え") { _, _ -> applyImport(parsed, true) }
                .setPositiveButton("末尾に追加") { _, _ -> applyImport(parsed, false) }
                .show()
        }
    }

    private fun applyImport(parsed: List<PlaylistIO.Parsed>, replace: Boolean) {
        lifecycleScope.launch {
            val r = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    PlaylistIO.apply(this@PlaylistDetailActivity, parsed, playlistId, replace)
                }
            }
            MaterialAlertDialogBuilder(this@PlaylistDetailActivity)
                .setTitle(if (r.isSuccess) "読み込みました" else "読み込めませんでした")
                .setMessage(r.fold({ PlaylistIO.summary(it) }, { it.message ?: "" }))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val p = Repository.playlist(playlistId)
        if (uri != null && p != null) lifecycleScope.launch {
            val r = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PlaylistIO.export(this@PlaylistDetailActivity, listOf(p), uri) }
            }
            Snackbar.make(list, r.fold({ "「${p.name}」を保存しました" }, { "保存に失敗しました: ${it.message}" }), Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_detail)
        playlistId = intent.getLongExtra(EXTRA_ID, 0L)
        actions = TrackActions(this)
        editor = TrackEditor(this)
        actions.onRemoved = { ids ->
            player.controller?.let { c ->
                for (i in c.mediaItemCount - 1 downTo 0) {
                    if (c.getMediaItemAt(i).mediaId.toLongOrNull() in ids) c.removeMediaItem(i)
                }
            }
        }
        mini = MiniPlayer(this, findViewById(R.id.miniPlayer))
        mini.onTrackChanged = { if (::adapter.isInitialized) adapter.playingId = it }
        player = PlayerConnection(this) { mini.attach(it) }

        // 下のタブ: 押したらメイン画面のそのタブへ
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav).apply {
            selectedItemId = R.id.nav_playlists
            setOnItemSelectedListener { item ->
                val tab = when (item.itemId) {
                    R.id.nav_library -> MainActivity.TAB_LIBRARY
                    R.id.nav_downloads -> MainActivity.TAB_DOWNLOADS
                    R.id.nav_search -> MainActivity.TAB_SEARCH
                    else -> MainActivity.TAB_PLAYLISTS
                }
                startActivity(android.content.Intent(this@PlaylistDetailActivity, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, tab)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
                false
            }
            // 「プレイリスト」をもう一度押したら一覧に戻る
            setOnItemReselectedListener { finish() }
        }
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add("名前を変更").setOnMenuItemClickListener {
            val p = Repository.playlist(playlistId) ?: return@setOnMenuItemClickListener true
            actions.promptName("名前を変更", p.name) { Repository.renamePlaylist(p.id, it.ifBlank { p.name }) }
            true
        }
        toolbar.menu.add("曲を並べ替え…").apply {
            setIcon(R.drawable.ic_sort)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { sortSongs(); true }
        }
        toolbar.menu.add("キューに追加").setOnMenuItemClickListener { player.addToQueue(tracks()); true }
        toolbar.menu.add("🎬 動画もまとめて保存").setOnMenuItemClickListener { actions.saveVideos(tracks()); true }
        toolbar.menu.add("ホームにピン留め / 解除").setOnMenuItemClickListener {
            ref()?.let { r ->
                val on = PlayLog.togglePin(r)
                Snackbar.make(list, if (on) "ホームにピン留めしました" else "ピン留めを外しました", Snackbar.LENGTH_SHORT).show()
            }
            true
        }
        toolbar.menu.add("ファイルから読み込む…").setOnMenuItemClickListener {
            runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
            true
        }
        toolbar.menu.add("ファイルに保存…").setOnMenuItemClickListener {
            Repository.playlist(playlistId)?.let { runCatching { exportLauncher.launch(PlaylistIO.fileName(listOf(it))) } }
            true
        }
        toolbar.menu.add("見つからない曲を外す").setOnMenuItemClickListener {
            val p = Repository.playlist(playlistId) ?: return@setOnMenuItemClickListener true
            val before = p.trackIds
            val after = before.filterNot { Repository.cantPlay(it) }
            if (after.size == before.size) {
                Snackbar.make(list, "見つからない曲はありません", Snackbar.LENGTH_SHORT).show()
            } else {
                Repository.setPlaylistOrder(playlistId, after)
                Snackbar.make(list, "${before.size - after.size} 曲を外しました", Snackbar.LENGTH_LONG)
                    .setAction("元に戻す") { Repository.setPlaylistOrder(playlistId, before) }.show()
            }
            true
        }
        toolbar.menu.add("プレイリストを削除").setOnMenuItemClickListener {
            val p = Repository.playlist(playlistId) ?: return@setOnMenuItemClickListener true
            actions.confirmDeletePlaylist(p) { finish() }
            true
        }

        lateinit var touchHelper: ItemTouchHelper
        adapter = TrackAdapter(
            draggable = false,
            onClick = { pos -> player.play(adapter.items.toList(), pos, ref = ref()) },
            onMore = { v, pos -> itemMenu(v, pos) },
            onStartDrag = { touchHelper.startDrag(it) },
        )
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        // 上の大きなジャケットと、下の「合う曲」も一覧と一緒にスクロール
        header = layoutInflater.inflate(R.layout.playlist_header, list, false)
        val headerAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(header) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {}
        }
        list.adapter = androidx.recyclerview.widget.ConcatAdapter(headerAdapter, adapter, recoAdapter)

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun isLongPressDragEnabled() = false
            // 並び替え・スワイプで外すのは「並び替え」中の曲の行だけ
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (vh.bindingAdapter !== adapter || !adapter.draggable) 0 else super.getMovementFlags(rv, vh)
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                if (from.bindingAdapter !== adapter || to.bindingAdapter !== adapter) return false
                dragging = true
                adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                removeAt(vh.bindingAdapterPosition)
            }
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (dragging) {
                    dragging = false
                    Repository.setPlaylistOrder(playlistId, adapter.items.map { it.id })
                }
            }
        })
        touchHelper.attachToRecyclerView(list)

        header.findViewById<View>(R.id.play).setOnClickListener { player.play(tracks(), 0, ref = ref()) }
        header.findViewById<View>(R.id.shuffle).setOnClickListener { player.play(tracks(), 0, shuffle = true, ref = ref()) }
        header.findViewById<View>(R.id.addSongs).setOnClickListener { pickSongs() }
        header.findViewById<View>(R.id.more).setOnClickListener { toolbar.showOverflowMenu() }
        header.findViewById<View>(R.id.share).setOnClickListener {
            Repository.playlist(playlistId)?.let { p -> actions.share(p.name, Share.Kind.PLAYLIST, Repository.tracksOf(p)) }
        }
        header.findViewById<View>(R.id.dl).setOnClickListener {
            val ts = tracks()
            val online = ts.filter { Repository.isOnline(it.id) }
            val localYt = ts.filter { !Repository.isMissing(it.id) && youtubeVideoId(it.sourceUrl) != null }
            when {
                online.isNotEmpty() -> MaterialAlertDialogBuilder(this)
                    .setTitle("まとめてダウンロード")
                    .setMessage("☁ の ${online.size} 曲をダウンロードします")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("ダウンロード") { _, _ -> DownloadManager.redownload(this, online) }
                    .show()
                localYt.isNotEmpty() -> actions.removeDownload(localYt)
                else -> Snackbar.make(list, "ダウンロードできる曲がありません", Snackbar.LENGTH_SHORT).show()
            }
        }
        toolbar.menu.add("手動で並べ替え(≡ で移動・スワイプで外す)").setOnMenuItemClickListener {
            adapter.draggable = !adapter.draggable
            header.findViewById<View>(R.id.editHint).visibility = if (adapter.draggable) View.VISIBLE else View.GONE
            it.title = if (adapter.draggable) "並べ替えを終わる" else "手動で並べ替え(≡ で移動・スワイプで外す)"
            true
        }

        val name = header.findViewById<TextView>(R.id.name)
        val info = header.findViewById<TextView>(R.id.info)
        val art = header.findViewById<ImageView>(R.id.art)
        val empty = findViewById<View>(R.id.empty)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Net.changed.collect { adapter.notifyDataSetChanged() } }
                launch { Repository.missing.collect { adapter.missing = it } }
                // ダウンロード中の曲の進み具合(1 秒に 1 回くらい)
                launch { var last = 0L; DownloadManager.jobs.collect { val now = android.os.SystemClock.uptimeMillis(); if (now - last > 800) { last = now; adapter.notifyDataSetChanged() } } }
                combine(Repository.playlists, Repository.tracks, Repository.missing) { _, _, _ -> Repository.playlist(playlistId) }.collect { p ->
                    if (p == null) { finish(); return@collect }
                    val ts = Repository.tracksOf(p)
                    // ☁ の曲は、再生ボタンを押したらすぐ鳴るように最初の何曲かの URL を先に取っておく(開いた時に 1 回だけ)
                    if (!warmed) { warmed = true; warmFirst(ts) }
                    toolbar.title = p.name
                    name.text = p.name
                    val gone = ts.count { Repository.cantPlay(it.id) }
                    val online = ts.count { Repository.isOnline(it.id) }
                    // 端末に保存している分の容量
                    val bytes = ts.filter { !Repository.isMissing(it.id) }.sumOf { it.sizeBytes }
                    info.text = "${ts.size} 曲 · ${formatTotal(ts.sumOf { it.durationMs })}" +
                        (if (bytes > 0) " · ${StorageSheet.size(bytes)}" else "") +
                        (if (online > 0) " · ☁ $online 曲" else "") +
                        if (gone > 0) "\n見つからない曲 $gone(グレー表示・再生時スキップ)" else ""
                    header.findViewById<android.widget.ImageButton>(R.id.dl).apply {
                        setImageResource(if (online == 0 && ts.isNotEmpty()) R.drawable.ic_download_done else R.drawable.ic_download)
                        contentDescription = if (online > 0) "まとめてダウンロード" else "ダウンロードを削除"
                    }
                    Covers.bind(art, ts, 480)
                    tintHeader(ts)
                    if (!recoLoaded && ts.isNotEmpty()) loadReco(ts)
                    empty.visibility = if (ts.isEmpty()) View.VISIBLE else View.GONE
                    if (!dragging) adapter.submit(ts)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player.connect()
    }

    override fun onStop() {
        mini.detach()
        player.release()
        super.onStop()
    }

    private fun tracks() = Repository.playlist(playlistId)?.let { Repository.tracksOf(it) }.orEmpty()

    private fun removeAt(pos: Int) {
        val p = Repository.playlist(playlistId) ?: return
        val t = adapter.items.getOrNull(pos) ?: return
        val before = p.trackIds
        Repository.setPlaylistOrder(playlistId, before.filterNot { it == t.id })
        Snackbar.make(list, "「${t.title}」を外しました", Snackbar.LENGTH_LONG)
            .setAction("元に戻す") { Repository.setPlaylistOrder(playlistId, before) }
            .show()
    }

    private fun itemMenu(anchor: View, pos: Int) {
        val t = adapter.items.getOrNull(pos) ?: return
        PopupMenu(this, anchor).apply {
            menu.add("次に再生").setOnMenuItemClickListener { player.playNext(t); true }
            menu.add("情報を編集…").setOnMenuItemClickListener { editor.edit(listOf(t)); true }
            menu.add("一番上へ移動").setOnMenuItemClickListener { moveTo(t.id, 0); true }
            NoGoodUi.addArtistItem(menu, this@PlaylistDetailActivity, t.artist)
            menu.add("一番下へ移動").setOnMenuItemClickListener { moveTo(t.id, Int.MAX_VALUE); true }
            menu.add("ダウンロード元をコピー").setOnMenuItemClickListener { copySourceUrl(this@PlaylistDetailActivity, t); true }
            menu.add("ほかのプレイリストに追加…").setOnMenuItemClickListener { actions.addToPlaylist(listOf(t.id)); true }
            actions.addVideoMenu(menu, t)
            menu.add("このプレイリストから外す").setOnMenuItemClickListener { removeAt(pos); true }
            menu.add("ライブラリから削除…").setOnMenuItemClickListener { actions.confirmDelete(listOf(t)); true }
        }.show()
    }

    /** 曲の並び順を一括で変える(元に戻せる) */
    private fun sortSongs() {
        val p = Repository.playlist(playlistId) ?: return
        val ts = Repository.tracksOf(p)
        if (ts.size < 2) return
        val options = listOf<Pair<String, (List<Track>) -> List<Track>>>(
            "タイトル順" to { l -> l.sortedBy { naturalKey(it.title) } },
            "アーティスト順" to { l -> l.sortedWith(compareBy<Track>({ it.artistOrUnknown.lowercase() }).then(albumOrderComparator)) },
            "アルバム順(アルバム内は曲順)" to { l -> l.sortedWith(compareBy<Track>({ it.albumOrUnknown.lowercase() }).then(albumOrderComparator)) },
            "追加が新しい順" to { l -> l.sortedByDescending { it.savedAt } },
            "追加が古い順" to { l -> l.sortedBy { it.savedAt } },
            "長さが短い順" to { l -> l.sortedBy { it.durationMs } },
            "逆順にする" to { l -> l.reversed() },
            "シャッフル(1回だけ並べ替え)" to { l -> l.shuffled() },
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("曲を並べ替え")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val before = p.trackIds
                // 見つからない曲などライブラリに無い id は末尾に残す
                val sorted = options[which].second(ts).map { it.id }
                val after = sorted + before.filterNot { it in sorted }
                Repository.setPlaylistOrder(playlistId, after)
                Snackbar.make(list, "「${options[which].first.substringBefore("(")}」に並べ替えました", Snackbar.LENGTH_LONG)
                    .setAction("元に戻す") { Repository.setPlaylistOrder(playlistId, before) }
                    .show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun moveTo(id: Long, index: Int) {
        val ids = Repository.playlist(playlistId)?.trackIds?.toMutableList() ?: return
        ids.remove(id)
        ids.add(index.coerceIn(0, ids.size), id)
        Repository.setPlaylistOrder(playlistId, ids)
    }

    /** ライブラリから曲を選んで追加 */
    private fun pickSongs() {
        val p = Repository.playlist(playlistId) ?: return
        val candidates = Repository.tracks.value.filterNot { it.id in p.trackIds }
        if (candidates.isEmpty()) {
            Snackbar.make(list, "追加できる曲がありません(すべて追加済み)", Snackbar.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { "${it.title}\n${it.artist}".trimEnd() }.toTypedArray()
        val checked = BooleanArray(candidates.size)
        MaterialAlertDialogBuilder(this)
            .setTitle("曲を追加")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("追加") { _, _ ->
                val ids = candidates.filterIndexed { i, _ -> checked[i] }.map { it.id }
                val n = Repository.addToPlaylist(playlistId, ids)
                if (n > 0) Snackbar.make(list, "$n 曲を追加しました", Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    companion object {
        const val EXTRA_ID = "playlist_id"
    }
}

package com.example.ytmp3

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Space
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ライブラリ。YouTube Music 風に「フォルダ / 曲 / アルバム / アーティスト」をチップで切り替える。
 * フォルダ表示は端末のフォルダ構成をツリーにして展開・折りたたみできる。
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {
    private val main get() = requireActivity() as MainActivity

    private enum class Mode { FOLDERS, SONGS, ALBUMS, ARTISTS }

    /** アルバム / アーティストを開いているとき */
    private data class Detail(val kind: Mode, val key: String, val title: String)

    // ───────── rows ─────────
    private sealed class Row {
        object Header : Row()
        data class DetailHeader(val title: String, val subtitle: String, val thumb: String?, val tracks: List<Track>) : Row()
        data class Folder(val node: Node, val depth: Int, val expanded: Boolean) : Row()
        data class Song(val track: Track, val depth: Int, val queue: List<Track>, val index: Int) : Row()
        data class Album(val key: String, val title: String, val artist: String, val thumb: String?, val tracks: List<Track>) : Row()
        data class Artist(val name: String, val thumb: String?, val tracks: List<Track>) : Row()
    }

    /** フォルダツリーのノード */
    private class Node(var name: String, var path: String) {
        val children: java.util.TreeMap<String, Node> = java.util.TreeMap(String.CASE_INSENSITIVE_ORDER)
        val tracks = mutableListOf<Track>()
        val all: List<Track> by lazy { tracks + children.values.flatMap { it.all } }
    }

    private lateinit var adapter: Adapter
    private lateinit var selectionBar: MaterialToolbar
    private lateinit var empty: TextView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var list: RecyclerView

    private var mode = Mode.ALBUMS
    private var offlineOnly = false
    /** アルバムをジャケットのグリッドで表示するか(false = 一覧) */
    private var albumGrid = true
    private var detail: Detail? = null
    private var query = ""
    private var sort = 0
    private val sortNames = arrayOf("新しい順", "古い順", "タイトル順", "アーティスト順", "アルバム順")
    private val selected = linkedSetOf<Long>()
    private var expanded = mutableSetOf<String>()
    private var knownRoots = mutableSetOf<String>()
    private var rows: List<Row> = emptyList()
    private var visibleSongs: List<Track> = emptyList()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                selected.isNotEmpty() -> exitSelection()
                detail != null -> { detail = null; rebuild() }
            }
        }
    }

    private val prefs by lazy { requireContext().getSharedPreferences("library_ui", Context.MODE_PRIVATE) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        selectionBar = view.findViewById(R.id.selectionBar)
        empty = view.findViewById(R.id.empty)
        refresh = view.findViewById(R.id.refresh)
        list = view.findViewById(R.id.list)

        expanded = prefs.getStringSet("expanded", emptySet())!!.toMutableSet()
        knownRoots = prefs.getStringSet("roots", emptySet())!!.toMutableSet()
        mode = Mode.entries.getOrElse(prefs.getInt("mode", Mode.ALBUMS.ordinal)) { Mode.ALBUMS }
        albumGrid = prefs.getBoolean("album_grid", true)
        sort = prefs.getInt("sort", 0)

        adapter = Adapter()
        val glm = GridLayoutManager(requireContext(), 2)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (albumGrid && rows.getOrNull(position) is Row.Album) 1 else 2
        }
        list.layoutManager = glm
        list.adapter = adapter

        val chips = view.findViewById<ChipGroup>(R.id.chips)
        chips.check(
            when (mode) {
                Mode.FOLDERS -> R.id.chipFolders; Mode.SONGS -> R.id.chipSongs
                Mode.ALBUMS -> R.id.chipAlbums; Mode.ARTISTS -> R.id.chipArtists
            }
        )
        // オフラインで聴ける曲だけに絞る(種類の選択とは別に、押すたびに切り替え)
        offlineOnly = prefs.getBoolean("offline_only", false)
        chips.addView(com.google.android.material.chip.Chip(requireContext()).apply {
            isCheckable = false
            fun label() {
                text = if (offlineOnly) "✓ オフラインで聴ける曲" else "オフラインで聴ける曲"
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(chips,
                    if (offlineOnly) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorSurface))
            }
            label()
            setOnClickListener {
                offlineOnly = !offlineOnly
                prefs.edit().putBoolean("offline_only", offlineOnly).apply()
                label(); rebuild()
            }
        })
        chips.setOnCheckedStateChangeListener { _, ids ->
            mode = when (ids.firstOrNull()) {
                R.id.chipSongs -> Mode.SONGS
                R.id.chipAlbums -> Mode.ALBUMS
                R.id.chipArtists -> Mode.ARTISTS
                else -> Mode.FOLDERS
            }
            prefs.edit().putInt("mode", mode.ordinal).apply()
            detail = null
            rebuild()
            list.scrollToPosition(0)
        }

        val searchClear = view.findViewById<View>(R.id.searchClear)
        val search = view.findViewById<TextView>(R.id.search)
        search.doAfterTextChanged {
            query = it?.toString().orEmpty()
            searchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            rebuild()
        }
        searchClear.setOnClickListener { search.text = "" }

        refresh.setOnRefreshListener { main.scanLibrary(userInitiated = true) }

        setupSelectionBar()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 電波・オフラインモードが変わったら、再生できる曲の表示を更新
                // 隠れている間は描き直さず、印だけ付けて表示された時に 1 回(ホームを開いている時に重くならないように)
                launch { Net.changed.collect { if (isAdded) rebuildIfShown() } }
                launch {
                    combine(Repository.tracks, Repository.missing, LibraryScanner.folders) { _, _, _ -> }
                        .collect { rebuildIfShown() }
                }
                launch { main.nowPlayingId.collect { if (isHidden) stale = true else adapter.notifyDataSetChanged() } }
                // ダウンロード中の曲の進み具合(1 秒に 1 回くらい)
                launch { var last = 0L; DownloadManager.jobs.collect { val now = android.os.SystemClock.uptimeMillis(); if (isHidden) stale = true else if (now - last > 800) { last = now; adapter.notifyDataSetChanged() } } }
                launch { LibraryScanner.scanning.collect { refresh.isRefreshing = it } }
                launch { main.libraryPermission.collect { adapter.notifyItemChanged(0) } }
            }
        }
    }

    // ───────── build rows ─────────

    private fun matches(t: Track, q: String) =
        t.title.lowercase().contains(q) || t.artist.lowercase().contains(q) ||
            t.album.lowercase().contains(q) || t.folder.lowercase().contains(q) || t.fileName.lowercase().contains(q)

    private fun sorted(list: List<Track>) = when (sort) {
        1 -> list.sortedBy { it.savedAt }
        2 -> list.sortedBy { it.title.lowercase() }
        3 -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.title.lowercase() }))
        4 -> list.sortedWith(compareBy({ it.album.lowercase() }, { it.fileName.lowercase() }))
        else -> list.sortedByDescending { it.savedAt }
    }

    /** アルバム内の曲順(トラック番号 → ファイル名の自然順 → 保存順) */
    private fun albumOrder(list: List<Track>) = list.sortedWith(albumOrderComparator)

    /** アーティスト内: アルバムごとにまとめ、各アルバムは曲順 */
    private fun artistOrder(list: List<Track>) =
        list.sortedWith(compareBy<Track>({ it.albumOrUnknown.lowercase() }).then(albumOrderComparator))

    @SuppressLint("NotifyDataSetChanged")
    private fun rebuild() {
        if (!isAdded) return
        // 「オフラインで聴ける曲だけ」: ダウンロード済みと一時保存済みの ☁
        val all = Repository.tracks.value.let { ts ->
            if (!offlineOnly) ts else ts.filter { t ->
                !Repository.isMissing(t.id) || (Repository.isOnline(t.id) && StreamCache.isCached(requireContext(), youtubeVideoId(t.sourceUrl)))
            }
        }
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) all else all.filter { matches(it, q) }
        val out = mutableListOf<Row>()
        val d = detail

        if (d != null) {
            val tracks = when (d.kind) {
                Mode.ALBUMS -> albumOrder(all.filter { albumKey(it) == d.key })
                else -> artistOrder(all.filter { it.artistOrUnknown == d.key })
            }.let { if (q.isEmpty()) it else it.filter { t -> matches(t, q) } }
            val sub = when (d.kind) {
                Mode.ALBUMS -> listOf(tracks.firstOrNull()?.artist.orEmpty(), "${tracks.size} 曲", formatTotal(tracks.sumOf { it.durationMs }))
                    .filter { it.isNotBlank() }.joinToString(" · ")
                else -> "${tracks.map { albumKey(it) }.distinct().size} アルバム · ${tracks.size} 曲"
            }
            out += Row.DetailHeader(d.title, sub, tracks.firstOrNull { it.thumbPath != null }?.thumbPath, tracks)
            tracks.forEachIndexed { i, t -> out += Row.Song(t, 0, tracks, i) }
            visibleSongs = tracks
        } else {
            out += Row.Header
            when (mode) {
                Mode.SONGS -> {
                    val s = sorted(filtered)
                    s.forEachIndexed { i, t -> out += Row.Song(t, 0, s, i) }
                    visibleSongs = s
                }
                Mode.FOLDERS -> {
                    val roots = buildTree(filtered)
                    // 初めて見るトップフォルダは開いた状態にする
                    val newRoots = roots.map { it.path }.filter { it !in knownRoots }
                    if (newRoots.isNotEmpty() && q.isEmpty()) {
                        expanded.addAll(newRoots); knownRoots.addAll(newRoots); saveExpanded()
                    }
                    val songs = mutableListOf<Track>()
                    roots.forEach { flatten(it, 0, q.isNotEmpty(), out, songs) }
                    visibleSongs = songs
                }
                Mode.ALBUMS -> {
                    filtered.groupBy { albumKey(it) }.values
                        .map { albumOrder(it) }
                        .sortedBy { it.first().albumOrUnknown.lowercase() }
                        .forEach { ts ->
                            val f = ts.first()
                            out += Row.Album(albumKey(f), albumTitle(f),
                                ts.map { it.artist }.distinct().let { if (it.size > 1) "さまざまなアーティスト" else it.first() },
                                ts.firstOrNull { it.thumbPath != null }?.thumbPath, ts)
                        }
                    visibleSongs = filtered
                }
                Mode.ARTISTS -> {
                    filtered.groupBy { it.artistOrUnknown }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                        .forEach { (name, ts) ->
                            out += Row.Artist(name, ts.firstOrNull { it.thumbPath != null }?.thumbPath, artistOrder(ts))
                        }
                    visibleSongs = filtered
                }
            }
        }
        rows = out
        selected.retainAll(all.map { it.id }.toSet())
        adapter.notifyDataSetChanged()
        updateSelection()

        empty.visibility = if (d == null && out.size <= 1) View.VISIBLE else View.GONE
        empty.text = when {
            all.isEmpty() -> "まだ曲がありません\n\n「検索」で曲を探すか URL を貼って追加するか、" +
                "下に引っ張って Music フォルダを読み込んでください"
            else -> "「$query」に一致する曲はありません"
        }
        backCallback.isEnabled = selected.isNotEmpty() || detail != null
    }

    /** アルバム名(無い曲はフォルダ名。それも無ければ空) */
    private fun albumTitle(t: Track) = t.album.ifBlank { t.folder.trimEnd('/').substringAfterLast('/') }

    private fun albumKey(t: Track) = (t.album.ifBlank { "\u0000" + t.folder }).lowercase()

    private fun buildTree(tracks: List<Track>): List<Node> {
        val root = Node("", "")
        for (t in tracks) {
            val segs = t.folder.trim('/').split('/').filter { it.isNotEmpty() }
            var n = root
            var path = ""
            if (segs.isEmpty()) {
                path = "?/"
                n = n.children.getOrPut("?") { Node("その他", path) }
            } else for (s in segs) {
                path += "$s/"
                n = n.children.getOrPut(s) { Node(s, path) }
            }
            n.tracks += t
        }
        root.children.values.forEach { compress(it) }
        return root.children.values.toList()
    }

    /** 曲がなくサブフォルダが 1 つだけのフォルダはまとめて表示("Music / Artist") */
    private fun compress(n: Node) {
        while (n.tracks.isEmpty() && n.children.size == 1) {
            val c = n.children.values.first()
            n.name = "${n.name} / ${c.name}"
            n.path = c.path
            n.children.clear()
            n.children.putAll(c.children)
            n.tracks.addAll(c.tracks)
        }
        n.children.values.forEach { compress(it) }
    }

    private fun flatten(n: Node, depth: Int, forceOpen: Boolean, out: MutableList<Row>, songs: MutableList<Track>) {
        val open = forceOpen || n.path in expanded
        out += Row.Folder(n, depth, open)
        if (!open) return
        n.children.values.forEach { flatten(it, depth + 1, forceOpen, out, songs) }
        val ts = albumOrder(n.tracks)
        ts.forEachIndexed { i, t -> out += Row.Song(t, depth + 1, ts, i) }
        songs += ts
    }

    private fun toggleFolder(path: String) {
        if (!expanded.remove(path)) expanded.add(path)
        saveExpanded()
        rebuild()
    }

    private fun setAllExpanded(open: Boolean) {
        if (open) {
            val paths = mutableSetOf<String>()
            fun walk(n: Node) { paths += n.path; n.children.values.forEach(::walk) }
            buildTree(Repository.tracks.value).forEach(::walk)
            expanded = paths
        } else expanded.clear()
        saveExpanded()
        rebuild()
    }

    private fun saveExpanded() {
        prefs.edit().putStringSet("expanded", expanded.toSet()).putStringSet("roots", knownRoots.toSet()).apply()
    }

    // ───────── selection ─────────

    private fun setupSelectionBar() {
        selectionBar.setNavigationOnClickListener { exitSelection() }
        selectionBar.menu.apply {
            add("すべて選択").setIcon(R.drawable.ic_select_all).setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener { selected.addAll(visibleSongs.map { it.id }); updateSelection(); true }
            add("プレイリストに追加").setIcon(R.drawable.ic_playlist_add).setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener { main.actions.addToPlaylist(selectedTracks().map { it.id }) { exitSelection() }; true }
            add("ライブラリから削除").setIcon(R.drawable.ic_delete).setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener { main.actions.confirmDelete(selectedTracks()) { exitSelection() }; true }
            add("キューに追加").setOnMenuItemClickListener { main.player.addToQueue(selectedTracks()); exitSelection(); true }
            add("情報を編集…").setOnMenuItemClickListener { main.editor.edit(selectedTracks()); exitSelection(); true }
            add("タイトルを整える…").setOnMenuItemClickListener { main.editor.cleanTitles(selectedTracks()); exitSelection(); true }
        }
    }

    private fun selectedTracks(): List<Track> {
        val map = Repository.tracks.value.associateBy { it.id }
        return selected.mapNotNull { map[it] }
    }

    private fun toggleSelect(id: Long) {
        if (!selected.remove(id)) selected.add(id)
        updateSelection()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelection() {
        val on = selected.isNotEmpty()
        selectionBar.visibility = if (on) View.VISIBLE else View.GONE
        selectionBar.title = "${selected.size} 曲を選択中"
        backCallback.isEnabled = on || detail != null
        main.setFabVisible(!on)
    }

    private fun exitSelection() {
        selected.clear()
        updateSelection()
        adapter.notifyDataSetChanged()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && selected.isNotEmpty()) exitSelection()
        if (!hidden && stale) { stale = false; rebuild() }
    }

    /** 隠れている間に変わったか(表示された時に描き直す) */
    private var stale = false
    private fun rebuildIfShown() { if (isHidden) stale = true else rebuild() }

    // ───────── menus ─────────

    private fun trackMenu(anchor: View, t: Track) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add("次に再生").setOnMenuItemClickListener { main.player.playNext(t); true }
            menu.add("この曲のラジオ").setOnMenuItemClickListener { Radio.forTrack(main, main.player, t); true }
            menu.add("キューに追加").setOnMenuItemClickListener { main.player.addToQueue(listOf(t)); true }
            NoGoodUi.addArtistItem(menu, requireContext(), t.artist)
            menu.add("プレイリストに追加…").setOnMenuItemClickListener { main.actions.addToPlaylist(listOf(t.id)); true }
            if (youtubeVideoId(t.sourceUrl) != null) menu.add("共有…").setOnMenuItemClickListener {
                main.actions.share(t.title, Share.Kind.SONG, listOf(t)); true
            }
            if (t.album.isNotBlank()) menu.add("アルバムを表示").setOnMenuItemClickListener {
                openDetail(Detail(Mode.ALBUMS, albumKey(t), albumTitle(t))); true
            }
            menu.add("アーティストを表示").setOnMenuItemClickListener {
                openDetail(Detail(Mode.ARTISTS, t.artistOrUnknown, t.artistOrUnknown)); true
            }
            menu.add("情報を編集…").setOnMenuItemClickListener { main.editor.edit(listOf(t)); true }
            menu.add("選択").setOnMenuItemClickListener { toggleSelect(t.id); true }
            menu.add("情報").setOnMenuItemClickListener { showInfo(t); true }
            if (t.sourceUrl.isNotBlank()) menu.add("ダウンロード元をコピー").setOnMenuItemClickListener { copySourceUrl(requireContext(), t); true }
            youtubeVideoId(t.sourceUrl)?.let { vid ->
                menu.add(if (Liked.contains(vid)) "♡ お気に入りから外す" else "♡ お気に入りに追加").setOnMenuItemClickListener {
                    Liked.toggle(Searcher.Result(Searcher.Kind.SONG, vid, t.sourceUrl, t.title, t.artist, t.durationMs / 1000, null)); true
                }
            }
            // オフライン / オンラインの切り替え(YouTube 由来の曲だけ)
            if (youtubeVideoId(t.sourceUrl) != null) {
                if (Repository.isOnline(t.id)) menu.add("⬇ ダウンロード").setOnMenuItemClickListener {
                    DownloadManager.redownload(requireContext(), listOf(t)); true
                } else if (!Repository.isMissing(t.id)) menu.add("☁ ダウンロードを削除").setOnMenuItemClickListener {
                    main.actions.removeDownload(listOf(t)); true
                }
            }
            main.actions.addVideoMenu(menu, t)
            menu.add("ライブラリから削除…").setOnMenuItemClickListener {
                main.actions.confirmDelete(listOf(t)); true
            }
        }.show()
    }

    private fun albumRefOf(tracks: List<Track>) = tracks.firstOrNull()?.takeIf { it.album.isNotBlank() }?.let { PlayLog.albumRef(it) }

    private fun groupMenu(anchor: View, name: String, tracks: List<Track>, ref: PlayLog.Ref? = null) {
        PopupMenu(requireContext(), anchor).apply {
            // まとめてオフライン / オンライン
            val onlineOnes = tracks.filter { Repository.isOnline(it.id) }
            val localYt = tracks.filter { !Repository.isMissing(it.id) && youtubeVideoId(it.sourceUrl) != null }
            if (onlineOnes.isNotEmpty()) menu.add("⬇ ダウンロード(${onlineOnes.size} 曲)").setOnMenuItemClickListener {
                DownloadManager.redownload(requireContext(), onlineOnes); true
            }
            if (localYt.isNotEmpty()) menu.add("☁ ダウンロードを削除(${localYt.size} 曲)").setOnMenuItemClickListener {
                main.actions.removeDownload(localYt); true
            }
            main.actions.addVideoGroupMenu(menu, tracks)
            menu.add("共有…").setOnMenuItemClickListener {
                main.actions.share(name, if (ref?.kind == PlayLog.Kind.ALBUM) Share.Kind.ALBUM else Share.Kind.PLAYLIST, tracks); true
            }
            if (ref?.kind == PlayLog.Kind.ARTIST) {
                menu.add("このアーティストのラジオ").setOnMenuItemClickListener { Radio.forArtist(main, main.player, ref.key); true }
            }
            if (ref != null) {
                menu.add(if (PlayLog.isPinned(ref)) "ホームのピン留めを外す" else "ホームにピン留め").setOnMenuItemClickListener {
                    val on = PlayLog.togglePin(ref)
                    android.widget.Toast.makeText(requireContext(), if (on) "ホームにピン留めしました" else "ピン留めを外しました", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            }
            menu.add("シャッフル再生").setOnMenuItemClickListener { main.player.play(tracks, 0, shuffle = true); true }
            menu.add("キューに追加").setOnMenuItemClickListener { main.player.addToQueue(tracks); true }
            menu.add("プレイリストに追加…").setOnMenuItemClickListener { main.actions.addToPlaylist(tracks.map { it.id }); true }
            menu.add("「$name」としてプレイリストを作成").setOnMenuItemClickListener {
                val p = Repository.createPlaylist(name, tracks.map { it.id })
                android.widget.Toast.makeText(requireContext(), "「${p.name}」を作成しました", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            menu.add("情報をまとめて編集…").setOnMenuItemClickListener { main.editor.edit(tracks); true }
            menu.add("タイトルをまとめて整える…").setOnMenuItemClickListener { main.editor.cleanTitles(tracks); true }
            menu.add("すべて選択").setOnMenuItemClickListener {
                selected.addAll(tracks.map { it.id }); updateSelection(); adapter.notifyDataSetChanged(); true
            }
            menu.add("ライブラリから削除…").setOnMenuItemClickListener {
                main.actions.confirmDelete(tracks, groupName = name) {
                    // 開いているアルバム/アーティストが空になったら一覧に戻る
                    if (detail != null) { detail = null; rebuild() }
                }
                true
            }
        }.show()
    }

    private fun showInfo(t: Track) {
        val msg = buildString {
            appendLine("タイトル: ${t.title}")
            if (t.artist.isNotBlank()) appendLine("アーティスト: ${t.artist}")
            if (t.album.isNotBlank()) appendLine("アルバム: ${t.album}")
            if (t.durationMs > 0) appendLine("長さ: ${formatTime(t.durationMs)}")
            appendLine("形式: ${t.fileName.substringAfterLast('.', "").uppercase().ifEmpty { "不明" }}")
            if (t.sizeBytes > 0) appendLine(String.format("サイズ: %.1f MB", t.sizeBytes / 1_048_576.0))
            appendLine("場所: ${SaveLocation.folderLabel(t.folder)}/${t.fileName}")
            appendLine("追加元: ${if (t.source == Track.SOURCE_SCAN) "フォルダ読み込み" else "ダウンロード"}")
            if (t.sourceUrl.isNotBlank()) appendLine("URL: ${t.sourceUrl}")
            if (t.trackNo > 0) appendLine("トラック: ${if (t.discNo > 0) "${t.discNo}-" else ""}${t.trackNo}")
            if (t.altUris.isNotEmpty()) appendLine("重複ファイル: ${t.altUris.size} 件をまとめています" +
                t.altKeys.joinToString("") { "\n  ・$it" })
            when {
                Repository.isOnline(t.id) -> appendLine("\n☁ オンライン(ネットから再生します)")
                Repository.isMissing(t.id) -> appendLine("\n⚠ ファイルが見つかりません")
                else -> appendLine("\n⬇ ダウンロード済み(オフラインで聴けます)")
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("曲の情報").setMessage(msg).setPositiveButton("OK", null)
            .apply {
                if (t.sourceUrl.isNotBlank()) setNeutralButton("ダウンロード元をコピー") { _, _ -> copySourceUrl(requireContext(), t) }
            }
            .show()
    }

    // ───────── ホームから開く ─────────

    /** "albums" / "artists" / "songs" / "folders" の一覧を開く */
    fun showMode(name: String) {
        val m = when (name) { "artists" -> Mode.ARTISTS; "songs" -> Mode.SONGS; "folders" -> Mode.FOLDERS; else -> Mode.ALBUMS }
        detail = null
        val chipId = when (m) {
            Mode.FOLDERS -> R.id.chipFolders; Mode.SONGS -> R.id.chipSongs
            Mode.ALBUMS -> R.id.chipAlbums; Mode.ARTISTS -> R.id.chipArtists
        }
        val chips = view?.findViewById<ChipGroup>(R.id.chips)
        if (mode != m && chips != null) chips.check(chipId) // 変更時のリスナーで再構築される
        else { mode = m; rebuild() }
        if (::list.isInitialized) list.scrollToPosition(0)
    }

    fun showAlbum(key: String, title: String) { showMode("albums"); openDetail(Detail(Mode.ALBUMS, key, title)) }
    fun showArtist(name: String) { showMode("artists"); openDetail(Detail(Mode.ARTISTS, name, name)) }

    /** 戻るボタン: 選択中・詳細を閉じたら true */
    fun handleBack(): Boolean = when {
        !isAdded -> false
        selected.isNotEmpty() -> { exitSelection(); true }
        detail != null -> { detail = null; rebuild(); true }
        else -> false
    }

    private fun openDetail(d: Detail) {
        detail = d
        rebuild()
        list.scrollToPosition(0)
    }

    // ───────── adapter ─────────

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            Row.Header -> 0
            is Row.DetailHeader -> 1
            is Row.Folder -> 2
            is Row.Song -> 3
            is Row.Album -> if (albumGrid) 4 else 6
            is Row.Artist -> 5
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = when (viewType) {
                0 -> R.layout.item_lib_header
                1 -> R.layout.item_detail_header
                2 -> R.layout.item_folder
                3 -> R.layout.item_track
                4 -> R.layout.item_album
                6 -> R.layout.item_album_row
                else -> R.layout.item_artist
            }
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            when (val r = rows[position]) {
                Row.Header -> bindHeader(v)
                is Row.DetailHeader -> bindDetail(v, r)
                is Row.Folder -> bindFolder(v, r)
                is Row.Song -> bindSong(v, r)
                is Row.Album -> {
                    Thumbs.bind(v.findViewById(R.id.art), r.thumb)
                    v.findViewById<TextView>(R.id.title).text = r.title
                    v.findViewById<TextView>(R.id.subtitle).text = "${r.artist} · ${r.tracks.size} 曲"
                    v.setOnClickListener { openDetail(Detail(Mode.ALBUMS, r.key, r.title)) }
                    v.setOnLongClickListener { groupMenu(v, r.title, r.tracks, albumRefOf(r.tracks)); true }
                    v.findViewById<View>(R.id.more).setOnClickListener { groupMenu(it, r.title, r.tracks, albumRefOf(r.tracks)) }
                }
                is Row.Artist -> {
                    Thumbs.bind(v.findViewById(R.id.art), r.thumb)
                    v.findViewById<TextView>(R.id.title).text = r.name
                    v.findViewById<TextView>(R.id.subtitle).text =
                        "${r.tracks.map { albumKey(it) }.distinct().size} アルバム · ${r.tracks.size} 曲"
                    v.setOnClickListener { openDetail(Detail(Mode.ARTISTS, r.name, r.name)) }
                    v.setOnLongClickListener { groupMenu(v, r.name, r.tracks, PlayLog.artistRef(r.name)); true }
                    v.findViewById<View>(R.id.more).setOnClickListener { groupMenu(it, r.name, r.tracks, PlayLog.artistRef(r.name)) }
                }
            }
        }

        private fun bindHeader(v: View) {
            val all = Repository.tracks.value
            val count = v.findViewById<TextView>(R.id.count)
            val option = v.findViewById<MaterialButton>(R.id.option)
            val playRow = v.findViewById<View>(R.id.playRow)
            val banner = v.findViewById<View>(R.id.banner)
            val bannerText = v.findViewById<TextView>(R.id.bannerText)
            val bannerButton = v.findViewById<MaterialButton>(R.id.bannerButton)

            val missingCount = Repository.missing.value.count { Repository.cantPlay(it) }
            when {
                !main.libraryPermission.value -> {
                    banner.visibility = View.VISIBLE
                    bannerText.text = "${main.scanFoldersLabel()} の曲を表示するには、音楽ファイルへのアクセス許可が必要です"
                    bannerButton.text = "許可する"
                    bannerButton.setOnClickListener { main.requestLibraryPermission() }
                }
                missingCount > 0 -> {
                    banner.visibility = View.VISIBLE
                    bannerText.text = "見つからない曲が $missingCount 曲あります(グレー表示)"
                    bannerButton.text = "整理"
                    bannerButton.setOnClickListener {
                        val gone = Repository.tracks.value.filter { Repository.cantPlay(it.id) }
                        main.actions.confirmDelete(gone)
                    }
                }
                else -> banner.visibility = View.GONE
            }

            val n = visibleSongs.size
            val q = query.isNotBlank()
            count.text = when (mode) {
                Mode.ALBUMS -> "${rows.count { it is Row.Album }} アルバム"
                Mode.ARTISTS -> "${rows.count { it is Row.Artist }} 人のアーティスト"
                else -> if (q) "$n / ${all.size} 曲" else "${all.size} 曲 · ${formatTotal(all.sumOf { it.durationMs })}"
            }
            when (mode) {
                Mode.SONGS -> {
                    option.visibility = View.VISIBLE
                    option.text = sortNames[sort]
                    option.setIconResource(R.drawable.ic_sort)
                    option.setOnClickListener { b ->
                        PopupMenu(requireContext(), b).apply {
                            sortNames.forEachIndexed { i, s -> menu.add(0, i, i, s) }
                            setOnMenuItemClickListener {
                                sort = it.itemId; prefs.edit().putInt("sort", sort).apply(); rebuild(); true
                            }
                        }.show()
                    }
                }
                Mode.FOLDERS -> {
                    option.visibility = if (q) View.GONE else View.VISIBLE
                    val anyOpen = expanded.isNotEmpty()
                    option.text = if (anyOpen) "すべて閉じる" else "すべて開く"
                    option.setIconResource(if (anyOpen) R.drawable.ic_unfold_less else R.drawable.ic_unfold_more)
                    option.setOnClickListener { setAllExpanded(!anyOpen) }
                }
                Mode.ALBUMS -> {
                    option.visibility = View.VISIBLE
                    option.text = if (albumGrid) "リスト表示" else "ジャケット表示"
                    option.setIconResource(if (albumGrid) R.drawable.ic_list else R.drawable.ic_grid)
                    option.setOnClickListener {
                        albumGrid = !albumGrid
                        prefs.edit().putBoolean("album_grid", albumGrid).apply()
                        rebuild()
                    }
                }
                else -> option.visibility = View.GONE
            }
            playRow.visibility = if (n > 0 && (mode == Mode.SONGS || mode == Mode.FOLDERS)) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.playAll).setOnClickListener { main.player.play(visibleSongs, 0) }
            v.findViewById<View>(R.id.shuffleAll).setOnClickListener { main.player.play(visibleSongs, 0, shuffle = true) }
        }

        private fun bindDetail(v: View, r: Row.DetailHeader) {
            Thumbs.bind(v.findViewById(R.id.art), r.thumb)
            v.findViewById<TextView>(R.id.title).text = r.title
            v.findViewById<TextView>(R.id.subtitle).text = r.subtitle
            v.findViewById<View>(R.id.back).setOnClickListener { detail = null; rebuild() }
            v.findViewById<View>(R.id.play).setOnClickListener { main.player.play(r.tracks, 0) }
            v.findViewById<View>(R.id.shuffle).setOnClickListener { main.player.play(r.tracks, 0, shuffle = true) }
            v.findViewById<View>(R.id.share).setOnClickListener {
                main.actions.share(r.title, if (detail?.kind == Mode.ALBUMS) Share.Kind.ALBUM else Share.Kind.PLAYLIST, r.tracks)
            }
            v.findViewById<View>(R.id.more).setOnClickListener {
                val ref = when (detail?.kind) {
                    Mode.ALBUMS -> albumRefOf(r.tracks)
                    Mode.ARTISTS -> detail?.let { d -> PlayLog.artistRef(d.key) }
                    else -> null
                }
                groupMenu(it, r.title, r.tracks, ref)
            }
        }

        private fun bindFolder(v: View, r: Row.Folder) {
            val dp = resources.displayMetrics.density
            v.findViewById<Space>(R.id.indent).layoutParams.width = (r.depth * 20 * dp).toInt()
            v.findViewById<Space>(R.id.indent).requestLayout()
            v.findViewById<ImageView>(R.id.chevron).rotation = if (r.expanded) 90f else 0f
            val all = r.node.all
            Thumbs.bind(v.findViewById(R.id.art), all.firstOrNull { it.thumbPath != null }?.thumbPath)
            v.findViewById<TextView>(R.id.name).text = r.node.name
            val subs = r.node.children.size
            v.findViewById<TextView>(R.id.info).text = buildList {
                if (subs > 0) add("$subs フォルダ")
                add("${all.size} 曲")
                val total = all.sumOf { it.durationMs }
                if (total > 0) add(formatTotal(total))
            }.joinToString(" · ")
            v.setOnClickListener { if (query.isBlank()) toggleFolder(r.node.path) }
            v.setOnLongClickListener { groupMenu(v, r.node.name.substringAfterLast(" / "), all); true }
            v.findViewById<ImageButton>(R.id.play).setOnClickListener { main.player.play(albumOrder(all), 0) }
            v.findViewById<ImageButton>(R.id.more).setOnClickListener { groupMenu(it, r.node.name.substringAfterLast(" / "), albumOrder(all)) }
        }

        private fun bindSong(v: View, r: Row.Song) {
            val t = r.track
            val dp = resources.displayMetrics.density
            v.setPaddingRelative(((16 + r.depth * 20) * dp).toInt(), v.paddingTop, v.paddingEnd, v.paddingBottom)
            val title = v.findViewById<TextView>(R.id.title)
            if (title.tag == null) title.tag = title.textColors
            val inAlbum = detail?.kind == Mode.ALBUMS
            title.text = if (inAlbum) "${if (t.trackNo > 0) t.trackNo else r.index + 1}. ${t.title}" else t.title
            val gone = !Net.canPlayNow(t)
            val online = Repository.isOnline(t.id)
            v.findViewById<TextView>(R.id.subtitle).text = buildList {
                // 保存の状態: 印なし = 端末にある / ☁✓ 一時保存済み / ☁ オンライン
                when {
                    gone && online -> add("☁ 今は再生できません")
                    gone -> add("ファイルが見つかりません")
                    online -> add(if (StreamCache.isCached(requireContext(), youtubeVideoId(t.sourceUrl))) "☁✓" else "☁")
                }
                if (t.hasVideo) add("🎬")
                DownloadManager.progressFor(t)?.let { add(it) }
                if ((mode != Mode.ARTISTS || detail == null) && t.artist.isNotBlank()) add(t.artist)
                if (mode == Mode.SONGS && t.album.isNotBlank()) add(t.album)
                if (t.durationMs > 0) add(formatTime(t.durationMs))
            }.joinToString(" · ")
            Thumbs.bind(v.findViewById(R.id.art), t.thumbPath)
            v.alpha = if (gone) 0.38f else 1f
            v.findViewById<View>(R.id.check).visibility = if (t.id in selected) View.VISIBLE else View.GONE
            if (t.id == main.nowPlayingId.value) {
                title.setTextColor(MaterialColors.getColor(title, androidx.appcompat.R.attr.colorPrimary))
            } else {
                title.setTextColor(title.tag as ColorStateList)
            }
            v.setOnClickListener {
                if (selected.isNotEmpty()) toggleSelect(t.id) else main.player.play(r.queue, r.index)
            }
            v.setOnLongClickListener { toggleSelect(t.id); true }
            v.findViewById<View>(R.id.more).setOnClickListener { trackMenu(it, t) }
        }
    }
}

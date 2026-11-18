package com.example.ytmp3

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment(R.layout.fragment_playlists) {
    private val main get() = requireActivity() as MainActivity
    private val adapter = Adapter()

    private val sortNames = arrayOf("手動(長押しで動かして並べ替え)", "新しい順", "名前順", "曲数が多い順", "最近再生した順")
    /** true = グリッド(大きなジャケットを 2 列) / false = リスト */
    private var grid = true
    private var sortButtonRef: com.google.android.material.button.MaterialButton? = null
    private val prefs by lazy { requireContext().getSharedPreferences("library_ui", android.content.Context.MODE_PRIVATE) }
    private var sort = 0
    private var dragging = false
    private lateinit var touchHelper: ItemTouchHelper
    private var toExport: List<Playlist> = emptyList()

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) export(uri) }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) import(uri) }

    /** プレイリストをファイルに保存(場所はシステムの画面で選ぶ) */
    fun startExport(lists: List<Playlist>) {
        if (lists.isEmpty()) return
        toExport = lists
        runCatching { exportLauncher.launch(PlaylistIO.fileName(lists)) }
    }

    private fun export(uri: android.net.Uri) {
        val lists = toExport
        viewLifecycleOwner.lifecycleScope.launch {
            val r = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PlaylistIO.export(requireContext(), lists, uri) }
            }
            toast(r.fold({ if (lists.size == 1) "「${lists[0].name}」を保存しました" else "${lists.size} 個のプレイリストを保存しました" },
                { "保存に失敗しました: ${it.message}" }))
        }
    }

    /** 読み込み先(null = 新しいプレイリストとして作る) */
    private var importTarget: Long? = null

    /** 既存のプレイリストにファイルから読み込む */
    fun startImport(target: Long? = null) {
        importTarget = target
        runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
    }

    private fun import(uri: android.net.Uri) {
        val target = importTarget
        viewLifecycleOwner.lifecycleScope.launch {
            val parsed = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PlaylistIO.parse(requireContext(), uri) }
            }.getOrElse {
                MaterialAlertDialogBuilder(requireContext()).setTitle("読み込めませんでした")
                    .setMessage(it.message ?: "").setPositiveButton("OK", null).show()
                return@launch
            }
            if (parsed.isEmpty()) { toast("プレイリストが入っていません"); return@launch }
            // 複数入っている時は読み込むものを選ぶ
            chooseLists(parsed) { chosen ->
                if (target == null) applyImport(chosen, null, false)
                else {
                    val name = Repository.playlist(target)?.name ?: ""
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("「$name」に読み込む")
                        .setMessage("${chosen.sumOf { it.entries.size }} 曲をどう入れますか？")
                        .setNegativeButton("キャンセル", null)
                        .setNeutralButton("置き換え") { _, _ -> applyImport(chosen, target, true) }
                        .setPositiveButton("末尾に追加") { _, _ -> applyImport(chosen, target, false) }
                        .show()
                }
            }
        }
    }

    private fun chooseLists(parsed: List<PlaylistIO.Parsed>, onChosen: (List<PlaylistIO.Parsed>) -> Unit) {
        if (parsed.size == 1) { onChosen(parsed); return }
        val checked = BooleanArray(parsed.size) { true }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("読み込むプレイリスト")
            .setMultiChoiceItems(parsed.map { "${it.name}(${it.entries.size}曲)" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("読み込む") { _, _ ->
                val chosen = parsed.filterIndexed { i, _ -> checked[i] }
                if (chosen.isNotEmpty()) onChosen(chosen)
            }
            .show()
    }

    private fun applyImport(chosen: List<PlaylistIO.Parsed>, target: Long?, replace: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val r = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    PlaylistIO.apply(requireContext(), chosen, target, replace)
                }
            }
            r.onFailure { MaterialAlertDialogBuilder(requireContext()).setTitle("読み込めませんでした").setMessage(it.message ?: "").setPositiveButton("OK", null).show() }
            r.onSuccess { res ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("プレイリストを読み込みました")
                    .setMessage(PlaylistIO.summary(res))
                    .setPositiveButton("OK", null)
                    .apply {
                        if (res.downloading > 0) setNeutralButton("ダウンロードを見る") { _, _ -> DownloadsSheet.show(main) }
                    }
                    .show()
                if (res.downloading > 0) main.ensureNotificationPermission()
            }
        }
    }

    /** 1 つ選んで保存 */
    private fun chooseAndExport() {
        val lists = Repository.playlists.value
        if (lists.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("保存するプレイリスト")
            .setItems(lists.map { "${it.name}(${it.trackIds.size}曲)" }.toTypedArray()) { _, i -> startExport(listOf(lists[i])) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_SHORT).show()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Repository.migratePlaylistOrder(requireContext())
        sort = prefs.getInt("playlist_sort", 4)
        grid = prefs.getBoolean("playlist_grid", true)
        val list = view.findViewById<RecyclerView>(R.id.list)
        val glm = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        glm.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.isFull(position)) 2 else 1
        }
        list.layoutManager = glm
        list.adapter = adapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            // グリッドでもリストでも、長押しで動かして並び替え(動かしたら並び順は「手動」に)
            override fun isLongPressDragEnabled() = true
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                if (adapter.playlistAt(vh.bindingAdapterPosition) == null) return 0
                val dirs = if (grid) ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    else ItemTouchHelper.UP or ItemTouchHelper.DOWN
                return makeMovementFlags(dirs, 0)
            }
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                if (sort != 0) {
                    sort = 0
                    prefs.edit().putInt("playlist_sort", 0).apply()
                    sortButtonRef?.text = sortNames[0].substringBefore("(")
                }
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (adapter.playlistAt(a) == null || adapter.playlistAt(b) == null) return false
                dragging = true
                adapter.move(a, b)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (dragging) {
                    dragging = false
                    Repository.setPlaylistsOrder(adapter.items.map { it.id })
                }
            }
        })
        touchHelper.attachToRecyclerView(list)

        val empty = view.findViewById<View>(R.id.empty)
        val header = view.findViewById<View>(R.id.header)
        val count = view.findViewById<android.widget.TextView>(R.id.count)
        view.findViewById<View>(R.id.io).setOnClickListener { b ->
            PopupMenu(requireContext(), b).apply {
                menu.add("ファイルから読み込む…").setOnMenuItemClickListener { startImport(null); true }
                if (Repository.playlists.value.isNotEmpty()) {
                    menu.add("プレイリストを選んで保存…").setOnMenuItemClickListener { chooseAndExport(); true }
                    menu.add("すべてのプレイリストを保存…").setOnMenuItemClickListener { startExport(Repository.playlists.value); true }
                }
            }.show()
        }
        // グリッド / リストの切り替え
        (header as ViewGroup).addView(com.google.android.material.button.MaterialButton(requireContext(), null,
            com.google.android.material.R.attr.borderlessButtonStyle).apply {
            fun label() { text = if (grid) "☰ リスト" else "▦ グリッド" }
            label()
            setOnClickListener {
                grid = !grid
                prefs.edit().putBoolean("playlist_grid", grid).apply()
                label()
                render(Repository.playlists.value)
            }
        }, 0)
        val sortButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.sort)
        sortButtonRef = sortButton
        sortButton.text = sortNames[sort].substringBefore("(")
        sortButton.setOnClickListener { b ->
            PopupMenu(requireContext(), b).apply {
                sortNames.forEachIndexed { i, n -> menu.add(0, i, i, n) }
                setOnMenuItemClickListener {
                    sort = it.itemId
                    prefs.edit().putInt("playlist_sort", sort).apply()
                    sortButton.text = sortNames[sort].substringBefore("(")
                    render(Repository.playlists.value)
                    true
                }
            }.show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Repository.playlists, Repository.tracks) { p, _ -> p }.collect {
                    if (!dragging) render(it)
                    empty.visibility = View.GONE
                    sortButton.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
                    count.visibility = View.GONE
                }
            }
        }
    }

    private fun render(all: List<Playlist>) {
        val recentOrder = PlayLog.recent().filter { it.kind == PlayLog.Kind.PLAYLIST }.map { it.key.toLongOrNull() }
        adapter.items = when (sort) {
            1 -> all.sortedByDescending { it.createdAt }
            2 -> all.sortedBy { it.name.lowercase() }
            3 -> all.sortedByDescending { it.trackIds.size }
            4 -> all.sortedBy { p -> recentOrder.indexOf(p.id).let { if (it < 0) Int.MAX_VALUE else it } }
            else -> all // 手動(保存された順)
        }
        adapter.rebuild()
    }

    /** 上の自動プレイリスト(お気に入り・よく聴く曲・最近追加) */
    private fun autoRow(parent: ViewGroup): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun card(label: String, icon: Int, c1: Int, c2: Int, onClick: () -> Unit) {
            val card = com.google.android.material.card.MaterialCardView(ctx).apply {
                radius = 16 * d; strokeWidth = 0
                setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { onClick() }
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.BOTTOM
                setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
            }
            box.addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE) },
                LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()))
            box.addView(TextView(ctx).apply {
                text = label; setTextColor(android.graphics.Color.WHITE)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall); setTextColor(android.graphics.Color.WHITE)
            })
            card.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (96 * d).toInt()))
            row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt()
            })
        }
        card("お気に入り", R.drawable.ic_favorite, 0xFFE2508C.toInt(), 0xFF7B2E7A.toInt()) {
            val l = Liked.list.value
            if (l.isEmpty()) toast("♡ を付けた曲はまだありません") else main.player.playSearch(l, 0)
        }
        card("よく聴く曲", R.drawable.ic_music, 0xFF3D6FE0.toInt(), 0xFF1B2E7A.toInt()) {
            val top = PlayLog.counts().entries.sortedByDescending { it.value }
                .mapNotNull { Repository.track(it.key)?.takeIf { t -> !Repository.cantPlay(t.id) } }.take(50)
            if (top.isEmpty()) toast("まだ再生の記録がありません") else main.player.play(top, 0)
        }
        card("最近追加", R.drawable.ic_add, 0xFF1AA58A.toInt(), 0xFF0E4E5A.toInt()) {
            val l = Repository.tracks.value.filterNot { Repository.cantPlay(it.id) }.sortedByDescending { it.savedAt }.take(50)
            if (l.isEmpty()) toast("まだ曲がありません") else main.player.play(l, 0)
        }
        return row
    }

    /** グリッドのカード(4 分割ジャケット + 名前 + 曲数・時間・保存の状態) */
    private fun gridCard(parent: ViewGroup): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (10 * d).toInt())
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            foreground = androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId)
        }
        val frame = android.widget.FrameLayout(ctx)
        val art = com.google.android.material.imageview.ShapeableImageView(ctx).apply {
            id = R.id.art
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder().setAllCornerSizes(14 * d).build()
        }
        frame.addView(art, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // ジャケットの右下の ▶ で、開かずにそのまま再生
        frame.addView(ImageButton(ctx).apply {
            id = R.id.play
            setImageResource(R.drawable.ic_play)
            contentDescription = "再生"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(com.google.android.material.color.MaterialColors.getColor(box, androidx.appcompat.R.attr.colorPrimary))
            }
            imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(box, com.google.android.material.R.attr.colorOnPrimary))
        }, android.widget.FrameLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt(), android.view.Gravity.END or android.view.Gravity.BOTTOM).apply {
            setMargins(0, 0, (8 * d).toInt(), (8 * d).toInt())
        })
        box.addView(SquareFrame(ctx).apply { addView(frame) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            id = R.id.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setPadding(0, (6 * d).toInt(), 0, 0)
        })
        texts.addView(TextView(ctx).apply {
            id = R.id.info; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall); alpha = 0.75f
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // オフライン / オンラインの切り替え
        row.addView(ImageButton(ctx).apply {
            id = R.id.dl
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
        }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
        row.addView(ImageButton(ctx).apply {
            id = R.id.more
            setImageResource(R.drawable.ic_more)
            contentDescription = "その他"
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
        }, LinearLayout.LayoutParams((32 * d).toInt(), (40 * d).toInt()))
        box.addView(row)
        return box
    }

    /** 正方形の枠(幅に合わせて高さを決める) */
    private class SquareFrame(c: android.content.Context) : android.widget.FrameLayout(c) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
    }

    /** 並び順を「手動」に(今見えている順を手動の順として保存) */
    private fun toManual() {
        if (sort == 0) return
        Repository.setPlaylistsOrder(adapter.items.map { it.id })
        sort = 0
        prefs.edit().putInt("playlist_sort", 0).apply()
        sortButtonRef?.text = sortNames[0].substringBefore("(")
    }

    /** プレイリストごとのオフライン / オンライン切り替え */
    private fun toggleOffline(p: Playlist) {
        val ts = Repository.tracksOf(p)
        val online = ts.filter { Repository.isOnline(it.id) }
        val localYt = ts.filter { !Repository.isMissing(it.id) && youtubeVideoId(it.sourceUrl) != null }
        when {
            online.isNotEmpty() -> MaterialAlertDialogBuilder(requireContext())
                .setTitle("「${p.name}」をすべてダウンロード")
                .setMessage("☁ の ${online.size} 曲をダウンロードします")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("ダウンロード") { _, _ -> DownloadManager.redownload(requireContext(), online) }
                .show()
            localYt.isNotEmpty() -> main.actions.removeDownload(localYt)
            else -> toast("自分で入れた曲は切り替えられません")
        }
    }

    private fun bindToggle(b: ImageButton, p: Playlist, tracks: List<Track>) {
        val online = tracks.count { Repository.isOnline(it.id) }
        val canOnline = tracks.any { !Repository.isMissing(it.id) && youtubeVideoId(it.sourceUrl) != null }
        b.visibility = if (online > 0 || canOnline) View.VISIBLE else View.GONE
        // ⬇ = まだネットの曲がある(押すとダウンロード) / ダウンロード済みの印 = 全部オフライン(押すと削除の確認)
        b.setImageResource(if (online > 0) R.drawable.ic_download else R.drawable.ic_download_done)
        b.imageTintList = android.content.res.ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(b,
            if (online > 0) com.google.android.material.R.attr.colorOnSurfaceVariant else androidx.appcompat.R.attr.colorPrimary))
        b.contentDescription = if (online > 0) "すべてダウンロード" else "ダウンロードをすべて削除"
        b.setOnClickListener { toggleOffline(p) }
    }

    private fun statusOf(tracks: List<Track>): String = when {
        tracks.isEmpty() -> ""
        tracks.any { Repository.isOnline(it.id) } -> " · ☁"
        else -> ""
    }

    fun createNew() {
        main.actions.promptName("新しいプレイリスト", "") { name ->
            val p = Repository.createPlaylist(name)
            open(p)
        }
    }

    private fun open(p: Playlist) {
        startActivity(Intent(requireContext(), PlaylistDetailActivity::class.java).putExtra(PlaylistDetailActivity.EXTRA_ID, p.id))
    }

    /** プレイリストのメニュー: ほかでできること(再生・シャッフル・並び替え)は出さない */
    private fun menu(anchor: View, p: Playlist) {
        PopupMenu(requireContext(), anchor).apply {
            val ts = Repository.tracksOf(p)
            menu.add("キューに追加").setOnMenuItemClickListener { main.player.addToQueue(ts); true }
            menu.add("共有…").setOnMenuItemClickListener { main.actions.share(p.name, Share.Kind.PLAYLIST, ts); true }
            main.actions.addVideoGroupMenu(menu, ts)
            // グリッドはカードのボタンで切り替えられるので、リストの時だけ出す
            if (!grid) {
                if (ts.any { Repository.isOnline(it.id) }) menu.add("⬇ すべてダウンロード").setOnMenuItemClickListener { toggleOffline(p); true }
                else if (ts.any { !Repository.isMissing(it.id) && youtubeVideoId(it.sourceUrl) != null })
                    menu.add("☁ ダウンロードをすべて削除").setOnMenuItemClickListener { toggleOffline(p); true }
            }
            val pinned = PlayLog.isPinned(PlayLog.playlistRef(p))
            menu.add(if (pinned) "ピン留めを外す" else "ホームにピン留め").setOnMenuItemClickListener { PlayLog.togglePin(PlayLog.playlistRef(p)); true }
            menu.add("名前を変更").setOnMenuItemClickListener {
                main.actions.promptName("名前を変更", p.name) { Repository.renamePlaylist(p.id, it.ifBlank { p.name }) }; true
            }
            menu.addSubMenu("ファイル").apply {
                add("ファイルに保存…").setOnMenuItemClickListener { startExport(listOf(p)); true }
                add("ファイルから読み込む…").setOnMenuItemClickListener { startImport(p.id); true }
            }
            menu.add("削除").setOnMenuItemClickListener {
                main.actions.confirmDeletePlaylist(p); true
            }
        }.show()
    }

    inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Playlist> = emptyList()
        /** 0 = 自動プレイリスト, 1 = 新規作成, 2 = プレイリスト */
        private var rows: List<Any> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun rebuild() {
            rows = listOf<Any>("auto", "new") + items
            notifyDataSetChanged()
        }

        fun playlistAt(pos: Int): Playlist? = rows.getOrNull(pos) as? Playlist
        fun isFull(pos: Int) = !grid || rows.getOrNull(pos) == "auto"

        fun move(a: Int, b: Int) {
            val m = rows.toMutableList()
            m.add(b, m.removeAt(a))
            rows = m
            items = m.filterIsInstance<Playlist>()
            notifyItemMoved(a, b)
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (val r = rows[position]) {
            "auto" -> 0
            "new" -> if (grid) 1 else 3
            else -> if (grid) 2 else 4
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = when (viewType) {
                0 -> autoRow(parent)
                1, 2 -> gridCard(parent)
                else -> LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            val d = resources.displayMetrics.density
            when (val r = rows[position]) {
                "auto" -> {}
                "new" -> {
                    v.findViewById<TextView>(R.id.name).text = "新規作成"
                    v.findViewById<TextView>(R.id.info).text = "プレイリストを作る"
                    v.findViewById<ImageView>(R.id.art).apply {
                        setImageResource(R.drawable.ic_add); scaleType = ImageView.ScaleType.CENTER
                        setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(v,
                            com.google.android.material.R.attr.colorSurfaceContainerHigh))
                    }
                    v.findViewById<View>(R.id.play).visibility = View.GONE
                    v.findViewById<View>(R.id.dl)?.visibility = View.GONE
                    v.findViewById<View>(R.id.more)?.visibility = View.GONE
                    v.findViewById<View>(R.id.drag)?.visibility = View.GONE
                    v.setOnClickListener { createNew() }
                }
                is Playlist -> {
                    val p = r
                    val tracks = Repository.tracksOf(p)
                    v.findViewById<TextView>(R.id.name).text = p.name
                    // グリッドはカードの ⬇ / ✓ ボタンで状態が分かるので、文字では出さない
                    v.findViewById<TextView>(R.id.info).text =
                        "${tracks.size} 曲 · ${formatTotal(tracks.sumOf { it.durationMs })}" + (if (grid) "" else statusOf(tracks))
                    val art = v.findViewById<ImageView>(R.id.art)
                    art.scaleType = ImageView.ScaleType.CENTER_CROP
                    art.background = null
                    Covers.bind(art, tracks, (if (grid) 320 else 160) * d.toInt().coerceAtLeast(1) / 2)
                    val play = v.findViewById<ImageButton>(R.id.play)
                    v.findViewById<ImageButton>(R.id.dl)?.let { bindToggle(it, p, tracks) }
                    if (grid) {
                        // グリッド: タップで中身を開く、▶ でそのまま再生、長押しでメニュー
                        play.visibility = if (tracks.isEmpty()) View.GONE else View.VISIBLE
                        play.setOnClickListener { main.player.play(tracks, 0, ref = PlayLog.playlistRef(p)) }
                        v.setOnClickListener { open(p) }
                        v.setOnLongClickListener(null)
                        v.isLongClickable = false
                        v.findViewById<View>(R.id.more)?.apply { visibility = View.VISIBLE; setOnClickListener { menu(it, p) } }
                    } else {
                        // リスト: 今まで通り(タップで再生、› で開く)
                        v.setOnClickListener { if (tracks.isEmpty()) open(p) else main.player.play(tracks, 0, ref = PlayLog.playlistRef(p)) }
                        play.visibility = View.VISIBLE
                        play.setImageResource(R.drawable.ic_chevron_right)
                        play.contentDescription = "開く"
                        play.setOnClickListener { open(p) }
                        v.findViewById<View>(R.id.more).apply { visibility = View.VISIBLE; setOnClickListener { menu(it, p) } }
                        v.findViewById<View>(R.id.drag).apply {
                            visibility = View.VISIBLE
                            setOnTouchListener { _, e ->
                                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) touchHelper.startDrag(h)
                                false
                            }
                        }
                    }
                }
            }
        }
    }
}

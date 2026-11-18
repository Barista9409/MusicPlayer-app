package com.example.ytmp3

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** 「さがす」タブ: YouTube / YouTube Music を検索して試聴・追加 */
class SearchFragment : Fragment(R.layout.fragment_search) {
    private val main get() = requireActivity() as MainActivity
    private val adapter = Adapter()
    private val prefs by lazy { requireContext().getSharedPreferences("search", Context.MODE_PRIVATE) }

    private lateinit var query: EditText
    private lateinit var loading: LinearProgressIndicator
    private lateinit var idle: View
    private lateinit var history: ChipGroup
    private lateinit var hint: TextView

    private var source = Searcher.Source.MUSIC
    /** 種類は検索先ごとに覚えておく(Music は 曲、YouTube は 動画 から) */
    private var musicSection = Searcher.Section.SONGS
    private var ytSection = Searcher.Section.VIDEOS
    private var section: Searcher.Section
        get() = if (source == Searcher.Source.YOUTUBE) ytSection else musicSection
        set(v) { if (source == Searcher.Source.YOUTUBE) ytSection = v else musicSection = v }
    private var results: List<Searcher.Result> = emptyList()
    /** YouTube の並び順 */
    private var sort = Searcher.Sort.RELEVANCE
    /** 並び順。人気順・新しい順はアプリ側で並べる(YouTube の人気順だけは YouTube 側) */
    enum class Order(val label: String, val key: String) {
        REL("関連度", "rel"), POP("🔥 人気順", "pop"), NEW("🆕 新しい順", "new"), NAME("名前順", "name")
    }
    /** 見つける(ジャンル・ランキング)の並び。元の順は「おすすめ順」 */
    private var browseOrder = Order.REL
    private var browseOriginal: List<Searcher.Result> = emptyList()

    private fun browseOrders(): List<Order> = buildList {
        add(Order.REL)
        if (browseOriginal.any { it.views > 0 }) add(Order.POP)
        add(Order.NAME)
    }

    private fun orderLabel(o: Order) = if (browsing != null && o == Order.REL) "おすすめ順" else o.label
    private var musicOrder = Order.REL
    private var ytOrder = Order.REL

    /** その種類で選べる並び(回数や日付が取れるものだけ) */
    private fun ordersFor(src: Searcher.Source, sec: Searcher.Section): List<Order> = when {
        src == Searcher.Source.YOUTUBE && sec == Searcher.Section.VIDEOS -> listOf(Order.REL, Order.POP, Order.NEW)
        src == Searcher.Source.YOUTUBE -> listOf(Order.REL)
        sec == Searcher.Section.ALBUMS -> listOf(Order.REL, Order.NEW)
        sec == Searcher.Section.ARTISTS -> listOf(Order.REL)
        else -> listOf(Order.REL, Order.POP)
    }

    private fun orderFor(src: Searcher.Source, sec: Searcher.Section): Order {
        val o = if (src == Searcher.Source.YOUTUBE) ytOrder else musicOrder
        return if (o in ordersFor(src, sec)) o else Order.REL
    }

    /** アプリ側で並べ替えるか */
    private fun clientOrder(src: Searcher.Source, sec: Searcher.Section): Order? {
        val o = orderFor(src, sec)
        return when {
            o == Order.REL -> null
            src == Searcher.Source.YOUTUBE && o == Order.POP -> null // YouTube 側で並べる
            else -> o
        }
    }

    private fun arrange(list: List<Searcher.Result>, o: Order?) = when (o) {
        Order.POP -> byPopularity(list)
        Order.NAME -> list.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.JAPANESE)) { it.title })
        Order.NEW -> list.withIndex().sortedWith(compareByDescending<IndexedValue<Searcher.Result>> { it.value.released }.thenBy { it.index }).map { it.value }
        else -> list
    }

    /** 人気順: 回数の多い順(回数が分からないものは後ろに、元の順のまま) */
    private fun byPopularity(list: List<Searcher.Result>) =
        list.withIndex().sortedWith(compareByDescending<IndexedValue<Searcher.Result>> { it.value.views }.thenBy { it.index }).map { it.value }
    /** 検索語がアーティスト名の時の「人気曲」(結果の上に出す) */
    private var top: InnerTube.TopSongs? = null
    private var topJob: Job? = null
    private var lastQuery = ""
    /** 続きを読むための鍵(下にスクロールした時に使う) */
    private var nextToken: String? = null
    private var loadingMore = false
    private var moreJob: Job? = null
    private var searchJob: Job? = null
    /** 試聴の URL を取得中の項目 */
    private var preparingId: String? = null

    /** ライブラリにある動画 ID / ダウンロード中の動画 ID */
    private var inLibrary: Set<String> = emptySet()
    /** ライブラリにあるがオンライン(☁)の曲 */
    private var inOnline: Set<String> = emptySet()
    private var inQueue: Set<String> = emptySet()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        query = view.findViewById(R.id.query)
        loading = view.findViewById(R.id.loading)
        idle = view.findViewById(R.id.idle)
        val idleBox = view.findViewById<ViewGroup>(R.id.idleBox)
        setupUrlButtons(idleBox)
        setupDiscover(idleBox)
        history = view.findViewById(R.id.history)
        hint = view.findViewById(R.id.hint)
        val clear = view.findViewById<View>(R.id.clear)

        val list = view.findViewById<RecyclerView>(R.id.results)
        // アーティストは丸いカードを 3 列で。見出し・曲などは横いっぱい
        list.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3).apply {
            spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == 3) 1 else 3
            }
        }
        list.adapter = adapter
        list.itemAnimator = null
        // 下までスクロールしたら続きを読み込む
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val last = (rv.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
                if (dy > 0 && last >= adapter.itemCount - 7) loadMore()
            }
        })

        source = if (prefs.getString("source", "music") == "youtube") Searcher.Source.YOUTUBE else Searcher.Source.MUSIC
        val toggle = view.findViewById<MaterialButtonToggleGroup>(R.id.source)
        toggle.check(if (source == Searcher.Source.MUSIC) R.id.srcMusic else R.id.srcYoutube)
        musicOrder = Order.entries.firstOrNull { it.key == prefs.getString("musicOrder", null) }
            ?: if (prefs.getBoolean("musicPopular", false)) Order.POP else Order.REL
        ytOrder = Order.entries.firstOrNull { it.key == prefs.getString("ytOrder", null) }
            ?: if (prefs.getString("ytSort", "") == "views") Order.POP else Order.REL
        sort = if (ytOrder == Order.POP) Searcher.Sort.VIEWS else Searcher.Sort.RELEVANCE
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            source = if (id == R.id.srcYoutube) Searcher.Source.YOUTUBE else Searcher.Source.MUSIC
            prefs.edit().putString("source", if (source == Searcher.Source.YOUTUBE) "youtube" else "music").apply()
            buildChips()
            if (lastQuery.isNotBlank()) search(lastQuery)
        }

        buildChips()

        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search(query.text.toString()); true
            } else false
        }
        // 空の時は 📋 貼り付け、入力中は ✕ クリア
        val paste = view.findViewById<View>(R.id.paste)
        query.doAfterTextChanged {
            clear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
            paste.visibility = if (it.isNullOrEmpty()) View.VISIBLE else View.GONE
            requestSuggestions(it?.toString().orEmpty())
        }
        query.setOnFocusChangeListener { _, has -> if (!has) hideSuggestions() }
        // 入力中の候補(結果の上に重ねて出す)
        suggestBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = 6 * resources.displayMetrics.density
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(view,
                com.google.android.material.R.attr.colorSurfaceContainerHigh))
            isClickable = true
        }
        (list.parent as ViewGroup).addView(suggestBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paste.setOnClickListener { pasteFromClipboard() }
        clear.setOnClickListener {
            goIdle()
            query.requestFocus()
            requireContext().getSystemService(InputMethodManager::class.java)?.showSoftInput(query, 0)
        }
        resultBar = view.findViewById(R.id.resultBar)
        resultInfo = view.findViewById(R.id.resultInfo)
        sortButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.sortButton).also { b ->
            b.setOnClickListener { showOrderMenu(it) }
        }
        browseScroll = view.findViewById(R.id.browseScroll)
        browseChips = view.findViewById(R.id.browseChips)
        sectionsScroll = view.findViewById(R.id.sectionsScroll)
        // 結果を見ている時の「戻る」は、最初の画面(見つける・履歴)へ
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToIdle)

        showIdle()
        // 🔒 の曲の別の音源が見つかった・無かった時に描き直す
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { AltSources.changed.collect { refreshList() } }
        }
        // 経路と時間の表示(遅い時の原因が分かるように)
        val diag = view.findViewById<TextView>(R.id.diag)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 速さなどの細かい情報は出さず、うまくいかない時だけ表示
                Searcher.status.collect {
                    val problem = it.contains("失敗") || it.contains("エラー") || it.contains("見つかりません")
                    diag.text = it; diag.visibility = if (problem) View.VISIBLE else View.GONE
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // ダウンロードの進み具合(%)では描き直さない: 待ち・ダウンロード中の曲が変わった時だけ
                combine(Repository.tracks, DownloadManager.jobs, Repository.missing) { t, j, m ->
                    Triple(t, j.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
                        .mapNotNull { youtubeVideoId(it.url) }.toSet(), m)
                }.distinctUntilChanged().collect { (tracks, queued, _) ->
                    inQueue = queued
                    // 隠れている間は計算しない(ライブラリ全体を調べるので重い)。表示された時に 1 回
                    if (isHidden) { ownedStale = true; return@collect }
                    recomputeOwned(tracks)
                }
            }
        }
    }

    /** 絞り込みのチップ: Music は種類(曲・アルバム…)、YouTube は並び順 */
    private var resultBar: View? = null
    private var resultInfo: TextView? = null
    private var sortButton: com.google.android.material.button.MaterialButton? = null

    /** 結果の上のバー: 「「〇〇」の曲 · 58 件」と並び順ボタン */
    private fun updateResultBar() {
        val bar = resultBar ?: return
        val hasResults = results.isNotEmpty() || top != null
        bar.visibility = if (hasResults && lastQuery.isNotBlank()) View.VISIBLE else View.GONE
        if (!hasResults) return
        val kind = if (source == Searcher.Source.YOUTUBE) "YouTube の" + section.labelFor(source) else section.label
        resultInfo?.text = browsing?.let { "$it · ${results.size} 件" }
            ?: "「$lastQuery」の$kind · ${results.size} 件" + if (nextToken != null) "+" else ""
        val cur = if (browsing != null) browseOrder else orderFor(source, section)
        sortButton?.visibility = View.VISIBLE
        sortButton?.text = orderLabel(cur).replace("🔥 ", "").replace("🆕 ", "")
        sortButton?.contentDescription = "並び順を選ぶ(今: ${orderLabel(cur)})"
    }

    private fun showOrderMenu(v: View) {
        if (browsing != null) {
            // 見つける: 読み込み済みの一覧をその場で並べ替え
            val opts = browseOrders()
            android.widget.PopupMenu(requireContext(), v).apply {
                opts.forEachIndexed { i, o -> menu.add(0, i, i, (if (o == browseOrder) "✓ " else "　 ") + orderLabel(o)) }
                setOnMenuItemClickListener { mi ->
                    browseOrder = opts[mi.itemId]
                    results = arrange(browseOriginal, browseOrder)
                    refreshList()
                    true
                }
            }.show()
            return
        }
        val cur = orderFor(source, section)
        val opts = ordersFor(source, section)
        android.widget.PopupMenu(requireContext(), v).apply {
            opts.forEachIndexed { i, o -> menu.add(0, i, i, (if (o == cur) "✓ " else "　 ") + o.label) }
            setOnMenuItemClickListener { mi ->
                val o = opts[mi.itemId]
                if (source == Searcher.Source.YOUTUBE) {
                    ytOrder = o
                    sort = if (o == Order.POP) Searcher.Sort.VIEWS else Searcher.Sort.RELEVANCE
                    prefs.edit().putString("ytOrder", o.key).apply()
                } else {
                    musicOrder = o
                    prefs.edit().putString("musicOrder", o.key).apply()
                }
                updateResultBar()
                if (lastQuery.isNotBlank() && browsing == null && o != cur) search(lastQuery)
                true
            }
        }.show()
    }

    private fun refreshList() {
        adapter.notifyDataSetChanged()
        updateResultBar()
    }

    // ───────── 入力中の候補 ─────────

    private var suggestBox: LinearLayout? = null
    private var suggestJob: Job? = null
    /** 候補を選んだ・検索した直後は出さない */
    private var suppressSuggest = false

    private fun hideSuggestions() {
        suggestJob?.cancel()
        suggestBox?.visibility = View.GONE
    }

    private fun requestSuggestions(text: String) {
        suggestJob?.cancel()
        val t = text.trim()
        if (suppressSuggest || t.isEmpty() || looksLikeUrl(t) || !query.hasFocus()) { hideSuggestions(); return }
        suggestJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(250)
            val list = runCatching { withContext(Dispatchers.IO) { InnerTube.suggestions(t) } }.getOrNull().orEmpty()
            if (query.text.toString().trim() != t || !query.hasFocus() || lastQuery == t) return@launch
            showSuggestions(list)
        }
    }

    private fun showSuggestions(list: List<String>) {
        val box = suggestBox ?: return
        box.removeAllViews()
        if (list.isEmpty()) { box.visibility = View.GONE; return }
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        list.forEach { sug ->
            box.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((20 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
                setBackgroundResource(tv.resourceId)
                addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_search); alpha = 0.6f },
                    LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()))
                addView(TextView(ctx).apply {
                    text = sug
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    setPadding((16 * d).toInt(), 0, 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // ↖: 検索欄に入れて続きを打つ
                addView(ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_north_west)
                    contentDescription = "検索欄に入れる"
                    val b = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, b, true)
                    setBackgroundResource(b.resourceId)
                    alpha = 0.6f
                    setOnClickListener { query.setText("$sug "); query.setSelection(query.text.length) }
                }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
                setOnClickListener {
                    suppressSuggest = true
                    query.setText(sug)
                    query.setSelection(sug.length)
                    suppressSuggest = false
                    hideSuggestions()
                    search(sug)
                }
            })
        }
        box.visibility = View.VISIBLE
    }

    private fun setSource(src: Searcher.Source) {
        source = src
        prefs.edit().putString("source", if (src == Searcher.Source.YOUTUBE) "youtube" else "music").apply()
    }

    private fun buildChips() {
        val sections = view?.findViewById<ChipGroup>(R.id.sections) ?: return
        sections.removeAllViews()
        fun chip(label: String, checked: Boolean, onPick: () -> Unit) = sections.addView(Chip(requireContext()).apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
            isChecked = checked
            setOnClickListener { onPick() }
        })
        // 種類。いつでも 1 タップで切り替え(YouTube は 動画・チャンネル・プレイリスト)
        val kinds = if (source == Searcher.Source.MUSIC) Searcher.Section.entries
            else listOf(Searcher.Section.VIDEOS, Searcher.Section.ARTISTS, Searcher.Section.PLAYLISTS)
        kinds.forEach { s ->
            chip(s.labelFor(source), browsing == null && s == section) {
                // ジャンルを見ている時は、そのジャンル名でこの種類を探す
                val genre = browsing?.replace(Regex("^[^\\p{L}\\p{N}]+"), "")?.replace("(日本)", "")?.trim()
                if (section != s || genre != null) {
                    section = s
                    buildChips()
                    val q = genre ?: lastQuery
                    if (q.isNotBlank()) { query.setText(q); search(q) }
                } else buildChips()
            }
        }
        view?.findViewById<View>(R.id.sectionsScroll)?.visibility = View.VISIBLE
        updateResultBar()
    }

    // ───────── 見つける: ランキング・ムード/ジャンル ─────────

    private var moodsCache: List<InnerTube.Mood>? = null

    private fun setupDiscover(box: ViewGroup) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        box.addView(TextView(ctx).apply {
            text = "見つける"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        }, 1)
        val chips = ChipGroup(ctx)
        discoverChips = chips
        box.addView(chips, 2, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (12 * d).toInt() })
        renderDiscover()
        viewLifecycleOwner.lifecycleScope.launch {
            if (moodsCache == null) moodsCache = runCatching { withContext(Dispatchers.IO) { InnerTube.moods() } }.getOrNull()
            renderDiscover()
        }
    }

    private var discoverChips: ChipGroup? = null

    /** 見つけるのボタン: ランキング + 表示するジャンル(編集した順) + ✏ 編集 */
    private fun renderDiscover() {
        val chips = discoverChips ?: return
        val ctx = requireContext()
        chips.removeAllViews()
        fun chip(label: String, onClick: () -> Unit) = chips.addView(Chip(ctx).apply { text = label; setOnClickListener { onClick() } })
        chip("🏆 ランキング") { showBrowse("🏆 ランキング(日本)") { InnerTube.charts() } }
        Genres.load(prefs).forEach { g -> chip(g.title) { openGenre(g) } }
        chips.addView(Chip(ctx).apply {
            text = "編集"
            setChipIconResource(R.drawable.ic_edit)
            isChipIconVisible = true
            contentDescription = "ジャンルを編集(表示・並び順)"
            setOnClickListener { editGenres() }
        })
    }

    // ───────── ジャンルの編集(表示するもの・並び順) ─────────

    private fun openGenre(g: Genres.Genre) {
        val moods = moodsCache.orEmpty()
        showBrowse(g.title) { Genres.playlists(g, moods) }
    }

    /** ジャンルの編集: 並び替え(≡)・削除(✕)・追加(YouTube Music のジャンル / おすすめ / 自分で作る) */
    private fun editGenres() {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val items = Genres.load(prefs).toMutableList()
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (12 * d).toInt(), 0, (8 * d).toInt()) }
        root.addView(TextView(ctx).apply {
            text = "ジャンルを編集"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setPadding((20 * d).toInt(), 0, (20 * d).toInt(), (2 * d).toInt())
        })
        root.addView(TextView(ctx).apply {
            text = "≡ をつかんで並び替え、✕ で削除、「＋ 追加」で増やせます"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            alpha = 0.75f
            setPadding((20 * d).toInt(), 0, (20 * d).toInt(), (8 * d).toInt())
        })
        lateinit var touch: androidx.recyclerview.widget.ItemTouchHelper
        val list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx) }
        val adapterG = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((8 * d).toInt(), 0, (4 * d).toInt(), 0)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt())
                }
                row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_drag)
                    contentDescription = "並び替え"
                    setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
                row.addView(TextView(ctx).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_close)
                    val tv = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                    setBackgroundResource(tv.resourceId)
                }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
                return object : RecyclerView.ViewHolder(row) {}
            }
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val row = h.itemView as LinearLayout
                val g = items[position]
                (row.getChildAt(1) as TextView).text = g.title
                row.getChildAt(2).apply {
                    contentDescription = "「${g.title}」を削除"
                    setOnClickListener {
                        val i = h.bindingAdapterPosition
                        if (i >= 0) { items.removeAt(i); notifyItemRemoved(i) }
                    }
                }
                row.getChildAt(0).setOnTouchListener { _, e ->
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) touch.startDrag(h)
                    false
                }
            }
        }
        touch = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
                val from = a.bindingAdapterPosition; val to = b.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                items.add(to, items.removeAt(from))
                adapterG.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        })
        list.adapter = adapterG
        touch.attachToRecyclerView(list)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.5).toInt()))

        fun addGenre(g: Genres.Genre) {
            if (items.any { it.title == g.title }) return
            items += g
            adapterG.notifyItemInserted(items.lastIndex)
            list.scrollToPosition(items.lastIndex)
        }
        fun createOwn() {
            val input = EditText(ctx).apply { hint = "例: シティポップ、夏うた、作業用BGM"; setSingleLine() }
            val box = android.widget.FrameLayout(ctx).apply {
                setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0); addView(input)
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle("ジャンルを作る")
                .setMessage("この言葉で YouTube Music のプレイリストを探します")
                .setView(box)
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("追加") { _, _ ->
                    val t = input.text.toString().trim()
                    if (t.isNotEmpty()) addGenre(Genres.Genre(t, t))
                }
                .show()
        }
        fun showAdd() {
            val have = items.map { it.title }.toSet()
            // おすすめ(最初の並びにあるもの)+ YouTube Music のジャンルで、まだ入っていないもの
            val cands = (Genres.defaults + moodsCache.orEmpty().map { Genres.Genre(it.title, it.title, listOf(it.title)) })
                .distinctBy { it.title }.filter { it.title !in have }
            val labels = (listOf("✏ 自分で作る…") + cands.map { it.title }).toTypedArray()
            MaterialAlertDialogBuilder(ctx)
                .setTitle("ジャンルを追加")
                .setItems(labels) { _, i -> if (i == 0) createOwn() else addGenre(cands[i - 1]) }
                .setNegativeButton("閉じる", null)
                .show()
        }

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), 0)
        }
        fun btn(label: String, weight: Boolean = false, onClick: () -> Unit) = buttons.addView(
            com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = label; setOnClickListener { onClick() }
            },
            if (weight) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = android.view.Gravity.START }
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        btn("＋ 追加", weight = true) { showAdd() }
        btn("元に戻す") {
            Genres.reset(prefs)
            dialog.dismiss(); renderDiscover(); browsing?.let { setBrowsing(it) }
        }
        btn("完了") {
            Genres.save(prefs, items)
            dialog.dismiss(); renderDiscover(); browsing?.let { setBrowsing(it) }
        }
        root.addView(buttons)
        dialog.setContentView(root)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.isDraggable = false
        dialog.show()
    }

    /** ランキング・ムードのプレイリスト一覧を結果欄に出す(タップで頭から試聴、右のボタンでまとめて追加) */
    private fun showBrowse(title: String, load: () -> List<Searcher.Result>) {
        hideKeyboard()
        searchJob?.cancel(); moreJob?.cancel(); topJob?.cancel()
        top = null
        nextToken = null
        loadingMore = false
        lastQuery = title
        loading.visibility = View.VISIBLE
        idle.visibility = View.GONE
        query.setText("")
        results = emptyList(); refreshList()
        setBrowsing(title)
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { load() } }
            if (lastQuery != title) return@launch
            loading.visibility = View.INVISIBLE
            val list = r.getOrNull().orEmpty()
            browseOriginal = list
            if (browseOrder !in browseOrders()) browseOrder = Order.REL
            results = arrange(list, browseOrder)
            refreshList()
            Searcher.status.value = if (list.isEmpty()) "$title: 見つかりませんでした" else "$title · ${list.size} 件(タップで試聴)"
            if (list.isEmpty()) {
                idle.visibility = View.VISIBLE
                hint.text = "「$title」を読み込めませんでした"
            }
        }
    }

    // ───────── URL から追加(旧「曲を追加」ボタン) ─────────

    private var clipButton: com.google.android.material.button.MaterialButton? = null
    private var clipUrl: String? = null

    private fun looksLikeUrl(s: String) = !s.contains(' ') &&
        (s.startsWith("http://") || s.startsWith("https://") || s.contains("youtu.be/") || s.contains("youtube.com/"))

    private fun setupUrlButtons(box: ViewGroup) {
        val ctx = requireContext()
        clipButton = com.google.android.material.button.MaterialButton(ctx, null,
            com.google.android.material.R.attr.materialButtonStyle).apply {
            setIconResource(R.drawable.ic_paste)
            visibility = View.GONE
            isAllCaps = false
            maxLines = 2
            setOnClickListener {
                clipUrl?.let { u -> openLink(u) }
                visibility = View.GONE
            }
        }
        box.addView(clipButton, 0, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * リンクを開く: YouTube・Spotify・共有リンクは、アプリの「共有」と同じ確認画面へ
     * (曲・プレイリストを一覧で見てから ☁ / ⬇ / ⬇🎬 を選ぶ)。それ以外の URL はこれまでどおり
     */
    private fun openLink(u: String) {
        if (Share.isShare(u) || Spotify.isSpotify(u) || main.isYouTubeUrl(u)) SpotifyImportActivity.open(requireContext(), Spotify.find(u)?.takeIf { Spotify.isSpotify(u) } ?: u)
        else main.quickAdd(u)
    }

    /** 📋: クリップボードの中身が URL ならすぐ追加、文字ならそれで検索 */
    private fun pasteFromClipboard() {
        val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
        val text = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString() }.getOrNull()
            ?.trim().orEmpty()
        if (text.isEmpty()) { toast("コピーされた文字がありません"); return }
        val url = Regex("https?://\\S+").find(text)?.value
        if (Share.isShare(text) || Spotify.isSpotify(text)) {
            clipButton?.visibility = View.GONE
            SpotifyImportActivity.open(requireContext(), if (Share.isShare(text)) text else Spotify.find(text) ?: text)
        } else if (url != null && main.isYouTubeUrl(url)) {
            clipButton?.visibility = View.GONE
            openLink(url)
        } else {
            query.setText(text.lines().first().take(100))
            query.setSelection(query.text.length)
            search(query.text.toString())
        }
    }

    /** コピーした YouTube の URL があれば「コピーした URL を追加」を出す(新しくコピーされた時だけ読む) */
    private fun checkClipboard() {
        val btn = clipButton ?: return
        val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java) ?: return
        val desc = runCatching { cm.primaryClipDescription }.getOrNull() ?: return
        val stamp = desc.timestamp
        if (stamp <= prefs.getLong("clip_seen", 0L)) return
        if (!desc.hasMimeType("text/*")) { prefs.edit().putLong("clip_seen", stamp).apply(); return }
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString() }.getOrNull()
            ?: return // まだ読めない(画面が前面になる前)
        prefs.edit().putLong("clip_seen", stamp).apply()
        val url = Regex("https?://\\S*(youtube\\.com|youtu\\.be|spotify\\.com|spotify\\.link|MusicPlayer-releases/s)\\S*").find(text)?.value ?: return
        clipUrl = url
        btn.text = when {
            Share.isShare(url) -> "📋 コピーした共有リンクを開く\n"
            Spotify.isSpotify(url) -> "📋 コピーした Spotify のリンクを読み込む\n"
            else -> "📋 コピーした URL を追加\n"
        } + url.take(48)
        btn.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) view?.postDelayed({ if (isAdded && !isHidden) checkClipboard() }, 400)
    }

    /** ライブラリ・オンラインにある曲の印を計算し直す */
    private var ownedStale = false
    private fun recomputeOwned(tracks: List<Track> = Repository.tracks.value) {
        inLibrary = VideoAlias.ownedIds(tracks.filterNot { Repository.isOnline(it.id) })
        inOnline = VideoAlias.ownedIds(tracks.filter { Repository.isOnline(it.id) })
        refreshList()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ownedStale) { ownedStale = false; recomputeOwned() }
        if (!hidden) view?.postDelayed({ if (isAdded && !isHidden) checkClipboard() }, 300)
        if (::browseScroll.isInitialized) updateBack()
    }

    /** 他の画面から(再生画面のアーティスト名など) */
    fun searchFor(q: String) {
        if (!::query.isInitialized) { view?.post { searchFor(q) }; return }
        query.setText(q)
        search(q)
    }

    // ───────── 検索 ─────────

    private fun search(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        // 共有リンク / Spotify のリンク: 確認してから追加する画面へ
        if (Share.isShare(text) || Spotify.isSpotify(text)) {
            hideKeyboard()
            query.setText("")
            SpotifyImportActivity.open(requireContext(), if (Share.isShare(text)) text else Spotify.find(text) ?: text)
            return
        }
        // URL を貼ったら、検索ではなく追加画面へ
        if (looksLikeUrl(text)) {
            hideKeyboard()
            query.setText("")
            openLink(text)
            return
        }
        lastQuery = text
        addHistory(text)
        hideSuggestions()
        hideKeyboard()
        searchJob?.cancel()
        moreJob?.cancel()
        topJob?.cancel()
        top = null
        nextToken = null
        loadingMore = false
        loading.visibility = View.VISIBLE
        idle.visibility = View.GONE
        setBrowsing(null)
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            val src = source
            val sec = section
            val order = clientOrder(src, sec)
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val first = Searcher.search(text, src, sec, sort)
                    if (order == null) return@withContext first
                    // 人気順・新しい順: 先に 3 ページ分(約 60 件)集めてから並べ替える
                    val all = first.results.toMutableList()
                    var token = first.token
                    repeat(2) {
                        val t = token ?: return@repeat
                        val more = runCatching { Searcher.more(t, src, sec) }.getOrNull() ?: return@repeat
                        val seen = all.map { x -> x.url }.toHashSet()
                        all += more.results.filter { x -> seen.add(x.url) }
                        token = more.token
                    }
                    first.copy(results = arrange(all, order), token = token)
                }
            }
            if (lastQuery != text) return@launch
            loading.visibility = View.INVISIBLE
            r.onSuccess { page ->
                val it = page.results
                results = it
                nextToken = page.token
                refreshList()
                // 上位の曲は先に再生 URL を取っておく。🔒 の曲は別の音源を先に探しておく
                Searcher.prefetch(it)
                AltSources.lookAhead(it.take(10))
                // アーティスト名で探した時は、その人の人気曲を上に出す(裏で取る)
                // (人気順・新しい順の時は並びが崩れるので出さない)
                if (src == Searcher.Source.MUSIC && sec == Searcher.Section.SONGS && order == null) {
                    topJob = viewLifecycleOwner.lifecycleScope.launch {
                        val t = withContext(Dispatchers.IO) { Searcher.topSongs(text) }
                        if (t != null && lastQuery == text && source == src && section == sec) {
                            top = t
                            refreshList()
                            Searcher.prefetch(t.songs, 3)
                        }
                    }
                }
                if (it.isEmpty()) {
                    idle.visibility = View.VISIBLE
                    hint.text = "「$text」に一致する結果はありませんでした"
                }
            }
            r.onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                results = emptyList()
                refreshList()
                idle.visibility = View.VISIBLE
                hint.text = "検索できませんでした\n${it.message?.lines()?.lastOrNull { l -> l.isNotBlank() } ?: ""}\n\n" +
                    "通信状態を確認するか、ダウンロード画面で yt-dlp を更新してください"
            }
        }
    }

    private fun loadMore() {
        val token = nextToken ?: return
        if (loadingMore) return
        loadingMore = true
        val q = lastQuery
        val src = source
        val sec = section
        val so = sort
        moreJob = viewLifecycleOwner.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) { Searcher.more(token, src, sec) }
            if (lastQuery != q || source != src || section != sec || sort != so) return@launch
            val seen = results.map { it.url }.toHashSet()
            val added = page.results.filter { seen.add(it.url) }
            results = results + arrange(added, clientOrder(src, sec))
            // 新しい曲が無ければそこで終わり(同じ鍵で無限に読まないように)
            nextToken = if (added.isEmpty()) null else page.token
            loadingMore = false
            refreshList()
        }
    }

    private lateinit var browseScroll: View
    private lateinit var browseChips: ChipGroup
    private lateinit var sectionsScroll: View
    /** 「見つける」のどれを開いているか(普通の検索なら null) */
    private var browsing: String? = null

    private val backToIdle = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goIdle()
    }

    private fun updateBack() {
        backToIdle.isEnabled = !isHidden && (browsing != null || results.isNotEmpty() || lastQuery.isNotBlank())
    }

    /** 最初の画面(見つける・最近の検索)に戻る */
    private fun goIdle() {
        searchJob?.cancel(); moreJob?.cancel(); topJob?.cancel()
        query.setText("")
        results = emptyList(); lastQuery = ""; nextToken = null; loadingMore = false
        top = null
        loading.visibility = View.INVISIBLE
        refreshList()
        setBrowsing(null)
        Searcher.status.value = ""
        showIdle()
    }

    /** 「見つける」のバー: ランキングと全ジャンルを横に並べて、今のを選択状態に。左端の ✕ で最初の画面へ */
    private fun setBrowsing(title: String?) {
        val changed = browsing != title
        browsing = title
        browseScroll.visibility = if (title != null) View.VISIBLE else View.GONE
        sectionsScroll.visibility = View.VISIBLE
        // 種類のチップの選択を今の表示に合わせる(ジャンル表示中はどれも選ばない、検索に戻ったら今の種類)
        if (changed) buildChips()
        updateBack()
        if (title == null) return
        val ctx = requireContext()
        browseChips.removeAllViews()
        browseChips.addView(Chip(ctx).apply {
            text = "✕ 閉じる"
            setOnClickListener { goIdle() }
        })
        fun add(label: String, open: () -> Unit) {
            val c = Chip(ctx, null, com.google.android.material.R.attr.chipStyle).apply {
                text = label
                isCheckable = true
                isChecked = label == title
                setOnClickListener { if (label != browsing) open() else isChecked = true }
            }
            browseChips.addView(c)
            if (c.isChecked) browseScroll.post { (browseScroll as? android.widget.HorizontalScrollView)?.smoothScrollTo((c.left - 48).coerceAtLeast(0), 0) }
        }
        add("🏆 ランキング(日本)") { showBrowse("🏆 ランキング(日本)") { InnerTube.charts() } }
        Genres.load(prefs).forEach { g -> add(g.title) { openGenre(g) } }
    }

    private fun showIdle() {
        idle.visibility = View.VISIBLE
        hint.text = "YouTube / YouTube Music の曲を探して\nタップで試聴(YouTube は動画)、⬇ で追加\n長押しでその他のメニュー"
        renderHistory()
    }

    private fun historyList(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("history", "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun addHistory(q: String) {
        val list = (listOf(q) + historyList().filter { it != q }).take(15)
        prefs.edit().putString("history", JSONArray(list).toString()).apply()
    }

    private fun renderHistory() {
        val list = historyList()
        history.removeAllViews()
        view?.findViewById<View>(R.id.historyTitle)?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        list.forEach { q ->
            history.addView(Chip(requireContext()).apply {
                text = q
                isCloseIconVisible = true
                setOnClickListener { query.setText(q); query.setSelection(q.length); search(q) }
                setOnCloseIconClickListener {
                    prefs.edit().putString("history", JSONArray(historyList().filter { it != q }).toString()).apply()
                    renderHistory()
                }
            })
        }
    }

    private fun hideKeyboard() {
        query.clearFocus()
        requireContext().getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(query.windowToken, 0)
    }

    // ───────── 試聴・追加 ─────────

    private fun preview(r: Searcher.Result) {
        if (r.isList) { playList(r); return }
        // 検索リストをそのまま再生キューに(⏭ で検索の次の曲へ)
        val queue = top?.songs?.takeIf { r in it } ?: results.toList()
        // ライブラリにあればそれを再生(通信不要)
        val local = VideoAlias.trackFor(r.videoId)
        if (local != null) { main.player.playSearch(queue, queue.indexOf(r)); return }
        if (preparingId != null) return
        preparingId = r.id
        refreshList()
        val t0 = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            // 最初の 1 曲は先に URL を取って、取れない時はここで知らせる(取った URL は再生時に使い回す)
            val url = runCatching { withContext(Dispatchers.IO) { Searcher.streamUrl(r.url) } }
            preparingId = null
            refreshList()
            url.onSuccess {
                val took = String.format("%.1f秒", (System.currentTimeMillis() - t0) / 1000.0)
                Searcher.status.value = "試聴 $took · ${Searcher.lastStream} · 読み込み中…"
                watchPlayback(r, t0)
                main.player.playSearch(queue, queue.indexOf(r))
            }
            url.onFailure { toast("試聴できませんでした。⬇ で追加すると再生できます") }
        }
    }

    private var watcher: androidx.media3.common.Player.Listener? = null

    /**
     * 試聴の再生を見張る。鳴り始めたら時間を表示、エラーなら内容を表示して
     * yt-dlp で取り直した URL で 1 回だけ再試行する。
     */
    /** 再生開始までの時間を表示(エラー時の取り直しは再生サービスが行う) */
    private fun watchPlayback(r: Searcher.Result, t0: Long) {
        val c = main.player.controller ?: return
        watcher?.let { c.removeListener(it) }
        val expectedId = PlayerConnection.STREAM_PREFIX + (r.videoId ?: r.id)
        val l = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && c.currentMediaItem?.mediaId == expectedId) {
                    val took = String.format("%.1f秒", (System.currentTimeMillis() - t0) / 1000.0)
                    Searcher.status.value = "試聴 $took で再生開始 · ${Searcher.lastStream}"
                    c.removeListener(this); watcher = null
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (c.currentMediaItem?.mediaId != expectedId) return
                val cause = generateSequence(error.cause) { it.cause }.mapNotNull { it.message }.firstOrNull().orEmpty()
                Searcher.status.value = "再生エラー: ${error.errorCodeName} ${cause.take(80)}".trim() + " → yt-dlp で取り直し中…"
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (mediaItem?.mediaId != expectedId) { c.removeListener(this); watcher = null }
            }
        }
        watcher = l
        c.addListener(l)
    }

    /** タップで動画プレビューを開くか(YouTube 検索、または Music の「動画」) */
    private fun opensVideo(r: Searcher.Result) =
        source == Searcher.Source.YOUTUBE || r.kind == Searcher.Kind.VIDEO

    /** アーティストのカード: 丸い写真・名前・登録者数 */
    private fun bindArtist(v: View, r: Searcher.Result) {
        Thumbs.bindUrl(v.findViewById(R.id.art), r.thumb)
        v.findViewById<TextView>(R.id.title).text = r.title
        v.findViewById<TextView>(R.id.subtitle).text = r.artist.split(" • ", " · ").map { it.trim() }
            .filter { it.isNotBlank() && it != "アーティスト" && it != "Artist" && it != r.title }.joinToString(" · ")
        v.setOnClickListener {
            openArtist(r, if (source == Searcher.Source.YOUTUBE) Searcher.Section.VIDEOS else Searcher.Section.SONGS)
        }
        v.setOnLongClickListener { b -> artistMenu(b, r); true }
        v.findViewById<View>(R.id.more).setOnClickListener { b -> artistMenu(b, r) }
    }

    /** アーティスト・チャンネルを開く: その名前で曲(人気曲が上に出る)・アルバム・動画などを探す */
    private fun openArtist(r: Searcher.Result, sec: Searcher.Section) {
        section = sec
        buildChips()
        query.setText(r.title)
        search(r.title)
    }

    private fun artistMenu(anchor: View, r: Searcher.Result) {
        android.widget.PopupMenu(requireContext(), anchor).apply {
            if (source == Searcher.Source.YOUTUBE) {
                menu.add("動画を見る").setOnMenuItemClickListener { openArtist(r, Searcher.Section.VIDEOS); true }
                menu.add("プレイリストを見る").setOnMenuItemClickListener { openArtist(r, Searcher.Section.PLAYLISTS); true }
            } else {
                menu.add("人気曲・曲を見る").setOnMenuItemClickListener { openArtist(r, Searcher.Section.SONGS); true }
                menu.add("アルバムを見る").setOnMenuItemClickListener { openArtist(r, Searcher.Section.ALBUMS); true }
            }
            menu.add(if (source == Searcher.Source.YOUTUBE) "YouTube で開く" else "YouTube Music で開く").setOnMenuItemClickListener {
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(r.url))) }; true
            }
            NoGoodUi.addArtistItem(menu, requireContext(), r.title, r.id)
        }.show()
    }

    private fun rowMenu(anchor: View, r: Searcher.Result) {
        android.widget.PopupMenu(requireContext(), anchor).apply {
            if (r.isList) {
                menu.add("開く(中の曲を見る)").setOnMenuItemClickListener { SmartListActivity.openList(requireContext(), r); true }
                menu.add("再生").setOnMenuItemClickListener { playList(r); true }
            }
            if (!r.isList) {
                menu.add("動画で見る").setOnMenuItemClickListener { openVideo(r); true }
                menu.add("音声で試聴").setOnMenuItemClickListener { preview(r); true }
                r.videoId?.let { vid ->
                    menu.add(if (Liked.contains(vid)) "「お気に入り」から外す" else "♡ お気に入りに追加").setOnMenuItemClickListener {
                        toast(if (Liked.toggle(r)) "「お気に入り」に追加しました" else "「お気に入り」から外しました"); true
                    }
                    menu.add("この曲のラジオ").setOnMenuItemClickListener { Radio.forVideo(main, main.player, vid, r.title, r.artist); true }
                }
            }
            if (!r.isList && r.videoId != null) {
                menu.add("☁ プレイリストに追加…(ダウンロードしない)").setOnMenuItemClickListener {
                    OnlineLibrary.add(r)?.let { t -> main.actions.addToPlaylist(listOf(t.id)) }; true
                }
                menu.add("☁ ライブラリに追加(ダウンロードしない)").setOnMenuItemClickListener {
                    OnlineLibrary.add(r)?.let { toast("☁「${r.title}」を追加しました") }; true
                }
            }
            menu.add(if (r.isList) "まとめて追加…" else "⬇ ダウンロードしてライブラリに追加").setOnMenuItemClickListener {
                if (r.isList) add(r) else {
                    main.ensureNotificationPermission()
                    DownloadManager.add(requireContext(), r.url, wholeList = false, makePlaylist = false)
                    toast("「${r.title}」をダウンロードします")
                }
                true
            }
            if (!r.isList && r.videoId != null && VideoCheck.known(r.videoId) == false)
                menu.add("🎬 動画なし(ジャケットの静止画だけ)").isEnabled = false
            else if (!r.isList && r.videoId != null) menu.add("🎬 動画を保存(ライブラリにも追加)").setOnMenuItemClickListener {
                main.ensureNotificationPermission()
                DownloadManager.addVideoForUrl(requireContext(), "https://www.youtube.com/watch?v=${r.videoId}", r.title)
                toast(if (VideoPrefs.wifiOnly(requireContext()) && Net.metered) "Wi-Fi につながったら動画を保存します" else "「${r.title}」の動画を保存します")
                true
            }
            menu.add("YouTube で開く").setOnMenuItemClickListener {
                runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(r.url))) }; true
            }
            menu.add("URL をコピー").setOnMenuItemClickListener {
                val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText(r.title, r.url)); true
            }
            if (!r.isList) NoGoodUi.addArtistItem(menu, requireContext(), r.artist)
        }.show()
    }

    /** 動画を再生して、再生画面を 🎬 動画で開く(検索結果がそのまま再生キュー。⏭ で次の結果へ) */
    private fun openVideo(r: Searcher.Result) {
        if (preparingId != null) return
        val queue = results.toList()
        preparingId = r.id
        refreshList()
        viewLifecycleOwner.lifecycleScope.launch {
            val res = runCatching { withContext(Dispatchers.IO) { Searcher.videoSources(r.url) } }
            preparingId = null
            refreshList()
            res.onSuccess { s ->
                if (main.player.controller == null) { toast("プレーヤーの準備中です。もう一度タップしてください"); return@onSuccess }
                NowPlayingActivity.setVideoMode(requireContext(), true)
                main.player.playVideo(r, s, queue)
                startActivity(android.content.Intent(requireContext(), NowPlayingActivity::class.java))
            }
            res.onFailure { toast("動画を再生できませんでした: ${it.message?.lines()?.firstOrNull().orEmpty()}") }
        }
    }

    private fun add(r: Searcher.Result) {
        if (r.isList) { addList(r); return }
        val vid = r.videoId
        if (vid != null && vid in inLibrary) { toast("ダウンロード済みです"); return }
        if (vid != null && vid in inOnline) {
            VideoAlias.trackFor(vid, includeMissing = true)?.let { DownloadManager.redownload(requireContext(), listOf(it)) }
            toast("ダウンロードします"); return
        }
        if (vid != null && vid in inQueue) { toast("ダウンロード中です"); return }
        OnlineLibrary.addSong(requireContext(), r) {
            main.ensureNotificationPermission()
            DownloadManager.add(requireContext(), r.url, wholeList = false, makePlaylist = false)
            toast("「${r.title}」をダウンロードします")
        }
    }

    private fun addList(r: Searcher.Result) {
        StreamDownload.addListDialog(requireContext(), r) {
            main.ensureNotificationPermission()
        }
    }

    /** アルバム/プレイリストをタップ: 頭から試聴(⏭ で次の曲) */
    private fun playList(r: Searcher.Result) {
        if (preparingId != null) return
        preparingId = r.id
        refreshList()
        val t0 = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            val tracks = runCatching { withContext(Dispatchers.IO) { Searcher.listTracks(r) } }
            preparingId = null
            refreshList()
            val list = tracks.getOrNull().orEmpty()
            if (list.isEmpty()) {
                toast("曲の一覧を取得できませんでした")
                return@launch
            }
            Searcher.status.value = "${r.title}: ${list.size} 曲 " + String.format("%.1f秒", (System.currentTimeMillis() - t0) / 1000.0)
            Searcher.prefetch(list, 2)
            main.player.playSearch(list, 0, r)
        }
    }

    private fun addAll(r: Searcher.Result, makePlaylist: Boolean) {
        main.ensureNotificationPermission()
        DownloadManager.add(requireContext(), r.url, wholeList = true, makePlaylist = makePlaylist)
        toast("曲の一覧を取得しています…(右上の ⬇ で進み具合)")
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    // ───────── 一覧 ─────────

    /** 一覧の行: 見出し / 曲 / 読み込み中 */
    private sealed class Row {
        data class Header(val text: String) : Row()
        data class Item(val r: Searcher.Result) : Row()
        object Footer : Row()
    }

    private fun rows(): List<Row> = buildList {
        top?.let { t ->
            add(Row.Header("${t.artist} の人気曲"))
            t.songs.forEach { add(Row.Item(it)) }
            if (results.isNotEmpty()) add(Row.Header("検索結果"))
        }
        results.forEach { add(Row.Item(it)) }
        if (results.isNotEmpty() && (nextToken != null || loadingMore)) add(Row.Footer)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        override fun getItemCount(): Int { rows = rows(); return rows.size }

        override fun getItemViewType(position: Int) = when (rows.getOrNull(position)) {
            is Row.Footer -> 1
            is Row.Header -> 2
            is Row.Item -> if ((rows[position] as Row.Item).r.isArtist) 3 else 0
            else -> 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 2) {
                val d = resources.displayMetrics.density
                val tv = TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(parent, androidx.appcompat.R.attr.colorPrimary))
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            if (viewType == 1) {
                val box = android.widget.FrameLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * resources.displayMetrics.density).toInt())
                    addView(CircularProgressIndicator(parent.context).apply { isIndeterminate = true },
                        android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
                }
                return object : RecyclerView.ViewHolder(box) {}
            }
            if (viewType == 3) {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_artist, parent, false)
                // 画像は列の幅に合わせた正方形(丸く切り抜く)
                val d = resources.displayMetrics.density
                val w = (if (parent.width > 0) parent.width else resources.displayMetrics.widthPixels) / 3 - (16 * d).toInt()
                v.findViewById<View>(R.id.art).layoutParams.height = w.coerceAtLeast((64 * d).toInt())
                return object : RecyclerView.ViewHolder(v) {}
            }
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_search, parent, false)) {}
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val row = rows.getOrNull(position) ?: return
            if (row is Row.Header) { (h.itemView as TextView).text = row.text; return }
            if (row is Row.Footer) {
                // 一番下が見えたら続きを読む(画面に収まるほど少ない時のため)
                h.itemView.post { loadMore() }
                return
            }
            val r = (row as Row.Item).r
            val v = h.itemView
            if (r.isArtist) { bindArtist(v, r); return }
            Thumbs.bindUrl(v.findViewById(R.id.art), r.thumb)
            v.findViewById<TextView>(R.id.title).text = r.title
            val lock = AltSources.label(r)
            v.alpha = lock?.second ?: 1f
            v.findViewById<TextView>(R.id.subtitle).text = buildList {
                lock?.let { add(it.first) }
                when (r.kind) {
                    Searcher.Kind.ALBUM -> add("アルバム")
                    Searcher.Kind.PLAYLIST -> add("プレイリスト")
                    Searcher.Kind.VIDEO -> if (source == Searcher.Source.MUSIC) add("動画")
                    else -> {}
                }
                // 「プレイリスト · プレイリスト · YouTube Music」のような重なりを除く
                r.artist.split(" • ", " · ").map { it.trim() }
                    .filter { it.isNotBlank() && it !in this && it != "曲" && it != "Playlist" && it != "Album" }
                    .forEach { add(it) }
                if (r.durationSec > 0) add(formatTime(r.durationSec * 1000))
                if (r.views > 0) add(formatViews(r.views))
            }.distinct().joinToString(" · ")

            val vid = r.videoId
            val state = v.findViewById<TextView>(R.id.state)
            val add = v.findViewById<ImageButton>(R.id.add)
            when {
                vid != null && vid in inLibrary -> {
                    state.visibility = View.VISIBLE; state.text = "✓ ライブラリ"
                    add.setImageResource(R.drawable.ic_check); add.alpha = 0.5f
                }
                vid != null && vid in inOnline -> {
                    state.visibility = View.VISIBLE
                    state.text = if (StreamCache.isCached(requireContext(), vid)) "☁✓ ライブラリ(一時保存済み)" else "☁ ライブラリ"
                    add.setImageResource(R.drawable.ic_download); add.alpha = 1f
                }
                vid != null && vid in inQueue -> {
                    state.visibility = View.VISIBLE; state.text = "ダウンロード中…"
                    add.setImageResource(R.drawable.ic_download); add.alpha = 0.5f
                }
                else -> {
                    state.visibility = View.GONE
                    add.setImageResource(if (r.isList) R.drawable.ic_playlist_add else R.drawable.ic_download)
                    add.alpha = 1f
                }
            }
            add.contentDescription = when {
                r.isList -> "まとめて追加"
                vid != null && vid in inOnline -> "ダウンロード"
                else -> "ライブラリに追加"
            }
            add.setOnClickListener { add(r) }

            val preparing = preparingId == r.id
            v.findViewById<CircularProgressIndicator>(R.id.playLoading).visibility = if (preparing) View.VISIBLE else View.GONE
            // 試聴中の曲は曲名をアクセント色に
            val title = v.findViewById<TextView>(R.id.title)
            if (title.tag == null) title.tag = title.textColors
            val playingThis = vid != null && main.nowPlayingStreamId == vid
            if (playingThis) title.setTextColor(com.google.android.material.color.MaterialColors.getColor(title, androidx.appcompat.R.attr.colorPrimary))
            else title.setTextColor(title.tag as android.content.res.ColorStateList)

            // タップ: YouTube(や Music の「動画」)は動画プレビュー、YouTube Music は音声の試聴
            v.setOnClickListener {
                when {
                    // アルバム・プレイリストは開いて中の曲を見る(再生は開いた画面の ▶ か、⋮ の「再生」)
                    r.isList -> SmartListActivity.openList(requireContext(), r)
                    opensVideo(r) -> openVideo(r)
                    else -> preview(r)
                }
            }
            // 長押し: もう一方の見方など
            v.setOnLongClickListener { b -> rowMenu(b, r); true }
            v.findViewById<View>(R.id.more).setOnClickListener { b -> rowMenu(b, r) }
        }
    }
}

/** 再生回数を短く(1.2億回 / 345万回 / 8,900回) */
fun formatViews(n: Long): String = when {
    n >= 100_000_000 -> String.format("%.1f億回", n / 1e8).replace(".0億", "億")
    n >= 100_000 -> "${n / 10_000}万回"
    n >= 10_000 -> String.format("%.1f万回", n / 1e4).replace(".0万", "万")
    else -> String.format("%,d回", n)
}

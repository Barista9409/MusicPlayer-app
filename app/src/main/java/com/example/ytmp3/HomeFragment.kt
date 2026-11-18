package com.example.ytmp3

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ホーム: クイックアクセス(ピン留め + よく聴くもの 8 個)、最近再生したもの、よく聴く曲、最近追加した曲、
 * ライブラリへの入口(アルバム・アーティスト・曲・フォルダ)
 */
class HomeFragment : Fragment(R.layout.fragment_home), androidx.media3.common.Player.Listener {
    private val main get() = requireActivity() as MainActivity
    private lateinit var content: LinearLayout
    private var controller: androidx.media3.session.MediaController? = null
    /** 「前回の続き」カード(再生状態が変わったら作り直す) */
    private var resumeSlot: LinearLayout? = null
    /** 復元後の曲の取り直しカード */
    private var restoreSlot: LinearLayout? = null

    private fun renderRestore(jobs: List<DownloadJob>) {
        val slot = restoreSlot ?: return
        slot.removeAllViews()
        val (done, total) = DownloadManager.restoreProgress(jobs) ?: return
        val d = resources.displayMetrics.density
        val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), 0)
            }
            radius = 16 * d
            setOnClickListener { DownloadsSheet.show(main) }
        }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        box.addView(TextView(requireContext()).apply {
            text = "復元: 曲をダウンロード中  $done / $total"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        })
        box.addView(com.google.android.material.progressindicator.LinearProgressIndicator(requireContext()).apply {
            max = total; setProgressCompat(done, false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (8 * d).toInt() }
        })
        box.addView(TextView(requireContext()).apply {
            text = "終わった曲から聴けます。タップで詳しい進み具合"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            alpha = 0.7f
        })
        card.addView(box)
        slot.addView(card)
    }
    private var topExpanded = false

    /** 画面に出す 1 つ分(プレイリスト・アルバム・アーティスト・曲・試聴) */
    private data class Item(
        val ref: PlayLog.Ref,
        val title: String,
        val sub: String,
        val localThumb: String?,
        val webThumb: String?,
        val tracks: List<Track>,
        /** あなたのミックスのカードなら、そのミックス */
        val mix: DailyMix.Mix? = null,
    )

    // ホームが見えるようになった時(アプリに戻った・ホームのタブに切り替えた):
    // あなたのミックスの、出していなかったジャケットを出し、次に押した時の中身を作り置きする
    override fun onResume() {
        super.onResume()
        if (!isHidden) homeShown()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isResumed) homeShown()
    }

    /**
     * ホームが見えた時: 見えない間に変わっていれば作り直す。
     * 再生中の画面を閉じる動きなどと重ならないよう少し待ってから(最初の 1 回だけはすぐ)
     */
    private fun homeShown() {
        val first = content.childCount == 0
        if (dirty || first) {
            dirty = false
            content.removeCallbacks(deferredRender)
            if (first) render() else content.postDelayed(deferredRender, 350)
        }
        content.postDelayed({
            if (isResumed && !isHidden) runCatching { DailyMix.onHomeShown(lastMixes ?: DailyMix.mixes()) }
        }, 400)
    }

    /** 見えない間(再生中の画面の後ろ・ほかのタブ)の変化は印だけ付けて、見えた時に 1 回作り直す */
    private var dirty = false
    private val deferredRender = Runnable { if (isAdded && isResumed && !isHidden) render() else dirty = true }
    private var lastMixes: List<DailyMix.Mix>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        content = view.findViewById(R.id.content)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        combine(Repository.tracks, Repository.playlists, Repository.missing) { _, _, _ -> },
                        combine(PlayLog.changed, NewReleases.list, NewReleases.related) { _, _, _ -> },
                        combine(DailyDiscover.list, Liked.list, DailyDiscover.loading, NewReleases.loading) { _, _, _, _ -> },
                        combine(NoGood.songs, NoGood.artists) { _, _ -> },
                        // あなたのミックス: 曲調の分析が進んだ時・分析中の表示
                        combine(MoodMix.changed, MixPrefs.changed, DailyMix.lastArt, ArtistGenres.changed) { _, _, _, _ -> },
                    ) { _, _, _, _, _ -> }
                        // 短い間に続けて変わっても、作り直しは 1 回に(カクつき・ちらつきを防ぐ)
                        .conflate()
                        .collect {
                            // 裏の変化(分析の進み・ジャケットの用意など)による作り直しは 2 秒に 1 回まで
                            val wait = 2000 - (android.os.SystemClock.uptimeMillis() - lastRender)
                            if (wait > 0) kotlinx.coroutines.delay(wait)
                            if (!isResumed || isHidden) { dirty = true; return@collect }
                            // 指で触っている間・離した直後は作り直さない(押そうとしたカードが消えてタップが取り消されないように)
                            waitUntilIdle()
                            if (!isResumed || isHidden) { dirty = true; return@collect }
                            // ミックスの分け方の計算は画面の処理とは別の所で先に(作り直しの間に画面が固まらないように)
                            precomputedMixes = withContext(Dispatchers.Default) { runCatching { DailyMix.mixes() }.getOrNull() }
                            waitUntilIdle()
                            if (!isResumed || isHidden) { dirty = true; return@collect }
                            render()
                            lastRender = android.os.SystemClock.uptimeMillis()
                        }
                }
                // 曲調の分析の進み具合は、見出しの文字だけ書き換える(ホーム全体は作り直さない)
                launch { MoodMix.progress.collect { content.findViewWithTag<TextView>(MIX_NOTE)?.text = mixNote() } }
                launch { ArtistGenres.progress.collect { content.findViewWithTag<TextView>(MIX_NOTE)?.text = mixNote() } }
                launch { DownloadManager.jobs.collect { renderRestore(it) } }
                launch {
                    main.controllerFlow.collect { c ->
                        controller?.removeListener(this@HomeFragment)
                        controller = c
                        c?.addListener(this@HomeFragment)
                        renderResume()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        content.removeCallbacks(deferredRender)
        controller?.removeListener(this)
        controller = null
        super.onDestroyView()
    }

    override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
        if (events.containsAny(
                androidx.media3.common.Player.EVENT_IS_PLAYING_CHANGED,
                androidx.media3.common.Player.EVENT_PLAY_WHEN_READY_CHANGED,
                androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION,
                androidx.media3.common.Player.EVENT_TIMELINE_CHANGED,
            )
        ) renderResume()
    }

    /** ① 前回の続き: キューが残っていて、今は止まっている時だけ */
    private val showResume = Runnable { renderResume(confirmed = true) }

    /**
     * 再生しよう・読み込もうとしている時は出さない。止まってから 0.5 秒たっても止まったままの時だけ出す
     * (ミックスなどを押した時、曲を差し替える一瞬だけ「止まっている」状態をはさむので、その時にちらつかないように)
     */
    private fun renderResume(confirmed: Boolean = false) {
        val slot = resumeSlot ?: return
        // 「前回の続き」は下のミニプレーヤーに出す(ホームの上に出したり消したりすると、ホームが上下に動いて見づらいため)
        if (SHOW_RESUME_CARD.not()) { slot.removeAllViews(); return }
        val c = controller
        val stopped = c != null && c.currentMediaItem != null && !c.isPlaying && !c.playWhenReady
        slot.removeCallbacks(showResume)
        if (!stopped) { slot.removeAllViews(); return }
        // すでに出ているなら、そのまま中身だけ新しく
        if (!confirmed && slot.childCount == 0) { slot.postDelayed(showResume, 500); return }
        slot.removeAllViews()
        c!!
        val item = c.currentMediaItem ?: return
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_home_resume, slot, false)
        Thumbs.bindItem(v.findViewById(R.id.art), item.mediaId, item.mediaMetadata.artworkUri)
        v.findViewById<TextView>(R.id.title).text = item.mediaMetadata.title ?: ""
        val pos = c.currentPosition.coerceAtLeast(0)
        val dur = c.duration
        v.findViewById<TextView>(R.id.subtitle).text = listOfNotNull(
            item.mediaMetadata.artist?.toString(),
            if (dur > 0) "${formatTime(pos)} / ${formatTime(dur)}" else null,
        ).joinToString(" · ")
        v.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress)
            .setProgressCompat(if (dur > 0) (pos * 1000 / dur).toInt() else 0, false)
        val resume = View.OnClickListener {
            if (c.playbackState == androidx.media3.common.Player.STATE_IDLE) c.prepare()
            if (c.playbackState == androidx.media3.common.Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
        v.setOnClickListener(resume)
        v.findViewById<View>(R.id.play).setOnClickListener(resume)
        slot.addView(v)
    }

    private val dp get() = resources.displayMetrics.density

    // ───────── 中身を作る ─────────

    /** 横スクロールの位置(ホームを描き直しても戻らないように) */
    private val hScroll = HashMap<String, Int>()
    private var carouselNo = 0

    /** ホームを作り直す(かかった時間を測り、最初の 1 回は起動の速さとして記録) */
    private fun render() {
        val t = android.os.SystemClock.uptimeMillis()
        renderNow()
        val ms = android.os.SystemClock.uptimeMillis() - t
        if (!firstRendered) {
            firstRendered = true
            // 描けた次の画面更新の後 = 操作できるようになった時
            content.post { StartupTiming.homeReady(ms) }
        } else StartupTiming.slowRender(ms)
    }
    private var firstRendered = false

    private fun renderNow() {
        carouselNo = 0
        if (!isAdded) return
        content.removeAllViews()
        val all = Repository.tracks.value
        val playable = all.filterNot { Repository.cantPlay(it.id) }

        // 復元の取り直しの進み具合
        restoreSlot = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        content.addView(restoreSlot)
        renderRestore(DownloadManager.jobs.value)

        // ① 前回の続き
        resumeSlot = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        content.addView(resumeSlot)
        renderResume()

        // 欄は「ホームを編集」で決めた順に(隠した欄は出さない)
        val sections = linkedMapOf<String, () -> Unit>(
            "buttons" to {
            // ① すぐ再生
            content.addView(quickButtons(playable))
            },
            "quick" to {
            // ② クイックアクセス
            val quick = quickAccess(playable)
            if (quick.isNotEmpty()) {
                header("クイックアクセス", "長押しでピン留め")
                // 3 行 × 横に並べて、画面には 2 列分。横にスクロールで最大 12 個
                // 1 画面にちょうど 2 列(3 列目がはみ出して見えないように)。スクロールは 2 列ずつぴたっと止まる
                val sidePad = (12 * dp).toInt()
                // 1 ページ = 画面の幅ちょうど。左の列は左に、右の列は右に余白を持たせるので、次のページの列ははみ出さない
                // 表示の解像度(今の画面の横幅)から計算。画面の回転や分割表示でも、その時の横幅で
                val screenW = if (android.os.Build.VERSION.SDK_INT >= 30)
                    requireActivity().windowManager.currentWindowMetrics.bounds.width()
                else resources.displayMetrics.widthPixels
                val colW = screenW / 2
                val page = screenW
                val scroll = HorizontalScrollView(requireContext()).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    setOnScrollChangeListener { _, x, _, _, _ -> hScroll["quickgrid"] = x }
                    // 止まっている位置(ページの左端)。タイルの上から指を動かした時は DOWN が来ないので、こちらを基準にする
                    var settledX = hScroll["quickgrid"] ?: 0
                    setOnTouchListener { v, e ->
                        val sv = v as HorizontalScrollView
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                            // 少しでも動かした方向の次のページへ
                            val cur = (settledX + page / 2) / page
                            val target = when {
                                sv.scrollX > settledX + page / 6 -> cur + 1
                                sv.scrollX < settledX - page / 6 -> cur - 1
                                else -> cur
                            }
                            val max = (sv.getChildAt(0).width - sv.width).coerceAtLeast(0)
                            settledX = (target * page).coerceIn(0, max)
                            sv.post { sv.smoothScrollTo(settledX, 0) }
                            return@setOnTouchListener true
                        }
                        false
                    }
                }
                val cols = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, 0)
                }
                quick.chunked(3).forEachIndexed { ci, col ->
                    val left = ci % 2 == 0
                    val column = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        // 左の列は画面の左端側、右の列は右端側に余白
                        setPadding(if (left) sidePad else 0, 0, if (left) 0 else sidePad, 0)
                    }
                    col.forEach { item ->
                        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
                        row.addView(tile(row, item))
                        column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    cols.addView(column, LinearLayout.LayoutParams(colW, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                // 列が奇数の時は、最後のページの右半分を空けて 2 列ぶんの幅に
                if ((quick.size + 2) / 3 % 2 == 1) cols.addView(View(requireContext()), LinearLayout.LayoutParams(colW, 1))
                scroll.addView(cols)
                hScroll["quickgrid"]?.let { x -> scroll.post { scroll.scrollTo(x, 0) } }
                content.addView(scroll)
            }
            },
            "recent" to {
            // ③ 最近再生したもの
            val recent = PlayLog.recent().mapNotNull { resolve(it, playable) }.take(12)
            if (recent.isNotEmpty()) {
                header("最近再生したもの")
                content.addView(carousel(recent.map { item -> item to { play(item) } }) { item, v -> menu(v, item) })
            }
            },
            "discover" to {
            // ディスカバー: ライブラリに無い、好みに近い曲(1 日 1 回入れ替え)
            DailyDiscover.refresh(requireContext())
            // 前に選んだ分でも、ライブラリに入れた曲・No Good は表示しない
            val disc = DailyDiscover.list.value.filter { r ->
                !LibraryMatch.hasSong(r.title, r.artist) && !NoGood.isSong(r.videoId) && !NoGood.isArtist(Radio.mainArtist(r.artist)) &&
                    r.videoId?.let { VideoAlias.trackFor(it, includeMissing = true) } == null
            }
            if (disc.isNotEmpty()) {
                header("おすすめの曲", "好きな曲に似た、まだ持っていない曲", action = "▶ 全部試聴" to { main.player.playSearch(disc, 0) },
                    open = { SmartListActivity.open(requireContext(), SmartListActivity.DISCOVER) },
                    loading = DailyDiscover.loading.value,
                    refresh = {
                        hScroll.remove("discover") // 入れ替えたら左端から
                        DailyDiscover.refresh(requireContext(), force = true)
                        render()
                    })
                content.addView(songCarousel(disc, scrollKey = "discover", noGood = true))
            }
            },
            "new" to {
            // 新作: よく聴くアーティストの最近のアルバム・シングル(ライブラリに無いもの)
            NewReleases.refresh(requireContext())
            val news = NewReleases.list.value
            if (news.isNotEmpty()) {
                header("ニューリリース", "よく聴くアーティストから", open = { SmartListActivity.open(requireContext(), SmartListActivity.NEW) },
                    loading = NewReleases.loading.value, refresh = {
                    NewReleases.refresh(requireContext(), force = true)
                    render()
                })
                val cards = news.map { nr ->
                    Item(PlayLog.streamRef(nr.result), nr.result.title,
                        listOf(nr.result.artist, if (nr.year > 0) "${nr.year}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                        null, nr.result.thumb, emptyList())
                }
                content.addView(carousel(cards.map { item -> item to { play(item) } }) { item, v -> menu(v, item) })
            }
            },
            "mix" to {
            // あなたのミックス: よく聴くアーティストのまとまりごとに、新曲を頭に(押すたびに作り直す)
            // 曲調の分析がまだの曲があれば、裏で進める(終われば描き直される)
            if (MixPrefs.byMood()) MoodMix.kick()
            val mixes = precomputedMixes ?: DailyMix.mixes()
            precomputedMixes = null
            lastMixes = mixes
            // 3 つのミックスの新曲を裏で先に探しておく(押した時にすぐ作れるように)
            DailyMix.prewarm()
            if (mixes.isNotEmpty()) {
                // ↻ はすぐ終わる。曲調の分析は裏で続くので、くるくるではなく進み具合を小さく出す
                header("あなたのミックス", mixNote(), noteTag = MIX_NOTE, refresh = {
                    DailyMix.refresh()
                    precomputedMixes = null // 作り直した後の分け方で
                    android.widget.Toast.makeText(requireContext(), "今の聴き方でミックスを作り直しました", android.widget.Toast.LENGTH_SHORT).show()
                    render()
                })
                val cards = mixes.map { m ->
                    Item(PlayLog.Ref(PlayLog.Kind.STREAM, "MIX${m.index}", m.title), m.title,
                        DailyMix.label(m), m.art, null, m.covers, mix = m) to { DailyMix.play(main, main.player, m) }
                }
                // 作り置きが無い・まとまりが変わったミックスは、すぐ作り置きを始める(↻ や分け直しの後も)
                DailyMix.ensurePrepared(mixes)
                // メニューは無いので「…」は出さない
                content.addView(carousel(cards, showMenu = false) { _, _ -> })
            }
            },
            "liked" to {
            // 気になる(試聴中に ♡ した曲)
            val liked = Liked.list.value
            if (liked.isNotEmpty()) {
                header("お気に入り", "${liked.size} 曲", action = "▶ 再生" to { main.player.playSearch(liked, 0) },
                    open = { SmartListActivity.open(requireContext(), SmartListActivity.LIKED) })
                content.addView(songCarousel(liked, removable = true))
            }
            },
            "related" to {
            // 似ているアーティスト(タップでその人の人気曲を試聴)
            // ライブラリにいるアーティスト・No Good は表示しない
            val rel = NewReleases.related.value.filter { !LibraryMatch.hasArtist(it.name) && !NoGood.isArtist(it.id) && !NoGood.isArtist(it.name) }
            if (rel.isNotEmpty()) {
                header("おすすめのアーティスト", "よく聴く人のファンが聴いている人", loading = NewReleases.loading.value, refresh = {
                    hScroll.remove("related") // 入れ替えたら左端から
                    NewReleases.refreshRelated(requireContext())
                    render()
                })
                val items = rel.map { a -> Item(PlayLog.artistRef(a.name), a.name, "", null, a.thumb, emptyList()) }
                // アーティストは丸いアイコン(曲の四角いジャケットと見分けやすく)
                content.addView(carousel(items.mapIndexed { i, item -> item to { playArtistTop(rel[i].name) } }, round = true, scrollKey = "related") { item, v ->
                    PopupMenu(requireContext(), v).apply {
                        rel.firstOrNull { it.name == item.title }?.let { a ->
                            menu.add("No Good(今後おすすめしない)").setOnMenuItemClickListener {
                                NoGood.addArtist(a.id, a.name)
                                NewReleases.dropArtist(a.id)
                                Toast.makeText(requireContext(), "「${a.name}」をおすすめに出さないようにしました(設定で解除できます)", Toast.LENGTH_SHORT).show()
                                true
                            }
                        }
                        menu.add("人気曲を試聴").setOnMenuItemClickListener { playArtistTop(item.title); true }
                        menu.add("このアーティストのラジオ").setOnMenuItemClickListener { Radio.forArtist(main, main.player, item.title); true }
                        menu.add("「検索」で見る").setOnMenuItemClickListener {
                            main.selectTab(MainActivity.TAB_SEARCH)
                            (requireActivity().supportFragmentManager.findFragmentByTag("search") as? SearchFragment)?.searchFor(item.title)
                            true
                        }
                    }.show()
                })
            }
            },
            "top" to {
            // ④ よく聴く曲
            val counts = PlayLog.counts()
            val top = counts.entries.sortedByDescending { it.value }
                .mapNotNull { e -> Repository.track(e.key)?.takeIf { !Repository.cantPlay(it.id) }?.let { it to e.value } }
            if (top.isNotEmpty()) {
                header("よく聴く曲", "最近 30 日", action = "▶ 再生" to { main.player.play(top.map { it.first }, 0) },
                    open = { SmartListActivity.open(requireContext(), SmartListActivity.TOP) })
                val shown = if (topExpanded) top.take(20) else top.take(5)
                val queue = top.map { it.first }
                shown.forEachIndexed { i, (t, n) -> content.addView(songRow(i, t, n, queue)) }
                if (top.size > 5) {
                    content.addView(textButton(if (topExpanded) "閉じる" else "もっと見る") {
                        topExpanded = !topExpanded; render()
                    })
                }
            }
            },
            "added" to {
            // ⑤ 最近追加した曲
            val added = playable.sortedByDescending { it.savedAt }.take(15)
            if (added.isNotEmpty()) {
                header("最近追加した曲", open = { SmartListActivity.open(requireContext(), SmartListActivity.ADDED) })
                val items = added.map { t ->
                    Item(PlayLog.Ref(PlayLog.Kind.TRACK, t.id.toString(), t.title), t.title, t.artist, t.thumbPath, null, listOf(t))
                }
                content.addView(carousel(items.mapIndexed { i, item -> item to { main.player.play(added, i) } }) { item, v -> menu(v, item) })
            }
            },
            "stats" to {
            // 聴いた記録のまとめ
            // 今月の記録(タップで詳しく)
            if (PlayLog.playsSince(0).isNotEmpty()) {
                val s = ListeningStats.summarize(ListeningStats.daysFrom(30), 30)
                val h = s.totalMs / 3600_000; val m = (s.totalMs / 60_000) % 60
                val d = resources.displayMetrics.density
                val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                    radius = 16 * d
                    strokeWidth = 0
                    setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer))
                    setOnClickListener { ListeningStats.show(requireActivity()) }
                }
                val on = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnPrimaryContainer)
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((16 * d).toInt(), (14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt())
                }
                val col = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(requireContext()).apply {
                    text = "最近の記録(30日)"; setTextColor(on); alpha = 0.8f
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium); setTextColor(on)
                })
                col.addView(TextView(requireContext()).apply {
                    text = if (s.plays == 0) "まだ記録がありません" else (if (s.estimated) "約" else "") + (if (h > 0) "${h}時間${m}分" else "${m}分") + " · ${s.plays}回"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge); setTextColor(on)
                })
                // ひとこと(日替わり)。連続記録が途切れそうな日はそれを優先
                val line = when {
                    s.streak >= 2 && !s.playedToday -> "🔥 ${s.streak} 日連続中 · 今日聴けば ${s.streak + 1} 日に"
                    s.highlights.isNotEmpty() -> s.highlights[(ListenTime.dayOf(System.currentTimeMillis()) % s.highlights.size).toInt()]
                    else -> s.topSongs.firstOrNull()?.let { (t, n) -> "いちばん聴いた曲: ${t.title}(${n}回)" }
                }
                if (s.type != null) col.addView(TextView(requireContext()).apply {
                    text = "あなたは ${s.type.first}"; maxLines = 1
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge); setTextColor(on)
                })
                line?.let { l ->
                    col.addView(TextView(requireContext()).apply {
                        text = l; maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall); setTextColor(on)
                    })
                }
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_chevron_right); imageTintList = android.content.res.ColorStateList.valueOf(on)
                })
                card.addView(row)
                content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins((16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt(), 0)
                })
            }
            },
            "library" to {
            // ⑥ ライブラリ
            header("ライブラリ", "${all.size} 曲")
            content.addView(libraryButtons())
            },
        )
        val prefs = requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val hiddenSecs = HomeSections.hidden(prefs)
        HomeSections.order(prefs).filter { it !in hiddenSecs }.forEach { sections[it]?.invoke() }
        // ホームを編集
        content.addView(textButton("✏ ホームを編集") { editHome() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)
        })


        if (all.isEmpty()) {
            content.addView(TextView(requireContext()).apply {
                text = "まだ曲がありません\n「検索」で曲を探すか、ライブラリを下に引っ張って Music フォルダを読み込んでください"
                setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), 0)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            })
        }
    }

    // ───────── クイックアクセス ─────────

    private fun quickAccess(playable: List<Track>): List<Item> {
        val out = LinkedHashMap<String, Item>()
        fun addRef(r: PlayLog.Ref?) {
            if (out.size >= 12 || r == null || r.id in out) return
            resolve(r, playable)?.let { out[r.id] = it }
        }
        // ピン留め → 今日(24 時間)よく聴いたもの → 最近聴いた順 → プレイリスト → 最近追加したアルバム
        PlayLog.pins().forEach { addRef(it) }
        PlayLog.groupScores(1).entries.sortedByDescending { it.value }.forEach { addRef(refFromId(it.key, playable)) }
        PlayLog.recent().filter { it.kind != PlayLog.Kind.STREAM && it.kind != PlayLog.Kind.TRACK }.forEach { addRef(it) }
        Repository.playlists.value.forEach { p -> if (Repository.tracksOf(p).isNotEmpty()) addRef(PlayLog.playlistRef(p)) }
        playable.sortedByDescending { it.savedAt }.filter { it.album.isNotBlank() }.forEach { addRef(PlayLog.albumRef(it)) }
        return out.values.toList()
    }

    private fun refFromId(id: String, playable: List<Track>): PlayLog.Ref? {
        val kind = id.substringBefore(":")
        val key = id.substringAfter(":")
        return when (kind) {
            "PLAYLIST" -> key.toLongOrNull()?.let { Repository.playlist(it) }?.let { PlayLog.playlistRef(it) }
            "ALBUM" -> playable.firstOrNull { it.album.lowercase() == key }?.let { PlayLog.albumRef(it) }
            "ARTIST" -> PlayLog.artistRef(key)
            else -> null
        }
    }

    /** 記録を今のライブラリと照らし合わせて表示用に(消えたものは null) */
    private fun resolve(r: PlayLog.Ref, playable: List<Track>): Item? {
        fun thumbOf(ts: List<Track>) = ts.firstOrNull { it.thumbPath != null }?.thumbPath
        return when (r.kind) {
            PlayLog.Kind.PLAYLIST -> {
                val p = r.key.toLongOrNull()?.let { Repository.playlist(it) } ?: return null
                val ts = Repository.tracksOf(p).filterNot { Repository.cantPlay(it.id) }
                Item(r, p.name, "プレイリスト · ${ts.size} 曲", thumbOf(ts), null, ts)
            }
            PlayLog.Kind.ALBUM -> {
                val ts = playable.filter { it.album.lowercase() == r.key }.sortedWith(albumOrderComparator)
                if (ts.isEmpty()) return null
                Item(r, ts[0].album, listOf("アルバム", ts[0].artist).filter { it.isNotBlank() }.joinToString(" · "), thumbOf(ts), null, ts)
            }
            PlayLog.Kind.ARTIST -> {
                val ts = playable.filter { it.artistOrUnknown == r.key }.sortedWith(albumOrderComparator)
                if (ts.isEmpty()) return null
                Item(r, r.key, "アーティスト · ${ts.size} 曲", thumbOf(ts), null, ts)
            }
            PlayLog.Kind.TRACK -> {
                val t = r.key.toLongOrNull()?.let { Repository.track(it) }?.takeIf { !Repository.cantPlay(it.id) } ?: return null
                Item(r, t.title, listOf("曲", t.artist).filter { it.isNotBlank() }.joinToString(" · "), t.thumbPath, null, listOf(t))
            }
            PlayLog.Kind.STREAM -> Item(r, r.title, r.subtitle, null, r.thumb, emptyList())
        }
    }

    // ───────── 再生・メニュー ─────────

    private fun play(item: Item, shuffle: Boolean = false) {
        when (item.ref.kind) {
            PlayLog.Kind.STREAM -> playStream(item.ref)
            PlayLog.Kind.ARTIST -> main.player.play(item.tracks, 0, shuffle = true, ref = item.ref)
            PlayLog.Kind.TRACK -> main.player.play(item.tracks, 0)
            else -> main.player.play(item.tracks, 0, shuffle = shuffle, ref = item.ref)
        }
    }

    /** 試聴の記録: アルバム/プレイリストなら曲を取り直して頭から、曲ならその曲を */
    private fun playStream(ref: PlayLog.Ref) {
        val r = PlayLog.resultOf(ref) ?: return
        if (!r.isList) { main.player.playSearch(listOf(r), 0); return }
        val isRadio = r.id.startsWith("RDAMVM")
        Toast.makeText(requireContext(), "「${r.title}」を読み込み中…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val tracks = runCatching { withContext(Dispatchers.IO) { Searcher.listTracks(r) } }.getOrNull().orEmpty()
            if (!isAdded) return@launch
            if (tracks.isEmpty()) { Toast.makeText(requireContext(), "曲の一覧を取得できませんでした", Toast.LENGTH_SHORT).show(); return@launch }
            Searcher.prefetch(tracks, 2)
            if (isRadio) main.player.playSearch(tracks, 0, ref = ref) else main.player.playSearch(tracks, 0, r)
        }
    }

    private fun menu(anchor: View, item: Item) {
        val r = item.ref
        PopupMenu(requireContext(), anchor).apply {
            when (r.kind) {
                PlayLog.Kind.PLAYLIST -> menu.add("開く").setOnMenuItemClickListener {
                    startActivity(Intent(requireContext(), PlaylistDetailActivity::class.java)
                        .putExtra(PlaylistDetailActivity.EXTRA_ID, r.key.toLong()))
                    true
                }
                PlayLog.Kind.ALBUM -> menu.add("開く").setOnMenuItemClickListener { main.openLibraryAlbum(r.key, item.title); true }
                PlayLog.Kind.ARTIST -> menu.add("開く").setOnMenuItemClickListener { main.openLibraryArtist(r.key); true }
                else -> {}
            }
            when (r.kind) {
                PlayLog.Kind.TRACK -> item.tracks.firstOrNull()?.let { t ->
                    menu.add("この曲のラジオ").setOnMenuItemClickListener { Radio.forTrack(main, main.player, t); true }
                }
                PlayLog.Kind.ARTIST -> menu.add("このアーティストのラジオ").setOnMenuItemClickListener {
                    Radio.forArtist(main, main.player, r.key); true
                }
                else -> {}
            }
            if (item.tracks.size > 1) {
                menu.add("シャッフル再生").setOnMenuItemClickListener {
                    main.player.play(item.tracks, 0, shuffle = true, ref = r); true
                }
                menu.add("キューに追加").setOnMenuItemClickListener { main.player.addToQueue(item.tracks); true }
            }
            if (r.kind == PlayLog.Kind.STREAM) PlayLog.resultOf(r)?.takeIf { it.isList && !it.id.startsWith("RDAMVM") }?.let { list ->
                menu.add("まとめてライブラリに追加…").setOnMenuItemClickListener {
                    StreamDownload.addListDialog(requireContext(), list) { main.ensureNotificationPermission() }; true
                }
            }
            if (r.kind != PlayLog.Kind.TRACK) {
                menu.add(if (PlayLog.isPinned(r)) "ピン留めを外す" else "ピン留め").setOnMenuItemClickListener {
                    PlayLog.togglePin(r); true
                }
            }
        }.show()
    }

    // ───────── 部品 ─────────

    /** 欄の見出し: 1 行目に「題名 ⟳」、その下に小さく説明。右端に「全部」など */
    /** あなたのミックスの見出しの説明(分析中は進み具合) */
    private fun mixNote(): String {
        if (MixPrefs.byGenre()) {
            val (gd, gt) = ArtistGenres.progress.value
            val tail = if (MixPrefs.moreNew()) "新曲をたくさん。押すたびに入れ替わります" else "新曲と聴き慣れた曲をほどよく。押すたびに入れ替わります"
            return if (gt > 0) "ジャンルを調べています(${gd}/${gt})。終わった分から分け方に反映します" else "よく聴くジャンルごとに、$tail"
        }
        val (doneN, totalN) = MoodMix.progress.value
        // 曲調で分ける設定でも、分析が足りない間はアーティストで仮に分けている(説明を実際に合わせる)
        val provisional = MixPrefs.byMood() && runCatching { MoodMix.groups() }.getOrNull() == null
        return if (totalN > 0 && MixPrefs.byMood()) "曲調を分析中(${doneN}/${totalN})。" +
                (if (provisional) "分析が進むまで、アーティストごとに分けています" else "終わった分から分け方に反映します")
            else if (provisional) "曲調の分析が進むまで、アーティストごとに分けています。押すたびに入れ替わります"
            else (if (MixPrefs.byMood()) "曲調ごとに" else "よく一緒に聴くアーティストごとに") +
                (if (MixPrefs.moreNew()) "、新曲をたくさん。" else "、新曲と聴き慣れた曲をほどよく。") + "押すたびに入れ替わります"
    }

    private var lastRender = 0L

    /** 裏で先に計算しておいたミックス(次の作り直しで使う。無ければその場で計算) */
    @Volatile private var precomputedMixes: List<DailyMix.Mix>? = null

    /** 指で触っている間と、離して 0.8 秒たつまで待つ */
    private suspend fun waitUntilIdle() {
        while (true) {
            val m = activity as? MainActivity ?: return
            val idle = !m.touching && android.os.SystemClock.uptimeMillis() - m.lastTouchAt > 800
            if (idle) return
            kotlinx.coroutines.delay(200)
        }
    }

    private fun header(title: String, note: String? = null, action: Pair<String, () -> Unit>? = null,
                       refresh: (() -> Unit)? = null, open: (() -> Unit)? = null, loading: Boolean = false,
                       noteTag: String? = null) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }
        val texts = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(requireContext()).apply {
            // 押せる見出しは「›」付き。タップで全部の一覧
            text = if (open != null) "$title ›" else title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            if (open != null) {
                val tv = android.util.TypedValue()
                requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setPadding(0, (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
                setOnClickListener { open() }
                contentDescription = "$title の一覧を開く"
            }
        })
        // 取り直し中: 更新ボタンの代わりにくるくる(切り替えに時間がかかっていると分かるように)
        if (refresh != null && loading) titleRow.addView(com.google.android.material.progressindicator.CircularProgressIndicator(requireContext()).apply {
            isIndeterminate = true
            indicatorSize = (20 * dp).toInt()
            trackThickness = (2 * dp).toInt()
            contentDescription = "$title を更新中"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (12 * dp).toInt()
        })
        // 更新ボタンは題名のすぐ横に(端だと押しにくいので)
        else if (refresh != null) titleRow.addView(android.widget.ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_refresh)
            contentDescription = "$title を更新"
            val tv = android.util.TypedValue()
            requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener {
                // くるっと回して、取り直し中だと分かるように
                animate().rotationBy(360f).setDuration(600).start()
                refresh()
            }
        }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginStart = (4 * dp).toInt() })
        texts.addView(titleRow)
        if (note != null) texts.addView(TextView(requireContext()).apply {
            text = if (refresh != null && loading) "更新中…(新しい候補を探しています)" else note
            if (noteTag != null) tag = noteTag
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            alpha = 0.7f
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) row.addView(textButton(action.first, action.second))
        content.addView(row)
    }

    /** ホームの欄の並び替え(≡)と表示(チェック) */
    private fun editHome() {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val items = HomeSections.order(prefs).toMutableList()
        val hidden = HomeSections.hidden(prefs).toMutableSet()
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (12 * d).toInt(), 0, (8 * d).toInt()) }
        root.addView(TextView(ctx).apply {
            text = "ホームを編集"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setPadding((20 * d).toInt(), 0, (20 * d).toInt(), (2 * d).toInt())
        })
        root.addView(TextView(ctx).apply {
            text = "≡ をつかんで並び替え、チェックを外すと隠せます(「前回の続き」はいつも一番上)"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            alpha = 0.75f
            setPadding((20 * d).toInt(), 0, (20 * d).toInt(), (8 * d).toInt())
        })
        lateinit var touch: androidx.recyclerview.widget.ItemTouchHelper
        val list = androidx.recyclerview.widget.RecyclerView(ctx).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx) }
        val adapterH = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((8 * d).toInt(), 0, (12 * d).toInt(), 0)
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt())
                }
                row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_drag)
                    contentDescription = "並び替え"
                    setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
                row.addView(com.google.android.material.checkbox.MaterialCheckBox(ctx),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(row) {}
            }
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val row = h.itemView as LinearLayout
                val key = items[position]
                val cb = row.getChildAt(1) as com.google.android.material.checkbox.MaterialCheckBox
                cb.setOnCheckedChangeListener(null)
                cb.text = HomeSections.labels[key] ?: key
                cb.isChecked = key !in hidden
                cb.setOnCheckedChangeListener { _, on -> if (on) hidden -= key else hidden += key }
                row.getChildAt(0).setOnTouchListener { _, e ->
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) touch.startDrag(h)
                    false
                }
            }
        }
        touch = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: androidx.recyclerview.widget.RecyclerView, a: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                                b: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean {
                val from = a.bindingAdapterPosition; val to = b.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                items.add(to, items.removeAt(from))
                adapterH.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
        })
        list.adapter = adapterH
        touch.attachToRecyclerView(list)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), 0)
        }
        buttons.addView(textButton("元に戻す") { HomeSections.reset(prefs); dialog.dismiss(); render() })
        buttons.addView(textButton("完了") { HomeSections.save(prefs, items, hidden); dialog.dismiss(); render() })
        root.addView(buttons)
        dialog.setContentView(androidx.core.widget.NestedScrollView(ctx).apply { addView(root) })
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.isDraggable = false
        dialog.show()
    }

    private fun textButton(label: String, onClick: () -> Unit) =
        MaterialButton(requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun quickButtons(playable: List<Track>): View {
        val key = "quick"
        val scroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            setOnScrollChangeListener { _, x, _, _, _ -> hScroll[key] = x }
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        fun chip(label: String, icon: Int, onClick: () -> Unit) {
            row.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                setIconResource(icon)
                iconSize = (18 * dp).toInt()
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (8 * dp).toInt() }
            })
        }
        fun none() = Toast.makeText(requireContext(), "まだ曲がありません", Toast.LENGTH_SHORT).show()
        chip("全曲シャッフル", R.drawable.ic_shuffle) {
            if (playable.isEmpty()) none() else main.player.play(playable, 0, shuffle = true)
        }
        // タップで一覧(中身を見てから ▶ で再生)。「全曲シャッフル」だけはすぐ再生
        chip("最近追加", R.drawable.ic_add) {
            if (playable.isEmpty()) none() else SmartListActivity.open(requireContext(), SmartListActivity.ADDED)
        }
        chip("久しぶりに聴く", R.drawable.ic_bedtime) { SmartListActivity.open(requireContext(), SmartListActivity.REDISCOVER) }
        chip("最近再生", R.drawable.ic_refresh) {
            if (PlayLog.recentTrackIds().isEmpty()) Toast.makeText(requireContext(), "まだ再生した曲がありません", Toast.LENGTH_SHORT).show()
            else SmartListActivity.open(requireContext(), SmartListActivity.RECENT)
        }
        scroll.addView(row)
        hScroll[key]?.let { x -> scroll.post { scroll.scrollTo(x, 0) } }
        return scroll
    }

    private fun tile(parent: ViewGroup, item: Item): View {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_home_tile, parent, false)
        bindArt(v.findViewById(R.id.art), item)
        v.findViewById<TextView>(R.id.title).text = item.title
        v.findViewById<View>(R.id.pin).visibility = if (PlayLog.isPinned(item.ref)) View.VISIBLE else View.GONE
        v.setOnClickListener { play(item) }
        v.setOnLongClickListener { menu(it, item); true }
        return v
    }

    private fun carousel(items: List<Pair<Item, () -> Unit>>, round: Boolean = false, scrollKey: String? = null,
                         showMenu: Boolean = true, onLong: (Item, View) -> Unit): View {
        // 位置を覚える名前(更新ボタンで左端に戻す欄は決まった名前)
        val key = scrollKey ?: ("c${carouselNo++}:" + (items.firstOrNull()?.first?.ref?.kind ?: ""))
        val scroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            setOnScrollChangeListener { _, x, _, _, _ -> hScroll[key] = x }
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }
        items.forEach { (item, onClick) ->
            val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_home_card, row, false)
            bindArt(v.findViewById(R.id.art), item)
            if (round) v.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.art).shapeAppearanceModel =
                com.google.android.material.shape.ShapeAppearanceModel.builder()
                    .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, 64 * dp).build()
            v.findViewById<TextView>(R.id.title).text = item.title
            if (round) v.findViewById<TextView>(R.id.title).gravity = android.view.Gravity.CENTER_HORIZONTAL
            v.findViewById<TextView>(R.id.subtitle).apply {
                text = item.sub
                visibility = if (item.sub.isBlank()) View.GONE else View.VISIBLE
            }
            v.setOnClickListener { onClick() }
            if (showMenu) {
                v.setOnLongClickListener { onLong(item, it); true }
                v.findViewById<View>(R.id.more).setOnClickListener { onLong(item, it) }
            } else v.findViewById<View>(R.id.more).visibility = View.GONE
            row.addView(v)
        }
        scroll.addView(row)
        hScroll[key]?.let { x -> scroll.post { scroll.scrollTo(x, 0) } }
        return scroll
    }

    /** アーティストの人気曲を試聴(取れなければラジオ) */
    private fun playArtistTop(name: String) {
        Toast.makeText(requireContext(), "「$name」の人気曲を読み込み中…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val top = runCatching { withContext(Dispatchers.IO) { InnerTube.artistTopSongs(name) } }.getOrNull()
            if (!isAdded) return@launch
            if (top != null && top.songs.isNotEmpty()) main.player.playSearch(top.songs, 0)
            else Radio.forArtist(main, main.player, name)
        }
    }

    /** 試聴曲のカード(タップでそこから続けて試聴、長押しでダウンロード・気になる) */
    private fun songCarousel(list: List<Searcher.Result>, removable: Boolean = false, scrollKey: String? = null, noGood: Boolean = false): View {
        val items = list.map { r -> Item(PlayLog.streamRef(r), r.title, r.artist, null, r.thumb, emptyList()) }
        return carousel(items.mapIndexed { i, item -> item to { main.player.playSearch(list, i) } }, scrollKey = scrollKey) { item, v ->
            val r = list[items.indexOf(item)]
            PopupMenu(requireContext(), v).apply {
                if (noGood) {
                    menu.add("No Good(この曲を今後おすすめしない)").setOnMenuItemClickListener {
                        NoGood.addSong(r); r.videoId?.let { DailyDiscover.drop(it) }
                        Toast.makeText(requireContext(), "おすすめに出さないようにしました(設定で解除できます)", Toast.LENGTH_SHORT).show(); true
                    }
                    if (r.artist.isNotBlank()) menu.add("No Good(${Radio.mainArtist(r.artist)} を今後おすすめしない)").setOnMenuItemClickListener {
                        val a = Radio.mainArtist(r.artist)
                        NoGood.addArtist(a, a)
                        DailyDiscover.list.value.filter { Radio.mainArtist(it.artist) == a }.mapNotNull { it.videoId }.forEach { DailyDiscover.drop(it) }
                        Toast.makeText(requireContext(), "「$a」の曲をおすすめに出さないようにしました(設定で解除できます)", Toast.LENGTH_SHORT).show(); true
                    }
                }
                menu.add("☁ ライブラリに追加(オンライン)").setOnMenuItemClickListener {
                    OnlineLibrary.add(r)?.let { Toast.makeText(requireContext(), "☁ ライブラリに追加しました", Toast.LENGTH_SHORT).show() }; true
                }
                menu.add("⬇ ダウンロードしてライブラリに追加").setOnMenuItemClickListener {
                    main.quickAdd("https://www.youtube.com/watch?v=${r.videoId}"); true
                }
                menu.add("この曲のラジオ").setOnMenuItemClickListener {
                    r.videoId?.let { Radio.forVideo(main, main.player, it, r.title, r.artist) }; true
                }
                menu.add(if (Liked.contains(r.videoId)) "「お気に入り」から外す" else "♡ お気に入りに追加").setOnMenuItemClickListener {
                    Liked.toggle(r); true
                }
            }.show()
        }
    }

    private fun songRow(i: Int, t: Track, n: Int, queue: List<Track>): View {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_home_song, content, false)
        v.findViewById<TextView>(R.id.rank).text = "${i + 1}"
        Thumbs.bind(v.findViewById(R.id.art), t.thumbPath)
        v.findViewById<TextView>(R.id.title).text = t.title
        v.findViewById<TextView>(R.id.subtitle).text = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · ")
        v.findViewById<TextView>(R.id.count).text = "${n}回"
        v.setOnClickListener { main.player.play(queue, i) }
        v.setOnLongClickListener {
            menu(it, Item(PlayLog.Ref(PlayLog.Kind.TRACK, t.id.toString(), t.title), t.title, t.artist, t.thumbPath, null, listOf(t)))
            true
        }
        return v
    }

    private fun libraryButtons(): View {
        val grid = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), (8 * dp).toInt())
        }
        val entries = listOf(
            Triple("アルバム", R.drawable.ic_library, "albums"),
            Triple("アーティスト", R.drawable.ic_person, "artists"),
            Triple("曲", R.drawable.ic_music, "songs"),
            Triple("フォルダ", R.drawable.ic_folder, "folders"),
        )
        entries.chunked(2).forEach { pair ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (label, icon, mode) ->
                row.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = label
                    setIconResource(icon)
                    iconTint = android.content.res.ColorStateList.valueOf(
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    minHeight = (52 * dp).toInt()
                    setOnClickListener { main.openLibrary(mode) }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt()
                    }
                })
            }
            grid.addView(row)
        }
        return grid
    }

    private fun bindArt(view: ImageView, item: Item) {
        // あなたのミックス: アーティストごとの代表曲のジャケットを並べて 1 枚に(「この人たちのミックス」と分かるように)
        if (item.ref.key.startsWith("MIX")) {
            // 押して入れ替わった中身があれば、そのジャケット。なければ、そのミックスのアーティストのジャケット
            // そのまとまり用のジャケットだけ使う(分け方が変わった後に、前のまとまりの絵を出さない)
            val now = item.mix?.let { DailyMix.artFor(it) }
            if (!now.isNullOrEmpty()) { Covers.bindSources(view, now, 320); return }
            if (item.tracks.size >= 2) { Covers.bind(view, item.tracks, 320); return }
        }
        if (item.webThumb != null) Thumbs.bindUrl(view, item.webThumb) else Thumbs.bind(view, item.localThumb)
    }
}

/** あなたのミックスの見出しの説明の目印(進み具合だけ書き換える) */
private const val MIX_NOTE = "mixNote"

/** ホームの上の「前回の続き」カード(今はミニプレーヤーに出すので使わない) */
private const val SHOW_RESUME_CARD = false

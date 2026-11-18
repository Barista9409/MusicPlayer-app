package com.example.ytmp3

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 初めて使う人への案内: 説明 4 枚 → 初期設定 4 つ(通知・保存先・追加のしかた・好きなアーティスト)。
 * どれも「あとで」で飛ばせる。設定 →「使い方をもう一度見る」で開き直せる
 */
object Onboarding {
    private const val KEY_DONE = "onboarding_done"
    /** 最初に選んだ好きなアーティスト(おすすめ・ラジオの種)。"名前" のリスト */
    private const val KEY_SEED = "seed_artists"

    fun isDone(main: MainActivity) = main.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    /** 好きなアーティスト(初期設定で選んだ人) */
    fun seedArtists(c: android.content.Context): List<String> =
        c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getString(KEY_SEED, "").orEmpty()
            .split("\n").filter { it.isNotBlank() }

    /** 初回だけ: まだ何もないなら案内を出す。すでに使っている人には出さない */
    fun maybeShow(main: MainActivity) {
        val p = main.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        if (p.getBoolean(KEY_DONE, false)) return
        if (Repository.tracks.value.isNotEmpty() || Liked.list.value.isNotEmpty() || Repository.playlists.value.isNotEmpty()) {
            p.edit().putBoolean(KEY_DONE, true).apply(); return
        }
        show(main)
    }

    private data class Slide(val title: String, val body: String, val icon: Int)

    /** 表示中(画面に戻るたびに重ねて出さないように) */
    private var showing = false

    fun show(main: MainActivity) {
        if (showing) return
        showing = true
        val p = main.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val d = main.resources.displayMetrics.density
        val dialog = BottomSheetDialog(main)
        dialog.setCancelable(false)
        dialog.setOnDismissListener { showing = false }
        val root = LinearLayout(main).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
            minimumHeight = (main.resources.displayMetrics.heightPixels * 0.85f).toInt()
        }
        val body = LinearLayout(main).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 下: 進み具合の点と「あとで / 次へ」
        val dots = TextView(main).apply { gravity = Gravity.CENTER; alpha = 0.6f; setPadding(0, 0, 0, (8 * d).toInt()) }
        root.addView(dots)
        val nav = LinearLayout(main).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val skip = MaterialButton(main, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "あとで" }
        val next = MaterialButton(main).apply { text = "次へ" }
        nav.addView(skip)
        nav.addView(View(main), LinearLayout.LayoutParams(0, 1, 1f))
        nav.addView(next)
        root.addView(nav)
        dialog.setContentView(androidx.core.widget.NestedScrollView(main).apply { addView(root) })
        dialog.behavior.apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true; isDraggable = false }

        val slides = listOf(
            Slide("ようこそ", "YouTube と YouTube Music の曲を探して、聴いて、保存できる音楽プレーヤーです。", R.drawable.ic_music),
            Slide("2 つの持ち方", "☁ オンライン: 容量を使わず、ネットで聴きます。\n⬇ ダウンロード: 端末に保存して、オフラインでも聴けます。\n\n曲ごとに選べて、あとから切り替えもできます。", R.drawable.ic_download),
            Slide("画面のつくり", "ホーム: おすすめ・最近の曲\nプレイリスト: 自分のまとまり\n検索: 曲・アーティスト・動画を探す\n設定: いろいろな設定\n\n再生画面では 🎵 / 🎬(動画)の切り替え、♡ でお気に入り、👎 で今後おすすめしない、ができます。\n\n曲やカードは長押し(または ⋮ / ⋯)でメニュー(ピン留め・No Good など)。", R.drawable.ic_queue_music),
            Slide("さっそく始めましょう", "続けて 4 つだけ設定します(どれもあとで変えられます)。", R.drawable.ic_check),
        )
        val total = slides.size + 4
        var step = 0

        fun title(s: String, icon: Int? = null) {
            if (icon != null) body.addView(android.widget.ImageView(main).apply {
                setImageResource(icon)
                setColorFilter(com.google.android.material.color.MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary))
            }, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()).apply { bottomMargin = (16 * d).toInt(); topMargin = (24 * d).toInt() })
            body.addView(TextView(main).apply {
                text = s
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
                setPadding(0, 0, 0, (12 * d).toInt())
            })
        }
        fun text(s: String) = body.addView(TextView(main).apply {
            text = s
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setLineSpacing(0f, 1.3f)
            setPadding(0, 0, 0, (12 * d).toInt())
        })

        fun finish(goSearch: Boolean) {
            p.edit().putBoolean(KEY_DONE, true).apply()
            dialog.dismiss()
            if (goSearch) main.selectTab(MainActivity.TAB_SEARCH)
            // 端末の音楽を読み込む許可は、案内のあとで聞く
            if (!p.getBoolean("asked_read", false) && !LibraryScanner.hasPermission(main)) {
                p.edit().putBoolean("asked_read", true).apply()
                main.requestLibraryPermission()
            }
            // 好きなアーティストを選んだら、すぐおすすめを作る
            if (seedArtists(main).isNotEmpty()) {
                DailyDiscover.refresh(main, force = true)
                NewReleases.refresh(main, force = true)
            }
        }

        lateinit var render: () -> Unit
        render = {
            body.removeAllViews()
            dots.text = (0 until total).joinToString(" ") { if (it == step) "●" else "○" }
            skip.text = if (step < slides.size) "飛ばす" else "あとで"
            next.text = if (step == total - 1) "検索してみる" else "次へ"
            when (step) {
                in slides.indices -> { val s = slides[step]; title(s.title, s.icon); text(s.body) }
                slides.size -> {
                    title("① 通知", R.drawable.ic_notifications)
                    text("ダウンロードの進み具合や、再生中の操作(ロック画面・通知のボタン)に使います。")
                    // 今の状態を見せる(もう一度見た時も)
                    val granted = android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
                        main, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) text("✓ 今は許可されています")
                    else body.addView(MaterialButton(main).apply {
                        text = "通知を許可する"
                        setOnClickListener { main.ensureNotificationPermission(); step++; render() }
                    })
                }
                slides.size + 1 -> {
                    title("② 保存先", R.drawable.ic_folder)
                    text("ダウンロードした曲・動画を置く場所です。\n今: ${SaveLocation(main).label()}")
                    body.addView(MaterialButton(main, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "保存先を変える"
                        setOnClickListener { main.showLocationDialog() }
                    })
                }
                slides.size + 2 -> {
                    title("③ 曲を「ライブラリに追加」する時", R.drawable.ic_add)
                    text("ライブラリに曲を入れる時のやり方です。")
                    val modes = arrayOf("ask", "online", "download")
                    val labels = arrayOf("毎回選ぶ(おすすめ)", "いつも ☁ オンライン(容量を使わない)", "いつも ⬇ ダウンロード(オフラインでも聴ける)")
                    val group = android.widget.RadioGroup(main)
                    labels.forEachIndexed { i, l ->
                        group.addView(com.google.android.material.radiobutton.MaterialRadioButton(main).apply {
                            id = View.generateViewId(); text = l; tag = modes[i]
                            isChecked = OnlineLibrary.addMode(main) == modes[i]
                            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
                        })
                    }
                    group.setOnCheckedChangeListener { g, id -> (g.findViewById<View>(id)?.tag as? String)?.let { OnlineLibrary.setAddMode(main, it) } }
                    body.addView(group)
                }
                else -> {
                    title("④ 好きなアーティスト", R.drawable.ic_favorite_border)
                    text("3 人くらい選ぶと、最初からあなた向けのおすすめ・ラジオが出ます。")
                    val chosen = ChipGroup(main)
                    fun renderChosen() {
                        chosen.removeAllViews()
                        seedArtists(main).forEach { name ->
                            chosen.addView(Chip(main).apply {
                                text = name; isCloseIconVisible = true
                                setOnCloseIconClickListener {
                                    p.edit().putString(KEY_SEED, (seedArtists(main) - name).joinToString("\n")).apply(); renderChosen()
                                }
                            })
                        }
                    }
                    renderChosen()
                    val input = EditText(main).apply { hint = "アーティスト名を入れて検索"; isSingleLine = true; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
                    val results = ChipGroup(main)
                    fun search() {
                        val q = input.text.toString().trim()
                        if (q.isEmpty()) return
                        results.removeAllViews()
                        results.addView(TextView(main).apply { text = "探しています…"; alpha = 0.7f })
                        main.lifecycleScope.launch {
                            val list = runCatching {
                                withContext(Dispatchers.IO) { InnerTube.search(q, Searcher.Source.MUSIC, Searcher.Section.ARTISTS).results }
                            }.getOrDefault(emptyList()).take(8)
                            results.removeAllViews()
                            if (list.isEmpty()) results.addView(TextView(main).apply { text = "見つかりませんでした"; alpha = 0.7f })
                            list.forEach { r ->
                                results.addView(Chip(main).apply {
                                    text = "＋ ${r.title}"
                                    setOnClickListener {
                                        val now = seedArtists(main)
                                        if (r.title !in now) p.edit().putString(KEY_SEED, (now + r.title).take(10).joinToString("\n")).apply()
                                        renderChosen(); isEnabled = false
                                    }
                                })
                            }
                        }
                    }
                    input.setOnEditorActionListener { _, _, _ -> search(); true }
                    val row = LinearLayout(main).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(MaterialButton(main, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "検索"; setOnClickListener { search() }
                    })
                    body.addView(chosen)
                    body.addView(row)
                    body.addView(results)
                }
            }
        }
        // 保存先を選ぶ画面などから戻ったら、今の設定で表示し直す
        dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { hasFocus -> if (hasFocus) render() }
        skip.setOnClickListener {
            // 説明の途中なら設定へ、設定の途中なら次の設定へ(最後なら閉じる)
            if (step < slides.size) { step = slides.size; render() }
            else if (step < total - 1) { step++; render() }
            else finish(goSearch = false)
        }
        next.setOnClickListener { if (step < total - 1) { step++; render() } else finish(goSearch = true) }
        render()
        dialog.show()
    }
}

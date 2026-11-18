package com.example.ytmp3

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/** 設定タブ: 上に設定、下にダウンロードの一覧 */
class DownloadsFragment : Fragment(R.layout.fragment_downloads) {
    private val main get() = requireActivity() as MainActivity
    private val adapter = Adapter()
    private var settings: LinearLayout? = null
    private var scroll: NestedScrollView? = null
    private var downloadsHeader: View? = null
    private var lastSettingsRender = 0L
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { Backup.export(ctx, uri) } }
            r.onSuccess { toast("バックアップを保存しました(曲 $it 件の情報)") }
            r.onFailure { toast("保存できませんでした: ${it.message}") }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("バックアップから復元")
            .setMessage("今の曲の一覧・プレイリスト・再生の記録・設定を、バックアップの内容に置き換えます。\n" +
                "復元後、アプリを再起動します。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("復元する") { _, _ ->
                val ctx = requireContext().applicationContext
                // 進み具合の画面(閉じられないように)
                val bar = com.google.android.material.progressindicator.LinearProgressIndicator(requireContext()).apply {
                    max = 100; isIndeterminate = false
                }
                val label = android.widget.TextView(requireContext()).apply { text = "データを戻しています… 0%" }
                val d = resources.displayMetrics.density
                val box = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), 0)
                    addView(label); addView(bar)
                }
                val dlg = MaterialAlertDialogBuilder(requireContext())
                    .setTitle("復元しています")
                    .setView(box)
                    .setCancelable(false)
                    .show()
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = runCatching {
                        withContext(Dispatchers.IO) {
                            Backup.restore(ctx, uri) { p ->
                                bar.post { bar.setProgressCompat(p, true); label.text = "データを戻しています… $p%" }
                            }
                        }
                    }
                    r.onFailure { dlg.dismiss(); toast("復元できませんでした: ${it.message}") }
                    r.onSuccess {
                        label.text = "完了。再起動して、足りない曲のダウンロードを始めます…"
                        toast("復元しました。再起動します")
                        // 新しいデータで読み直すためにアプリを再起動
                        val i = Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        ctx.startActivity(i)
                        kotlinx.coroutines.delay(500)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
            }
            .show()
    }

    private val autoLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri == null) return@registerForActivityResult
        if (AutoBackup.enable(requireContext().applicationContext, uri)) {
            toast("自動バックアップをオンにしました。毎日このファイルに上書き保存します")
        } else {
            toast("この保存先は自動バックアップに使えません。別の場所(Google ドライブなど)を選んでください")
        }
        renderSettings()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_LONG).show()

    /** 表示前に「一覧へ」と頼まれた時 */
    private var pendingScroll = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = view.findViewById(R.id.settings)
        scroll = view.findViewById(R.id.scroll)
        downloadsHeader = view.findViewById(R.id.downloadsHeader)
        val banner = view.findViewById<View>(R.id.banner)
        val bannerText = view.findViewById<TextView>(R.id.bannerText)
        val summary = view.findViewById<TextView>(R.id.summary)
        val empty = view.findViewById<View>(R.id.empty)
        val cancelAll = view.findViewById<View>(R.id.cancelAll)
        val clearDone = view.findViewById<View>(R.id.clearDone)
        val logView = view.findViewById<TextView>(R.id.log)
        val logScroll = view.findViewById<ScrollView>(R.id.logScroll)
        val logToggle = view.findViewById<TextView>(R.id.logToggle)

        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@DownloadsFragment.adapter
            itemAnimator = null
        }
        banner.setOnClickListener { scrollToDownloads() }
        cancelAll.setOnClickListener { DownloadManager.cancelAll() }
        clearDone.setOnClickListener { DownloadManager.clearFinished() }
        logToggle.setOnClickListener {
            val show = logScroll.visibility != View.VISIBLE
            logScroll.visibility = if (show) View.VISIBLE else View.GONE
            logToggle.text = if (show) "▼ 詳細ログを隠す" else "▶ 詳細ログを表示"
            if (show) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 設定の値(状態が変わったら作り直す)
                launch {
                    combine(
                        combine(DownloadManager.engineStatus, SleepTimer.state, Alarms.alarms) { _, _, _ -> },
                        LibraryScanner.folders, HiArt.progress, AppUpdater.progress,
                    ) { _, _, _, _ -> }
                        // ジャケットの高画質化・アプリ更新の進み具合は 1 曲ごとに変わるので、作り直しは 0.8 秒に 1 回まで
                        // (設定画面全体を作り直すので、毎回だと画面が固まったようになる)
                        .conflate()
                        .collect {
                            val wait = 800 - (android.os.SystemClock.uptimeMillis() - lastSettingsRender)
                            if (wait > 0) kotlinx.coroutines.delay(wait)
                            renderSettings()
                            lastSettingsRender = android.os.SystemClock.uptimeMillis()
                        }
                }
                launch {
                    DownloadManager.jobs.collect { jobs ->
                        adapter.items = jobs.reversed()
                        adapter.notifyDataSetChanged()
                        // 一覧は画面右上の ⬇(DownloadsSheet)に移したので、ここでは出さない
                        empty.visibility = View.GONE
                        val active = jobs.count { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
                        val done = jobs.count { it.status == JobStatus.DONE }
                        val failed = jobs.count { it.status == JobStatus.FAILED }
                        summary.text = buildString {
                            append("ダウンロード")
                            if (active > 0) append(" · 残り $active")
                            if (done > 0) append(" · 完了 $done")
                            if (failed > 0) append(" · 失敗 $failed")
                        }
                        cancelAll.visibility = if (active > 0) View.VISIBLE else View.GONE
                        clearDone.visibility = if (jobs.size > active) View.VISIBLE else View.GONE
                        banner.visibility = View.GONE
                        val running = jobs.firstOrNull { it.status == JobStatus.RUNNING }
                        val rp = DownloadManager.restoreProgress(jobs)
                        bannerText.text = if (rp != null) "⬇  復元: 曲をダウンロード中 ${rp.first} / ${rp.second}  ›"
                        else "⬇  ダウンロード中 $active 曲" + (running?.let { " · ${it.title.take(20)}" } ?: "") + "  ›"
                    }
                }
                launch {
                    DownloadManager.log.collect { lines ->
                        logView.text = lines.joinToString("\n")
                        if (logScroll.visibility == View.VISIBLE) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
        renderSettings()
        if (pendingScroll) scrollToDownloads()
    }

    /** ダウンロード一覧の位置までスクロール */
    fun scrollToDownloads() {
        // ダウンロードの一覧は画面右上の ⬇ から開く画面に移した
        pendingScroll = false
        if (isAdded) DownloadsSheet.show(requireActivity())
    }

    /** 保存先などが変わった時(MainActivity から) */
    fun refreshLocation() = renderSettings()

    // ───────── 設定 ─────────

    private fun renderSettings() {
        val box = settings ?: return
        if (!isAdded) return
        box.removeAllViews()
        group = null
        val loc = main.saveLocation

        section(box, "listen", "🎧 サウンド", listOfNotNull(
            if (Net.offlineMode(requireContext())) "オフラインモード" else null,
            SoundSettings.fadeSec(requireContext()).let { if (it == 0) null else "フェード $it 秒" },
            "EQ: " + SoundSettings.label(requireContext())).joinToString(" · "))
        sub(box, "オンライン / オフライン")
        val off = Net.offlineMode(requireContext())
        row(box, R.drawable.ic_download, "オフラインモード",
            if (off) "端末にある曲と ☁✓ の曲だけ再生" else "オフ", switch = off) {
            Net.setOfflineMode(requireContext(), !off); renderSettings()
        }
        val nm = Net.noMobileStream(requireContext())
        row(box, R.drawable.ic_download, "モバイル回線ではネットから再生しない",
            if (nm) "Wi-Fi の時だけ ☁ を再生" else "オフ", switch = nm) {
            Net.setNoMobileStream(requireContext(), !nm); renderSettings()
        }
        sub(box, "再生と音")
        val ask = AutoSkip.isOn(requireContext())
        row(box, R.drawable.ic_skip_next, "止まったら自動で次の曲へ",
            if (ask) "読み込みが 15 秒進まない・音が 12 秒止まった時に、取り直してだめなら次の曲へ" else "オフ(止まったまま待ちます)",
            switch = ask) { AutoSkip.setOn(requireContext(), !ask); renderSettings() }
        val fs = SoundSettings.fadeSec(requireContext())
        row(box, R.drawable.ic_music, "曲間のフェード",
            if (fs == 0) "オフ" else "$fs 秒(曲の終わりを小さく、次の曲の始まりを大きくしてなめらかに)") {
            val opts = intArrayOf(0, 2, 4, 6, 8)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("曲間のフェード")
                .setSingleChoiceItems(opts.map { if (it == 0) "オフ" else "$it 秒" }.toTypedArray(), opts.indexOf(fs).coerceAtLeast(0)) { d, i ->
                    SoundSettings.setFadeSec(requireContext(), opts[i]); d.dismiss(); renderSettings()
                }
                .setNegativeButton("閉じる", null)
                .show()
        }
        val level = VolumeLeveler.isOn(requireContext())
        row(box, R.drawable.ic_volume, "音量をそろえる",
            if (level) "曲ごとの音量差をならします" else "オフ(曲の音量そのまま)", switch = level) {
            VolumeLeveler.setOn(requireContext(), !level)
            renderSettings()
        }
        row(box, R.drawable.ic_volume, "イコライザー", SoundSettings.label(requireContext())) {
            val ps = SoundSettings.presets()
            val cur = ps.indexOfFirst { it.id == SoundSettings.presetId(requireContext()) }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("イコライザー")
                .setSingleChoiceItems(ps.map { it.label }.toTypedArray(), cur) { d, i ->
                    SoundSettings.setPreset(requireContext(), ps[i].id); d.dismiss(); renderSettings()
                }
                .setNegativeButton("閉じる", null)
                .show()
        }
        val bg = BackgroundPlay.isOn(requireContext())
        row(box, R.drawable.ic_play, "アプリを終了しても再生を続ける",
            if (bg) "最近使ったアプリから消しても、音楽はそのまま流れます" else "アプリを終了すると、再生も止まります",
            switch = bg) {
            BackgroundPlay.setOn(requireContext(), !bg)
            renderSettings()
        }
        val bt = BluetoothAutoPlay.isOn(requireContext())
        val btOk = BluetoothAutoPlay.hasPermission(requireContext())
        row(box, R.drawable.ic_play, "Bluetooth 接続時に自動再生",
            when {
                !bt -> "オフ"
                !btOk -> "⚠ 許可が必要です(タップで許可)"
                else -> "カーナビやイヤホンで音楽が聴ける時だけ、前回の続きから再生"
            }, switch = bt && btOk) {
            when {
                bt && !btOk -> main.requestBtPermission(force = true)
                bt -> BluetoothAutoPlay.setOn(requireContext(), false)
                else -> { BluetoothAutoPlay.setOn(requireContext(), true); main.requestBtPermission(force = true) }
            }
            renderSettings()
        }

        // ✨ おすすめ: あなたのミックス・ラジオ・No Good(何をおすすめするか)をまとめて
        val moreNew = MixPrefs.moreNew()
        val byMood = MixPrefs.byMood()
        val ac = AutoContinue.isGlobalOn(requireContext())
        section(box, "reco", "✨ おすすめ", listOf(
            if (moreNew) "新曲をたくさん" else "バランスよく",
            when (MixPrefs.split()) { "genre" -> "ジャンルで分ける"; "artist" -> "アーティストで分ける"; else -> "曲調で分ける" },
            if (ac) "ラジオ オン" else "ラジオ オフ").joinToString(" · "))
        sub(box, "あなたのミックス")
        row(box, R.drawable.ic_queue_music, "ミックスの中身",
            if (moreNew) "新曲をたくさん · よく聴くアーティストの新曲を中心に" else "バランスよく · 新曲と聴き慣れた曲をほどよく") {
            choose("ミックスの中身", listOf(
                "バランスよく" to "新曲と聴き慣れた曲・似た曲をほどよく混ぜます",
                "新曲をたくさん" to "よく聴くアーティストの新曲を、頭から続けて流します"),
                if (moreNew) 1 else 0) { i -> MixPrefs.setMoreNew(i == 1); renderSettings() }
        }
        val split = MixPrefs.split()
        row(box, R.drawable.ic_palette, "ミックスの分け方", when (split) {
            "genre" -> "ジャンル · J-POP・K-POP など、よく聴くジャンルごとに"
            "artist" -> "アーティスト · よく一緒に聴く人どうしで"
            else -> "曲調 · にぎやか／まんなか／おだやか"
        }) {
            val modes = listOf("mood", "artist", "genre")
            choose("ミックスの分け方", listOf(
                "曲調で分ける" to "にぎやか・まんなか・おだやかの 3 つ。名前は中身に合わせて(パワフル・さわやか・メロウ など)",
                "アーティストで分ける" to "よく一緒に聴くアーティストどうしで 3 つ。好きな人の曲をまとめて聴けます",
                "ジャンルで分ける" to "よく聴くジャンル(J-POP・K-POP・アニメ など)から 3 つ。そのジャンルの似た曲を集めます。ジャンルはアーティスト名を音楽データベース(MusicBrainz)で調べます"),
                modes.indexOf(split).coerceAtLeast(0)) { i -> MixPrefs.setSplit(modes[i]); renderSettings() }
        }
        // ミックスの曲を取ってくる場所(どちらか 1 つは必ずオン)
        val srcMusic = MixPrefs.useMusic(); val srcYt = MixPrefs.useYouTube()
        sub(box, "ミックスにするもの")
        // 両方オフにしようとした時は、もう片方を自動でオンにする(どちらか 1 つは必ずオン)
        row(box, R.drawable.ic_queue_music, "ミュージック",
            if (srcMusic) (if (srcYt) "曲(音源)と動画を混ぜて流します" else "曲(音源)だけで作ります") else "使いません", switch = srcMusic) {
            val music = !srcMusic
            MixPrefs.setSource(music, if (!music) true else srcYt); renderSettings()
        }
        row(box, R.drawable.ic_movie, "ミュージックビデオ",
            if (srcYt) (if (srcMusic) "動画も混ぜます" else "動画だけで作ります(音源・静止画の曲は入れません・🎬 映像オン)")
            else "使いません", switch = srcYt) {
            val yt = !srcYt
            MixPrefs.setSource(if (!yt) true else srcMusic, yt); renderSettings()
        }
        sub(box, "ラジオと No Good")
        row(box, R.drawable.ic_queue_music, "キューが終わったら似た曲を流す",
            if (ac) "似た曲を自動で続けます(プレイリストとリピート中は流しません)" else "オフ(キューの最後で止まります)", switch = ac) {
            AutoContinue.setGlobalOn(requireContext(), !ac); renderSettings()
        }
        val ngCount = NoGood.songs.value.size + NoGood.artists.value.size
        row(box, R.drawable.ic_close, "No Good にしたもの", if (ngCount == 0) "なし(再生中の 👎 や、曲の長押しから「もう聴かない」)" else "$ngCount 件 · ミックス・ラジオ・おすすめに出さない曲・アーティスト") {
            showNoGood()
        }

        section(box, "look", "🎨 テーマ", Skin.current(requireContext()).label + " · " +
            (if (Skin.current(requireContext()).mesh != null) "明るさ自動" else Skin.brightness(requireContext()).label))
        val skin = Skin.current(requireContext())
        row(box, R.drawable.ic_palette, "スキン", skin.label) { showSkinPicker() }
        row(box, R.drawable.ic_palette, "アプリのアイコン", AppIcon.current(requireContext()).label) { showIconPicker() }
        val br = Skin.brightness(requireContext())
        row(box, R.drawable.ic_brightness, "明るさ",
            if (skin.mesh != null) "スキンに合わせて自動(グラデーション)" else br.label) {
            val items = Skin.Brightness.values()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("明るさ")
                .setSingleChoiceItems(items.map { it.label }.toTypedArray(), items.indexOf(br)) { d, i ->
                    d.dismiss()
                    if (items[i] != br) {
                        // ダーク ↔ ライトは自動で切り替わる。真っ黒 ↔ ダークは作り直しが必要
                        Skin.setBrightness(requireContext(), items[i])
                        requireActivity().recreate()
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
        val ac2 = Skin.artColors(requireContext())
        row(box, R.drawable.ic_music, "再生中の画面をジャケットの色に",
            if (ac2) "曲ごとに背景の色が変わります" else "オフ(スキンの色)", switch = ac2) {
            Skin.setArtColors(requireContext(), !ac2); renderSettings()
        }
        val hp = HiArt.progress.value
        row(box, R.drawable.ic_music, "ジャケットを高画質にする",
            if (hp != null) "取得中… ${hp.first} / ${hp.second} 曲" else "全曲の再生画面・ロック画面・ウィジェット用の大きい画像を先に取得") {
            if (HiArt.progress.value != null) return@row
            val ctx = requireContext().applicationContext
            HiArt.prefetchAll(ctx) { got ->
                android.widget.Toast.makeText(ctx, if (got > 0) "$got 曲のジャケットを高画質にしました" else "新しく取得できる画像はありませんでした",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }

        section(box, "data", "📁 ライブラリ", "${Repository.tracks.value.size} 曲 · 保存先 ${loc.label()} · " +
            (if (AutoBackup.target(requireContext()) != null) "自動バックアップ オン" else "自動バックアップ オフ"))
        sub(box, "ダウンロード")
        row(box, R.drawable.ic_download, "ダウンロードの一覧", "進み具合・失敗した曲のやり直し・詳細ログ") {
            DownloadsSheet.show(requireActivity())
        }
        row(box, R.drawable.ic_folder, "ストレージ", StorageSheet.summary(requireContext())) {
            StorageSheet.show(main)
        }
        sub(box, "動画")
        val vq = VideoPrefs.quality(requireContext())
        row(box, R.drawable.ic_movie, "保存する動画の画質", "${vq}p まで") {
            val qs = intArrayOf(360, 480, 720, 1080)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("保存する動画の画質")
                .setSingleChoiceItems(arrayOf("360p(約 10〜15 MB/曲)", "480p(約 20〜30 MB/曲)", "720p(約 40〜60 MB/曲)", "1080p(約 80〜120 MB/曲)"),
                    qs.indexOf(vq).coerceAtLeast(0)) { d, i -> VideoPrefs.setQuality(requireContext(), qs[i]); d.dismiss(); renderSettings() }
                .setNegativeButton("閉じる", null)
                .show()
        }
        val vw = VideoPrefs.wifiOnly(requireContext())
        row(box, R.drawable.ic_movie, "動画は Wi-Fi の時だけ保存", if (vw) "モバイル回線の時は Wi-Fi につながるまで待ちます" else "オフ(モバイル回線でも保存)", switch = vw) {
            VideoPrefs.setWifiOnly(requireContext(), !vw); renderSettings()
        }
        val vn = VideoPrefs.netForLibrary(requireContext())
        row(box, R.drawable.ic_movie, "🎬 で保存していない曲もネットの動画を映す",
            if (vn) "ライブラリの曲も、動画を保存していなければネットから読み込みます" else "オフ(ライブラリの曲は保存した動画だけ。検索・ラジオの曲はいつもネットの動画)", switch = vn) {
            VideoPrefs.setNetForLibrary(requireContext(), !vn); renderSettings()
        }
        val vd = VideoPrefs.dropAudio(requireContext())
        row(box, R.drawable.ic_movie, "動画を保存したら音声ファイルを消す",
            if (vd) "容量を節約(🎵 の時も動画の音で聴きます)" else "オフ(🎵 はいつもの音声、🎬 は動画)", switch = vd) {
            VideoPrefs.setDropAudio(requireContext(), !vd); renderSettings()
        }
        row(box, R.drawable.ic_add, "曲を「ライブラリに追加」する時", OnlineLibrary.addModeLabel(requireContext())) {
            val modes = arrayOf("ask", "online", "download")
            val labels = arrayOf("毎回選ぶ", "いつも ☁ オンライン", "いつも ⬇ ダウンロード")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("曲を「ライブラリに追加」する時")
                .setSingleChoiceItems(labels, modes.indexOf(OnlineLibrary.addMode(requireContext())).coerceAtLeast(0)) { d, i ->
                    OnlineLibrary.setAddMode(requireContext(), modes[i]); d.dismiss(); renderSettings()
                }
                .setNegativeButton("閉じる", null)
                .show()
        }
        row(box, R.drawable.ic_folder, "保存先", loc.label()) { main.showLocationDialog() }
        row(box, R.drawable.ic_music, "形式",
            if (loc.fastMode) "高速 M4A(変換なし・おすすめ)" else "MP3(変換あり・時間がかかります)") { main.toggleFormat() }
        row(box, R.drawable.ic_folder_open, "アーティスト/アルバムでフォルダ分け",
            if (loc.organize) "${SaveLocation.APP_DIR} / アーティスト / アルバム / 曲" else "保存先にそのまま保存",
            switch = loc.organize) { main.setOrganize(!loc.organize) }
        val lad = LikedAutoDownload.isOn(requireContext())
        row(box, R.drawable.ic_download, "♡ した曲を自動でダウンロード",
            if (lad) "Wi-Fi の時に、お気に入り(♡)に入れた曲をライブラリに保存します" else "オフ", switch = lad) {
            LikedAutoDownload.setOn(requireContext(), !lad)
            if (!lad) { val ctx = requireContext().applicationContext; viewLifecycleOwner.lifecycleScope.launch { LikedAutoDownload.run(ctx) } }
            renderSettings()
        }
        val cacheMb = StreamCache.sizeMb(requireContext())
        row(box, R.drawable.ic_delete, "試聴の一時保存を消す",
            "一度聴いた試聴曲を最大 500MB まで保存して、2 回目から通信なしで再生(今 $cacheMb MB)") {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("試聴の一時保存を消す")
                .setMessage("一度聴いた試聴曲の一時保存($cacheMb MB)を消します。ライブラリの曲には影響しません。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("消す") { _, _ ->
                    val ctx = requireContext().applicationContext
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { StreamCache.clear(ctx) }
                        renderSettings()
                    }
                }
                .show()
        }
        row(box, R.drawable.ic_sort, "保存済みの曲をフォルダ分けする…", "今あるダウンロード済みの曲を整理します") { main.confirmReorganize() }

        sub(box, "ライブラリと記録")
        val folders = LibraryScanner.folders.value
        row(box, R.drawable.ic_library, "読み込むフォルダ",
            folders.joinToString("、") { SaveLocation.folderLabel(it) }.ifBlank { "なし" }) { main.showScanFoldersDialog() }
        row(box, R.drawable.ic_refresh, "フォルダを再読み込み", "新しく入れた曲を探します") { main.scanLibrary(userInitiated = true) }
        val tw = TagWriter.isOn(requireContext())
        row(box, R.drawable.ic_edit, "編集した情報をファイルにも書き込む",
            if (tw) "「情報を編集」の内容を音楽ファイルのタグにも保存(他のアプリでも同じ表示に)" else "オフ(このアプリの中だけで変更)",
            switch = tw) { TagWriter.setOn(requireContext(), !tw); renderSettings() }
        row(box, R.drawable.ic_music, "聴いた記録のまとめ", "今月・最近 90 日によく聴いた曲とアーティスト") {
            ListeningStats.show(requireActivity())
        }
        row(box, R.drawable.ic_delete, "再生履歴を消す", "ホームの「最近再生」「よく聴く曲」の記録") {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("再生履歴を消す")
                .setMessage("ホームの「最近再生したもの」「よく聴く曲」の記録を消します。\nピン留めと曲のファイルはそのままです。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("消す") { _, _ -> PlayLog.clearHistory() }
                .show()
        }

        sub(box, "バックアップ")
        val auto = AutoBackup.target(requireContext())
        val err = AutoBackup.lastError(requireContext())
        val last = AutoBackup.lastTime(requireContext())
        row(box, R.drawable.ic_refresh, "毎日の自動バックアップ", when {
            err != null -> "⚠ $err"
            auto == null -> "オフ(タップで Google ドライブなどに保存先を作成)"
            last > 0 -> "オン · 最終: " + android.text.format.DateFormat.format("M/d H:mm", last)
            else -> "オン · まもなく保存します"
        }, switch = auto != null) {
            if (auto == null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("毎日の自動バックアップ")
                    .setMessage("次の画面で保存先を選んでください。\n左上の ☰ から「ドライブ」を選ぶと Google ドライブに保存できます。\n\n" +
                        "以後、毎日 1 回このファイルに上書き保存します(曲のファイル自体は含みません)。\n" +
                        "戻す時は「バックアップから復元…」でこのファイルを選びます。")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("保存先を選ぶ") { _, _ -> runCatching { autoLauncher.launch(AutoBackup.fileName()) } }
                    .show()
            } else {
                val items = arrayOf("今すぐバックアップ", "保存先を変更…", "自動バックアップをオフにする")
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("毎日の自動バックアップ")
                    .setItems(items) { _, which ->
                        val ctx = requireContext().applicationContext
                        when (which) {
                            0 -> {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val ok = withContext(Dispatchers.IO) { AutoBackup.run(ctx) }
                                    toast(if (ok) "バックアップしました" else (AutoBackup.lastError(ctx) ?: "保存できませんでした"))
                                    renderSettings()
                                }
                            }
                            1 -> runCatching { autoLauncher.launch(AutoBackup.fileName()) }
                            2 -> { AutoBackup.disable(ctx); renderSettings() }
                        }
                    }
                    .setNegativeButton("閉じる", null)
                    .show()
            }
        }
        row(box, R.drawable.ic_download, "バックアップを保存…",
            "曲の一覧と編集内容・プレイリスト・再生の記録・設定・ジャケットを 1 つのファイル(.${Backup.EXT})に。曲のファイル自体は含みません") {
            runCatching { exportLauncher.launch(Backup.fileName()) }
        }
        row(box, R.drawable.ic_refresh, "バックアップから復元…", "機種変更・再インストールの後に(.${Backup.EXT} ファイルを選択)") {
            runCatching { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        }
        row(box, R.drawable.ic_download, "この端末に無い曲をダウンロード",
            "復元した後などに。元の YouTube から取り直します(プレイリストや編集内容はそのまま)") {
            main.offerRedownload(showNone = true)
        }
        row(box, R.drawable.ic_delete, "ライブラリを初期化…", "全曲を一覧から外します(ファイルを消すかは選べます)") { confirmReset() }

        section(box, "app", "ℹ️ アプリ", "v${AppUpdater.currentName(requireContext())} · 更新・自動更新・できること")
        // アップデート: アプリの新しい版の確認と、ダウンロード機能(yt-dlp)の更新をまとめて
        val up = AppUpdater.progress.value
        val ytdlp = runCatching { DownloadManager.engineVersion() }.getOrDefault("?")
        val channel = "（${AppUpdater.channelLabel(requireContext())}）"
        row(box, R.drawable.ic_refresh, "アプリを更新",
            if (up != null) "アプリをダウンロード中… $up%"
            else "v${AppUpdater.currentName(requireContext())}$channel\nyt-dlp $ytdlp") {
            if (AppUpdater.progress.value == null) runUpdate()
        }
        row(box, R.drawable.ic_download, "自動更新設定", UpdateNotifier.modeLabel(requireContext()) +
            when (UpdateNotifier.mode(requireContext())) {
                "auto" -> " · Wi-Fi の時に裏で更新します"
                "notify" -> " · 新しい版が出たら通知します"
                else -> ""
            }) {
            val modes = arrayOf("notify", "auto", "off")
            val labels = arrayOf("通知する", "自動で更新する(Wi-Fi の時)", "オフ")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("自動更新設定")
                .setSingleChoiceItems(labels, modes.indexOf(UpdateNotifier.mode(requireContext())).coerceAtLeast(0)) { d, i ->
                    UpdateNotifier.setMode(requireContext(), modes[i])
                    if (modes[i] != "off") main.ensureNotificationPermission()
                    d.dismiss(); renderSettings()
                }
                .setNegativeButton("閉じる", null)
                .show()
        }
        // 安定版のみを受け取る(既定オン)。オフにすると開発途中のものも届く(作っている人・試す人向け)
        val stableOnly = !AppUpdater.wantsDev(requireContext())
        row(box, R.drawable.ic_refresh, "安定版のみを受け取る", "開発途中のものは除外する", switch = stableOnly) {
            AppUpdater.setDev(requireContext(), stableOnly)
            requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
                .remove("update_notified").apply()
            renderSettings()
        }
        row(box, R.drawable.ic_music, "このアプリについて", "できること・変更履歴・友だちに共有") { main.showAbout() }
        row(box, R.drawable.ic_check, "使い方をもう一度見る", "はじめの説明と、かんたんな初期設定") { Onboarding.show(main) }
        row(box, R.drawable.ic_error, "問題を送る", "うまく動かない時に、状況と記録を送れます") { showReport() }

        group = null
    }

    /** No Good にした曲・アーティストの一覧。チェックして解除 */
    private fun showNoGood() {
        val artists = NoGood.artists.value
        val songs = NoGood.songs.value
        if (artists.isEmpty() && songs.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No Good にしたものはありません", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = artists.map { "🎤 ${it.name}" } + songs.map { "🎵 ${it.name}" + if (it.sub.isNotBlank()) " — ${it.sub}" else "" }
        val checked = BooleanArray(labels.size)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("No Good にしたもの(チェックして解除)")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setNegativeButton("閉じる", null)
            .setPositiveButton("解除") { _, _ ->
                val a = artists.indices.filter { checked[it] }.map { artists[it].key }.toSet()
                val so = songs.indices.filter { checked[artists.size + it] }.map { songs[it].key }.toSet()
                if (a.isNotEmpty()) NoGood.removeArtists(a)
                if (so.isNotEmpty()) NoGood.removeSongs(so)
                if (a.isNotEmpty() || so.isNotEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "${a.size + so.size} 件を解除しました(次の更新から出ることがあります)", android.widget.Toast.LENGTH_SHORT).show()
                    renderSettings()
                }
            }
            .show()
    }

    /** エラー履歴: 新しい順に表示。コピーして送れる */
    /**
     * 問題を送る: 一言 + 版・端末・関係する設定・エラー履歴(新しい 50 件)をまとめて、共有メニューで送る(LINE など)。
     * 送る前に全文を見せる。曲名は伏せられる
     */
    private fun showReport() {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, (4 * d).toInt(), pad, 0) }
        val note = android.widget.EditText(ctx).apply {
            hint = "何が起きましたか？(例: 再生中に音が止まった)"
            minLines = 2
            textSize = 14f
        }
        box.addView(note)
        val mask = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply { text = "曲名を伏せる" }
        box.addView(mask)
        box.addView(TextView(ctx).apply {
            text = "送る内容(このまま送られます)"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        })
        val preview = TextView(ctx).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(preview) }
        box.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (260 * d).toInt()))
        fun build() = reportText(note.text.toString().trim(), mask.isChecked)
        fun refresh() { preview.text = build() }
        note.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refresh() }
        })
        mask.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("問題を送る")
            .setView(box)
            .setPositiveButton("送る") { _, _ ->
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, build()), "問題を送る"))
            }
            .setNegativeButton("コピー") { _, _ ->
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("問題の報告", build()))
                android.widget.Toast.makeText(ctx, "コピーしました", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("記録を消す") { _, _ ->
                MaterialAlertDialogBuilder(ctx).setMessage("記録(${ErrorLog.count()} 件)を消しますか？")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("消す") { _, _ ->
                        ErrorLog.clear()
                        android.widget.Toast.makeText(ctx, "記録を消しました", android.widget.Toast.LENGTH_SHORT).show()
                        if (view != null) renderSettings()
                    }.show()
            }
            .show()
    }

    /** 報告の本文(版・端末・関係する設定・エラー履歴) */
    private fun reportText(note: String, maskTitles: Boolean): String {
        val c = requireContext()
        val onOff = { b: Boolean -> if (b) "オン" else "オフ" }
        val logs = ErrorLog.recent(ErrorLog.count()).filterNot { ErrorLog.isRoutine(it) }.take(50).map { l -> if (maskTitles) l.replace(Regex("「[^」]*」"), "「…」") else l }
        return buildString {
            append("【MusicPlayer 問題の報告】\n")
            append("内容: ").append(note.ifBlank { "(書かれていません)" }).append("\n\n")
            append("版: v${AppUpdater.currentName(c)} (${AppUpdater.currentCode(c)}) · 安定版のみ: ${onOff(!AppUpdater.wantsDev(c))}\n")
            append("端末: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}\n")
            append("設定: ラジオ ${onOff(AutoContinue.isGlobalOn(c))} · 音量をそろえる ${onOff(VolumeLeveler.isOn(c))} · " +
                "イコライザー ${SoundSettings.label(c)} · 曲間フェード ${SoundSettings.fadeSec(c)}秒 · 止まったら次へ ${onOff(AutoSkip.isOn(c))}\n")
            append("\n--- 記録(新しい順・${logs.size} 件) ---\n")
            if (logs.isEmpty()) append("(記録はありません)\n") else logs.forEach { append(it).append("\n") }
        }
    }

    /** 「アプリを更新」: yt-dlp を更新しつつアプリの新しい版を確認して、結果をまとめて表示 */
    private fun runUpdate() {
        val dlg = MaterialAlertDialogBuilder(requireContext())
            .setTitle("アプリを更新")
            .setMessage("確認しています…\n(数十秒かかることがあります)")
            .setCancelable(false)
            .show()
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // アプリの確認と yt-dlp の更新を同時に進めて、両方終わってからまとめて表示
            val appJob = async(Dispatchers.IO) { runCatching { AppUpdater.latest(ctx) } }
            // 画面を閉じても更新は最後まで続ける(待つのは 2 分まで)
            val engineRun = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
                runCatching { DownloadManager.updateEngineAndWait() }.getOrElse { "確認できませんでした" }
            }
            val engineJob = async {
                kotlinx.coroutines.withTimeoutOrNull(120_000) { engineRun.await() }
                    ?: "まだ更新中です(終わるまで裏で続けます)"
            }
            val app = appJob.await()
            if (dlg.isShowing && !engineJob.isCompleted) dlg.setMessage("yt-dlp を更新しています…")
            val engine = engineJob.await()
            dlg.dismiss()
            if (!isAdded) return@launch
            val cur = AppUpdater.currentName(ctx)
            val rel = app.getOrNull()?.takeIf { it.code > AppUpdater.currentCode(ctx) }
            val appLine = when {
                app.isFailure -> "アプリ: 確認できませんでした(通信を確認してください)"
                rel != null -> "アプリ: 新しい版があります(v$cur → ${rel.name.removePrefix("MusicPlayer ")})" +
                    rel.notes.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                else -> "アプリ: 最新です(v$cur)"
            }
            val b = MaterialAlertDialogBuilder(requireContext())
                .setTitle("アプリを更新")
                .setMessage("$appLine\n\nyt-dlp: $engine")
            if (rel != null) b.setNegativeButton("あとで", null).setPositiveButton("アプリを更新する") { _, _ -> main.startAppUpdate(rel) }
            else b.setPositiveButton("OK", null)
            b.show()
            renderSettings()
        }
    }

    /** ホーム画面のアイコンの色を選ぶ(アイコンの見本つき) */
    private fun showIconPicker() {
        val c = requireContext()
        val cur = AppIcon.current(c)
        val dp = resources.displayMetrics.density
        val adapter = object : android.widget.ArrayAdapter<AppIcon.Def>(c, 0, AppIcon.all) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val d = getItem(position)!!
                val row = LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
                }
                row.addView(ImageView(c).apply {
                    setImageDrawable(androidx.core.content.ContextCompat.getDrawable(c, d.mipmap))
                    layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
                })
                row.addView(TextView(c).apply {
                    text = if (d == cur) "${d.label}  ✓" else d.label
                    textSize = 16f
                    setPadding((16 * dp).toInt(), 0, 0, 0)
                })
                return row
            }
        }
        MaterialAlertDialogBuilder(c)
            .setTitle("アプリのアイコン")
            .setAdapter(adapter) { _, i ->
                val d = AppIcon.all[i]
                if (d == cur) return@setAdapter
                AppIcon.set(c, d.key)
                MaterialAlertDialogBuilder(c)
                    .setMessage("アイコンを「${d.label}」にしました。\n\nホーム画面に反映されるまで、数秒〜1 分ほどかかることがあります。" +
                        "その間にホーム画面のアイコンを押すと「インストールされていません」と出ることがありますが、少し待つと直ります。\n\n" +
                        "機種によっては、ホーム画面に置いていたアイコンが消えることがあります。その時はアプリの一覧から置き直してください。")
                    .setPositiveButton("OK", null)
                    .show()
                renderSettings()
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** ライブラリの初期化: 何を消すか選んで、もう一度確認してから実行 */
    private fun confirmReset() {
        val n = Repository.tracks.value.size
        val items = arrayOf("曲のファイルも端末から削除する(元に戻せません)", "プレイリストも削除する", "再生の記録・ピン留めも消す")
        val checked = booleanArrayOf(false, true, true)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("ライブラリを初期化($n 曲)")
            .setMultiChoiceItems(items, checked) { _, i, on -> checked[i] = on }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("次へ") { _, _ ->
                val msg = buildString {
                    append("ライブラリの $n 曲を一覧から外します。\n\n")
                    append(if (checked[0]) "・曲のファイルも端末から削除します(元に戻せません)\n"
                        else "・曲のファイルは端末に残します(フォルダの再読み込みでも戻りません。戻す時は「読み込むフォルダ → 除外した曲を元に戻す」)\n")
                    if (checked[1]) append("・プレイリストを削除します\n")
                    if (checked[2]) append("・再生の記録とピン留めを消します\n")
                    append("\n先に「バックアップを保存」しておくと、後で戻せます。")
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(if (checked[0]) "本当に初期化しますか？(ファイルも削除)" else "本当に初期化しますか？")
                    .setMessage(msg)
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("初期化する") { _, _ ->
                        main.actions.resetLibrary(deleteFiles = checked[0], clearPlaylists = checked[1], clearHistory = checked[2])
                        renderSettings()
                    }
                    .show()
            }
            .show()
    }

    /** スキンを選ぶ: 色の丸とイラストの見本を並べて、タップで切り替え */
    private fun showSkinPicker() {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val cur = Skin.current(ctx)
        val dynamicOk = com.google.android.material.color.DynamicColors.isDynamicColorAvailable()
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (24 * d).toInt()) }
        fun title(t: String) = TextView(ctx).apply {
            text = t
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding((4 * d).toInt(), (12 * d).toInt(), 0, (8 * d).toInt())
        }
        fun grid(defs: List<Skin.Def>, illust: Boolean): android.widget.GridLayout {
            val g = android.widget.GridLayout(ctx).apply { columnCount = if (illust) 3 else 4 }
            for (s in defs) {
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
                    val tv = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                }
                val selected = s.key == cur.key
                val stroke = com.google.android.material.color.MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
                val thumb = com.google.android.material.imageview.ShapeableImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val shape = com.google.android.material.shape.ShapeAppearanceModel.builder()
                        .setAllCornerSizes(if (illust) 12 * d else 100 * d).build()
                    shapeAppearanceModel = shape
                    if (selected) { strokeWidth = 3 * d; strokeColor = android.content.res.ColorStateList.valueOf(stroke); setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt()) }
                    when {
                        s.mesh != null -> setImageDrawable(MeshDrawable(s.mesh))
                        illust -> {
                            setBackgroundColor(if (Skin.isNight(ctx)) 0xFF14161F.toInt() else 0xFFF8F6F8.toInt())
                            setImageResource(resources.getIdentifier("skin_${s.key}_art", "drawable", ctx.packageName))
                        }
                        s.key == "dynamic" -> setImageDrawable(android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            intArrayOf(0xFFE57373.toInt(), 0xFFFFD54F.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(), 0xFFBA68C8.toInt())))
                        else -> setImageDrawable(android.graphics.drawable.ColorDrawable(s.swatch))
                    }
                    alpha = if (s.key == "dynamic" && !dynamicOk) 0.35f else 1f
                }
                val w = if (illust) (88 * d).toInt() else (52 * d).toInt()
                val h = if (illust) (150 * d).toInt() else (52 * d).toInt()
                cell.addView(thumb, LinearLayout.LayoutParams(w, h))
                cell.addView(TextView(ctx).apply {
                    text = if (s.key == "dynamic" && !dynamicOk) "壁紙の色\n(Android 12〜)" else if (s.key == "navy") "ネイビー" else if (s.key == "dynamic") "壁紙の色" else s.label
                    gravity = android.view.Gravity.CENTER
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                    setPadding(0, (4 * d).toInt(), 0, 0)
                    if (selected) setTextColor(stroke)
                })
                cell.setOnClickListener {
                    if (s.key == "dynamic" && !dynamicOk) {
                        android.widget.Toast.makeText(ctx, "壁紙の色は Android 12 以上で使えます", android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    if (s.key != cur.key) {
                        Skin.setCurrent(ctx, s.key)
                        requireActivity().recreate()
                    }
                }
                g.addView(cell, android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                })
            }
            return g
        }
        root.addView(title("色"))
        root.addView(grid(Skin.all.filter { !it.illust && it.mesh == null }, false))
        root.addView(title("グラデーション"))
        root.addView(TextView(ctx).apply {
            text = "選ぶと明るさは自動で決まります(文字が読みやすいように)"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding((4 * d).toInt(), 0, 0, (6 * d).toInt())
        })
        root.addView(grid(Skin.all.filter { it.mesh != null }, true))
        root.addView(title("イラスト"))
        root.addView(grid(Skin.all.filter { it.illust }, true))
        dialog.setContentView(NestedScrollView(ctx).apply { addView(root) })
        dialog.show()
    }

    /** 通知の履歴: タップで全文、✕ で 1 件ずつ、「すべて消す」でまとめて消す */
    fun showInbox() {
        val ctx = requireContext()
        Inbox.markSeen(ctx)
        val d = resources.displayMetrics.density
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (12 * d).toInt(), 0, (16 * d).toInt()) }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * d).toInt(), 0, (8 * d).toInt(), (4 * d).toInt())
        }
        head.addView(TextView(ctx).apply {
            text = "お知らせ"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val clearAll = com.google.android.material.button.MaterialButton(ctx, null,
            com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "すべて消す" }
        head.addView(clearAll)
        root.addView(head)
        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(NestedScrollView(ctx).apply { addView(listBox) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val fmt = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.JAPAN)

        fun render() {
            listBox.removeAllViews()
            val items = Inbox.list.value
            clearAll.isEnabled = items.isNotEmpty()
            if (items.isEmpty()) {
                listBox.addView(TextView(ctx).apply {
                    text = "通知の履歴はありません"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setPadding((20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt())
                })
                return
            }
            for (e in items) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((20 * d).toInt(), (10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt())
                    val tv = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                }
                val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(TextView(ctx).apply {
                    text = e.title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                })
                val body = TextView(ctx).apply {
                    text = e.text
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                }
                texts.addView(body)
                texts.addView(TextView(ctx).apply {
                    text = fmt.format(java.util.Date(e.time))
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                    alpha = 0.7f
                })
                row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_close)
                    contentDescription = "消す"
                    val tv = android.util.TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener { Inbox.remove(ctx, e); render() }
                }, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
                // タップで全文を開く/閉じる。「v… に更新しました」は変更点を開く
                val updated = Regex("v([0-9.]+) に更新しました").find(e.title)?.groupValues?.get(1)
                if (updated != null) row.setOnClickListener { main.showChanges(updated) }
                else row.setOnClickListener {
                    val open = body.maxLines != Int.MAX_VALUE
                    body.maxLines = if (open) Int.MAX_VALUE else 2
                }
                listBox.addView(row)
            }
        }
        clearAll.setOnClickListener { Inbox.clear(ctx); render() }
        render()
        dialog.setContentView(root)
        dialog.show()
    }

    /** グループの中の小見出し */
    private fun sub(box: LinearLayout, title: String) {
        val d = resources.displayMetrics.density
        (group ?: box).addView(TextView(requireContext()).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            alpha = 0.7f
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (2 * d).toInt())
        })
    }

    /** 今のグループ(行はここに入る) */
    private var group: LinearLayout? = null

    /** 設定のグループ: 見出しをタップで開いたり閉じたり(開き具合は覚えておく)。閉じている時は中身の要約を表示 */
    /** 選択画面: 選択肢の名前と、その下に小さく説明 */
    private fun choose(title: String, items: List<Pair<String, String>>, checked: Int, onPick: (Int) -> Unit) {
        val labels = items.map { (name, note) ->
            android.text.SpannableStringBuilder(name).apply {
                append("\n")
                val st = length
                append(note)
                setSpan(android.text.style.RelativeSizeSpan(0.82f), st, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(0x99888888.toInt()), st, length, 0)
            }
        }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { d, i -> d.dismiss(); onPick(i) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun section(box: LinearLayout, key: String, title: String, summary: String) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        var open = prefs.getBoolean("set_open_$key", false)
        val primary = com.google.android.material.color.MaterialColors.getColor(box, androidx.appcompat.R.attr.colorPrimary)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt())
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(primary)
        })
        val sum = TextView(ctx).apply {
            text = summary
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            alpha = 0.75f
        }
        texts.addView(sum)
        header.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_expand_down)
            imageTintList = android.content.res.ColorStateList.valueOf(primary)
        }
        header.addView(chevron, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun apply(animate: Boolean) {
            if (animate) android.transition.TransitionManager.beginDelayedTransition(box)
            body.visibility = if (open) View.VISIBLE else View.GONE
            sum.visibility = if (open) View.GONE else View.VISIBLE
            chevron.rotation = if (open) 180f else 0f
            header.contentDescription = "$title(" + (if (open) "開いています" else "閉じています") + ")"
        }
        header.setOnClickListener {
            open = !open
            prefs.edit().putBoolean("set_open_$key", open).apply()
            apply(true)
        }
        apply(false)
        // グループの区切り線
        if (box.childCount > 0) box.addView(View(ctx).apply {
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(box, com.google.android.material.R.attr.colorOutlineVariant))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt().coerceAtLeast(1)).apply {
            marginStart = (16 * d).toInt(); marginEnd = (16 * d).toInt()
        })
        box.addView(header)
        box.addView(body)
        group = body
    }

    private fun row(box: LinearLayout, icon: Int, title: String, value: String, switch: Boolean? = null, onClick: () -> Unit) {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_setting, box, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.value).text = value
        v.findViewById<MaterialSwitch>(R.id.toggle).apply {
            visibility = if (switch == null) View.GONE else View.VISIBLE
            isChecked = switch == true
        }
        v.setOnClickListener { onClick() }
        (group ?: box).addView(v)
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<DownloadJob> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val title: TextView = v.findViewById(R.id.title)
            val progress: LinearProgressIndicator = v.findViewById(R.id.progress)
            val message: TextView = v.findViewById(R.id.message)
            val action: ImageButton = v.findViewById(R.id.action)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_job, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val j = items[position]
            h.title.text = j.title
            h.progress.visibility = if (j.status == JobStatus.RUNNING) View.VISIBLE else View.GONE
            if (j.status == JobStatus.RUNNING) {
                h.progress.isIndeterminate = j.progress <= 0
                if (j.progress > 0) h.progress.setProgressCompat(j.progress, false)
            }
            h.message.text = when (j.status) {
                JobStatus.QUEUED -> "待機中"
                JobStatus.RUNNING -> j.message.ifBlank { "処理中…" }
                JobStatus.DONE -> j.message.ifBlank { "完了" }
                JobStatus.FAILED -> "失敗: ${j.message}"
                JobStatus.CANCELED -> "キャンセルしました"
            }
            h.icon.setImageResource(
                when (j.status) {
                    JobStatus.DONE -> R.drawable.ic_check_circle
                    JobStatus.FAILED -> R.drawable.ic_error
                    JobStatus.CANCELED -> R.drawable.ic_close
                    else -> R.drawable.ic_download
                }
            )
            when (j.status) {
                JobStatus.QUEUED, JobStatus.RUNNING -> {
                    h.action.setImageResource(R.drawable.ic_close)
                    h.action.contentDescription = "中止"
                    h.action.setOnClickListener { DownloadManager.cancel(j.id) }
                }
                JobStatus.FAILED, JobStatus.CANCELED -> {
                    h.action.setImageResource(R.drawable.ic_refresh)
                    h.action.contentDescription = "再試行"
                    h.action.setOnClickListener { DownloadManager.retry(requireContext(), j.id) }
                }
                JobStatus.DONE -> {
                    h.action.setImageResource(R.drawable.ic_play)
                    h.action.contentDescription = "再生"
                    h.action.setOnClickListener {
                        j.trackId?.let { Repository.track(it) }?.let { main.player.play(listOf(it), 0) }
                    }
                }
            }
        }
    }
}

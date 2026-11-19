package com.example.ytmp3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle

class MainActivity : AppCompatActivity() {

    lateinit var player: PlayerConnection
        private set

    /** 指が画面に触れているか・最後に触れた時刻(触っている間に画面を作り直してタップを取り消さないように) */
    var touching = false
        private set
    var lastTouchAt = 0L
        private set

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        lastTouchAt = android.os.SystemClock.uptimeMillis()
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> touching = true
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> touching = false
        }
        return super.dispatchTouchEvent(ev)
    }
    lateinit var actions: TrackActions
    lateinit var editor: TrackEditor
        private set
    lateinit var saveLocation: SaveLocation
        private set
    val nowPlayingId = MutableStateFlow<Long?>(null)
    /** プレーヤーにつながったら入る(ホームの「前回の続き」用) */
    val controllerFlow = MutableStateFlow<androidx.media3.session.MediaController?>(null)
    /** アプリアイコン長押しのショートカット(プレーヤー接続後に実行) */
    private var pendingShortcut: Intent? = null

    /** 試聴中(ストリーミング)の動画 ID */
    val nowPlayingStreamId: String?
        get() = player.controller?.currentMediaItem?.mediaId
            ?.takeIf { it.startsWith(PlayerConnection.STREAM_PREFIX) }?.removePrefix(PlayerConnection.STREAM_PREFIX)
    /** 音楽ファイルの読み取り権限があるか */
    val libraryPermission = MutableStateFlow(false)
    private var lastScan = 0L

    private lateinit var toolbar: MaterialToolbar
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var nav: BottomNavigationView
    private lateinit var mini: MiniPlayer
    private var currentTab = TAB_LIBRARY
    /** ホームタブでライブラリ画面を開いているか */
    private var libraryOpen = false
    private val libraryBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val lib = supportFragmentManager.findFragmentByTag(TAGS[0]) as? LibraryFragment
            if (lib?.handleBack() != true) closeLibrary()
        }
    }
    private var fabAllowed = true

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                saveLocation.setTree(uri)
                toast("保存先を変更しました")
            } catch (e: Exception) {
                toast("このフォルダは選べません")
            }
            downloads().refreshLocation()
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Bluetooth 自動再生のための「付近のデバイス」の許可 */
    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("許可がないため、Bluetooth 接続時の自動再生は使えません(設定から変更できます)")
    }

    /** Bluetooth 自動再生がオンで許可が無ければ、説明してから許可を求める */
    fun requestBtPermission(force: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT < 31 || BluetoothAutoPlay.hasPermission(this)) return
        val st = getSharedPreferences("settings", MODE_PRIVATE)
        if (!force && st.getBoolean("bt_perm_asked", false)) return
        st.edit().putBoolean("bt_perm_asked", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("Bluetooth 接続時に自動再生")
            .setMessage("カーナビやイヤホンにつながった時に、前回の続きから自動で再生します。\n" +
                "そのために「付近のデバイス」の許可が必要です。\n\n(設定 → 再生 でオフにもできます)")
            .setNegativeButton("今はしない", null)
            .setPositiveButton("許可する") { _, _ ->
                runCatching { btPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }
            }
            .show()
    }

    private val readPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        libraryPermission.value = granted
        if (granted) scanLibrary(userInitiated = true)
        else toast("許可がないため、フォルダの曲は読み込めません")
    }

    private val pickScanFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (docId == null) { toast("このフォルダは選べません"); return@registerForActivityResult }
        val rel = LibraryScanner.normalize(SaveLocation.relativeFromDocId(docId))
        LibraryScanner.setFolders(this, LibraryScanner.folders.value + rel)
        toast("「${SaveLocation.folderLabel(rel)}」を追加しました")
        scanLibrary(userInitiated = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTiming.activityCreated()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        saveLocation = SaveLocation(this)
        actions = TrackActions(this)
        editor = TrackEditor(this)
        actions.onRemoved = ::removeFromQueue
        mini = MiniPlayer(this, findViewById(R.id.miniPlayer))
        mini.onTrackChanged = { nowPlayingId.value = it }
        player = PlayerConnection(this) { mini.attach(it); controllerFlow.value = it; runPendingShortcut() }

        toolbar = findViewById(R.id.toolbar)
        fab = findViewById(R.id.fab)
        nav = findViewById(R.id.bottomNav)
        setupMenu()
        // ダウンロード中は設定タブに残りの曲数
        // ♡ した曲を Wi-Fi の時に自動でダウンロード(♡ が増えた時・アプリを開いた時)
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                Liked.list.collect { kotlinx.coroutines.delay(1500); LikedAutoDownload.run(this@MainActivity) }
            }
        }
        // 設定タブの点: まだ見ていないお知らせ。ダウンロードは右上の ⬇ に数字で
        Inbox.load(this)
        lifecycleScope.launch {
            // 画面が見えている間だけ(裏ではダウンロードの進み具合ごとに動かさない)
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            DownloadManager.jobs.combine(Inbox.unread) { jobs, unread -> jobs to unread }.collect { (jobs, unread) ->
                val badge = nav.getOrCreateBadge(R.id.nav_downloads)
                badge.isVisible = unread > 0
                badge.clearNumber()
                updateBell(unread)
                val active = jobs.count { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
                val failed = jobs.count { it.status == JobStatus.FAILED }
                updateDownloadsIcon(active, failed)
            }
            }
        }

        onBackPressedDispatcher.addCallback(this, libraryBack)
        libraryOpen = savedInstanceState?.getBoolean("libraryOpen") ?: false
        toolbar.setNavigationOnClickListener { closeLibrary() }
        if (savedInstanceState == null) {
            // 最初はホームだけ作る。ほかのタブは初めて開いた時に作る(起動直後に重くならないように)
            supportFragmentManager.beginTransaction()
                .add(R.id.container, HomeFragment(), HOME_TAG)
                .commitNow()
        }
        nav.setOnItemSelectedListener { item ->
            showTab(when (item.itemId) {
                R.id.nav_playlists -> TAB_PLAYLISTS
                R.id.nav_downloads -> TAB_DOWNLOADS
                R.id.nav_search -> TAB_SEARCH
                else -> TAB_LIBRARY
            })
            true
        }
        // 「ホーム」をもう一度押したらライブラリからホームに戻る
        nav.setOnItemReselectedListener { if (it.itemId == R.id.nav_library && libraryOpen) closeLibrary() }
        fab.setOnClickListener {
            if (currentTab == TAB_PLAYLISTS) (fragment(1) as PlaylistsFragment).createNew() else openAddSheet(null)
        }
        showTab(savedInstanceState?.getInt("tab") ?: TAB_LIBRARY)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
        outState.putBoolean("libraryOpen", libraryOpen)
    }

    override fun onStart() {
        super.onStart()
        // 更新した直後なら「今回の変更点」
        Changelog.unseen(this).takeIf { it.isNotEmpty() }?.let { items ->
            MaterialAlertDialogBuilder(this)
                .setTitle("✨ v${AppUpdater.currentName(this)} に更新しました")
                .setMessage(Changelog.format(items))
                .setPositiveButton("OK", null)
                .show()
            // 見たので「更新しました」の通知は消す(お知らせの点も消す)
            UpdateNotifier.clearUpdated(this)
            Inbox.markSeen(this)
        }
        // 1 日 1 回、アプリの新しい版を確認
        checkAppUpdate(manual = false)
        // 自動プレイリストの中身を更新(6 時間に 1 回まで)
        runCatching { SmartPlaylists.refresh(this) }
        // Bluetooth 自動再生(既定オン)の許可を一度だけ聞く
        if (BluetoothAutoPlay.isOn(this)) requestBtPermission()
        // バックアップから復元した直後: この端末に無い曲を取り直すか聞く
        val st = getSharedPreferences("settings", MODE_PRIVATE)
        if (st.getBoolean(Backup.KEY_JUST_RESTORED, false)) {
            st.edit().putBoolean(Backup.KEY_JUST_RESTORED, false).apply()
            // 足りない曲は確認なしで自動でダウンロード(進み具合はホームと設定に表示)
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1500)
                val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DownloadManager.missingOnDevice(this@MainActivity) }
                if (list.isNotEmpty()) {
                    ensureNotificationPermission()
                    DownloadManager.redownload(this@MainActivity, list)
                    toast("復元: 足りない ${list.size} 曲のダウンロードを始めました")
                }
            }
        }
        updateShortcuts()
        player.connect()
        if (!Onboarding.isDone(this)) window.decorView.post { if (!isFinishing) Onboarding.maybeShow(this) }
        libraryPermission.value = LibraryScanner.hasPermission(this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // 初めての人には、案内(Onboarding)が終わってから聞く
        if (!libraryPermission.value && !prefs.getBoolean("asked_read", false) && prefs.getBoolean("onboarding_done", false)) {
            prefs.edit().putBoolean("asked_read", true).apply()
            readPermission.launch(LibraryScanner.permission())
        } else if (System.currentTimeMillis() - lastScan > 60_000) {
            scanLibrary(userInitiated = false)
        }
    }

    /** フォルダを読み込み、ファイルの有無を確認する */
    fun scanLibrary(userInitiated: Boolean) {
        lastScan = System.currentTimeMillis()
        if (userInitiated && !LibraryScanner.hasPermission(this)) {
            requestLibraryPermission()
        }
        lifecycleScope.launch {
            LibraryScanner.refreshNow(applicationContext)
            if (userInitiated && LibraryScanner.hasPermission(this@MainActivity)) toast(LibraryScanner.lastResult.value)
        }
    }

    fun requestLibraryPermission() {
        if (shouldShowRequestPermissionRationale(LibraryScanner.permission()) ||
            !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("asked_read_twice", false)
        ) {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("asked_read_twice", true).apply()
            readPermission.launch(LibraryScanner.permission())
        } else {
            // 「今後表示しない」になっている場合はアプリ設定を開く
            MaterialAlertDialogBuilder(this)
                .setTitle("アクセス許可")
                .setMessage("設定画面の「権限」→「音楽とオーディオ」を許可してください。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("設定を開く") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null)))
                }
                .show()
        }
    }

    fun scanFoldersLabel(): String =
        LibraryScanner.folders.value.joinToString("、") { SaveLocation.folderLabel(it) }.ifEmpty { "フォルダ" }

    fun showScanFoldersDialog() {
        val folders = LibraryScanner.folders.value
        val ignoredCount = Repository.ignored.value.size
        val items = folders.map { "📁 " + SaveLocation.folderLabel(it) } + listOf("＋ フォルダを追加…", "Music フォルダに戻す") +
            (if (ignoredCount > 0) listOf("除外した曲を元に戻す($ignoredCount)") else emptyList())
        MaterialAlertDialogBuilder(this)
            .setTitle("ライブラリに読み込むフォルダ")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < folders.size -> {
                        val f = folders[which]
                        MaterialAlertDialogBuilder(this)
                            .setTitle(SaveLocation.folderLabel(f))
                            .setMessage("このフォルダを読み込み対象から外しますか？\n(取り込み済みの曲はライブラリに残ります)")
                            .setNegativeButton("キャンセル", null)
                            .setPositiveButton("外す") { _, _ -> LibraryScanner.setFolders(this, folders - f) }
                            .show()
                    }
                    which == folders.size -> runCatching { pickScanFolder.launch(null) }.onFailure { toast("フォルダ選択を開けません") }
                    which == folders.size + 2 -> {
                        Repository.clearIgnored()
                        scanLibrary(userInitiated = true)
                    }
                    else -> {
                        LibraryScanner.setFolders(this, listOf("Music/"))
                        scanLibrary(userInitiated = true)
                    }
                }
            }
            .setPositiveButton("今すぐ読み込む") { _, _ -> scanLibrary(userInitiated = true) }
            .setNegativeButton("閉じる", null)
            .show()
    }

    override fun onStop() {
        mini.detach()
        player.release()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (openDownloadsOnResume) { openDownloadsOnResume = false; DownloadsSheet.show(this) }
        pendingTab?.let { t -> pendingTab = null; selectTab(t) }
        pendingSearch?.let { q -> pendingSearch = null; openSearch(q) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** ある版の変更点を出す(通知・お知らせから) */
    fun showChanges(version: String) {
        val items = Changelog.forVersion(this, version)
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("✨ v$version で新しくなったところ")
            .setMessage(Changelog.format(items))
            .setPositiveButton("OK", null)
            .show()
        UpdateNotifier.clearUpdated(this)
    }

    /** 共有されたファイルの中身(大きすぎるものは読まない) */
    private fun readShared(uri: android.net.Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { it.readBytes().take(200_000).toByteArray().toString(Charsets.UTF_8) }
    }.getOrNull()

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // アプリを使う人どうしの共有: 共有用ページの「アプリで開く」/ 共有されたファイル
        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.data
            val text = when (data?.scheme) {
                "musicplayer" -> data.toString()
                "content" -> readShared(data)
                else -> null
            }
            if (text != null) {
                if (Share.isShare(text)) SpotifyImportActivity.open(this, text)
                else Toast.makeText(this, "共有のリンクが見つかりませんでした", Toast.LENGTH_LONG).show()
            }
            intent.action = null
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            // 共有された URL はすぐ追加(YouTube 以外ならいつもの画面)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().ifBlank {
                // ファイルで共有された時(大きいプレイリスト)
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { readShared(it) }.orEmpty()
            }
            val url = Regex("https?://\\S+").find(text)?.value
            // アプリを使う人どうしの共有リンク / Spotify: 確認してから追加する画面へ
            if (Share.isShare(text)) SpotifyImportActivity.open(this, text)
            else if (Spotify.isSpotify(text)) SpotifyImportActivity.open(this, Spotify.find(text) ?: text)
            // YouTube / YouTube Music の共有も、確認してから追加する画面へ(題名は共有元が付けてくれたもの)
            else if (url != null && isYouTubeUrl(url)) SpotifyImportActivity.open(this, url, intent.getStringExtra(Intent.EXTRA_SUBJECT))
            else openAddSheet(text)
            intent.action = null
        }
        // 「更新しました」の通知から: 変更点を開く
        intent.getStringExtra(UpdateNotifier.EXTRA_SHOW_CHANGES)?.let { v ->
            intent.removeExtra(UpdateNotifier.EXTRA_SHOW_CHANGES)
            // 起動時の「更新しました」と二重に出ないよう、ここで見たことにする
            Changelog.unseen(this)
            showChanges(v)
        }
        // 「新しい版があります」の通知から
        if (intent.getBooleanExtra(UpdateNotifier.EXTRA_SHOW_UPDATE, false)) {
            intent.removeExtra(UpdateNotifier.EXTRA_SHOW_UPDATE)
            checkAppUpdate(manual = true)
        }
        if (intent.action == ACTION_SHORTCUT) {
            pendingShortcut = Intent(intent)
            intent.action = null
            runPendingShortcut()
        }
        intent.getStringExtra(EXTRA_SEARCH)?.let { q ->
            intent.removeExtra(EXTRA_SEARCH)
            // 再生画面のアーティスト名から: 画面が前に出てから(裏にいる間はタブを切り替えられず、何も起きなかった)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) openSearch(q)
            else pendingSearch = q
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) {
            intent.removeExtra(EXTRA_OPEN_DOWNLOADS)
            // 通知から: 画面が前に出てから開く(裏にいる間に開こうとすると開けない)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) DownloadsSheet.show(this)
            else openDownloadsOnResume = true
        }
        if (intent.hasExtra(EXTRA_TAB)) {
            val t = intent.getIntExtra(EXTRA_TAB, TAB_LIBRARY)
            intent.removeExtra(EXTRA_TAB)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) selectTab(t) else pendingTab = t
        }
    }

    private var sleepItem: android.view.MenuItem? = null
    private var bellItem: android.view.MenuItem? = null
    private var dlItem: android.view.MenuItem? = null
    /** 再生画面のアーティスト名から: 前に出たら検索する */
    private var pendingSearch: String? = null
    private var pendingTab: Int? = null

    private fun openSearch(q: String) {
        selectTab(TAB_SEARCH)
        (fragment(TAB_SEARCH) as? SearchFragment)?.searchFor(q)
    }

    /** 通知から開いた時: 前に出たらダウンロードの一覧を開く */
    private var openDownloadsOnResume = false

    /** 右上のダウンロード表示: 文字で「⬇ 2 曲 · 45%」「⚠ 失敗 1」。タップで一覧 */
    private var dlButton: com.google.android.material.button.MaterialButton? = null

    private fun updateDownloadsIcon(active: Int, failed: Int) {
        val item = dlItem ?: return
        // いつでも表示(何もしていない時は ⬇ だけ。タップで一覧)
        item.isVisible = true
        val b = dlButton ?: return
        val frac = DownloadManager.overallProgress()
        b.text = when {
            active > 0 -> "$active 曲" + (frac?.takeIf { it > 0f }?.let { " · ${(it * 100).toInt()}%" } ?: "")
            failed > 0 -> "失敗 $failed"
            else -> ""
        }
        b.iconPadding = if (b.text.isNullOrEmpty()) 0 else (4 * resources.displayMetrics.density).toInt()
        val err = active == 0 && failed > 0
        val idle = active == 0 && failed == 0
        val fg = com.google.android.material.color.MaterialColors.getColor(toolbar,
            if (err) androidx.appcompat.R.attr.colorError
            else if (idle) com.google.android.material.R.attr.colorOnSurfaceVariant else androidx.appcompat.R.attr.colorPrimary)
        b.setTextColor(fg)
        b.iconTint = android.content.res.ColorStateList.valueOf(fg)
        b.setIconResource(if (err) R.drawable.ic_error else R.drawable.ic_download)
        b.contentDescription = buildString {
            append("ダウンロード")
            if (active > 0) append("中 残り $active 曲")
            if (failed > 0) append("(失敗 $failed 曲)")
            append("。タップで一覧")
        }
    }

    private var alarmItem: android.view.MenuItem? = null

    /** ベルのアイコン(未読があれば右上に赤い点) */
    private fun updateBell(unread: Int) {
        val item = bellItem ?: return
        val bell = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_notifications)!!.mutate()
        bell.setTint(com.google.android.material.color.MaterialColors.getColor(toolbar,
            com.google.android.material.R.attr.colorOnSurfaceVariant))
        if (unread <= 0) { item.icon = bell; return }
        val d = resources.displayMetrics.density
        val dot = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(com.google.android.material.color.MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorError))
            setSize((8 * d).toInt(), (8 * d).toInt())
        }
        item.icon = android.graphics.drawable.LayerDrawable(arrayOf(bell, dot)).apply {
            setLayerGravity(1, android.view.Gravity.TOP or android.view.Gravity.END)
            setLayerSize(1, (8 * d).toInt(), (8 * d).toInt())
            setLayerInset(1, 0, (1 * d).toInt(), (1 * d).toInt(), 0)
        }
        item.contentDescription = "お知らせ(未読 $unread 件)"
    }


    private fun setupMenu() {
        // お知らせのベル: どのタブでも表示(未読があれば点)
        bellItem = toolbar.menu.add("お知らせ").apply {
            setIcon(R.drawable.ic_notifications)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { downloads().showInbox(); true }
        }
        // ダウンロード: いつでも表示(ダウンロード中は数と進み具合)。タップで一覧
        dlItem = toolbar.menu.add("ダウンロード").apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false
            // アイコンだけだと意味が分かりにくいので、数と進み具合を文字で出す小さなボタン
            val d = resources.displayMetrics.density
            val btn = com.google.android.material.button.MaterialButton(this@MainActivity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                isAllCaps = false
                textSize = 13f
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                insetTop = 0; insetBottom = 0
                iconSize = (18 * d).toInt()
                iconPadding = (4 * d).toInt()
                setPadding((10 * d).toInt(), 0, (12 * d).toInt(), 0)
                setIconResource(R.drawable.ic_download)
                // 枠なし・背景なしで控えめに(アイコンと文字だけ)
                strokeWidth = 0
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                rippleColor = android.content.res.ColorStateList.valueOf(0x22888888)
                setOnClickListener { DownloadsSheet.show(this@MainActivity) }
                layoutParams = android.view.ViewGroup.MarginLayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, (34 * d).toInt())
                    .apply { marginEnd = (4 * d).toInt() }
            }
            dlButton = btn
            actionView = android.widget.FrameLayout(this@MainActivity).apply {
                addView(btn, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, (34 * d).toInt(),
                    android.view.Gravity.CENTER_VERTICAL).apply { marginEnd = (4 * d).toInt() })
            }
            setOnMenuItemClickListener { DownloadsSheet.show(this@MainActivity); true }
        }
        // よく使うものはアイコンで常に表示
        sleepItem = toolbar.menu.add("おやすみタイマー").apply {
            setIcon(R.drawable.ic_bedtime)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { SleepTimerSheet.show(this@MainActivity); true }
        }
        alarmItem = toolbar.menu.add("アラーム").apply {
            setIcon(R.drawable.ic_alarm)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { startActivity(Intent(this@MainActivity, AlarmsActivity::class.java)); true }
        }
        // オンのアラームがあれば時計アイコンをアクセント色に
        lifecycleScope.launch {
            Alarms.alarms.collect { list ->
                val on = list.any { it.enabled }
                alarmItem?.icon?.mutate()?.setTint(
                    com.google.android.material.color.MaterialColors.getColor(toolbar,
                        if (on) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
                alarmItem?.contentDescription = if (on) "アラーム(オンあり)" else "アラーム"
            }
        }
        // おやすみタイマー作動中は月アイコンをアクセント色に
        lifecycleScope.launch {
            SleepTimer.state.collect { st ->
                val active = st !is SleepTimer.State.Off
                sleepItem?.icon?.mutate()?.setTint(
                    com.google.android.material.color.MaterialColors.getColor(
                        toolbar,
                        if (active) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
            }
        }
    }

    // ───────── tabs ─────────

    /** タブの画面(まだ作っていなければ、隠した状態で作る) */
    private fun fragment(i: Int): Fragment {
        supportFragmentManager.findFragmentByTag(TAGS[i])?.let { return it }
        val f = when (i) { 0 -> LibraryFragment(); 1 -> PlaylistsFragment(); 2 -> DownloadsFragment(); else -> SearchFragment() }
        val tx = supportFragmentManager.beginTransaction().add(R.id.container, f, TAGS[i])
        if (!(i == currentTab && (i != TAB_LIBRARY || libraryOpen))) tx.hide(f)
        tx.commitNow()
        return f
    }
    private fun downloads() = fragment(2) as DownloadsFragment

    fun selectTab(tab: Int) {
        nav.selectedItemId = when (tab) {
            TAB_PLAYLISTS -> R.id.nav_playlists
            TAB_DOWNLOADS -> R.id.nav_downloads
            TAB_SEARCH -> R.id.nav_search
            else -> R.id.nav_library
        }
    }

    private fun showTab(tab: Int) {
        currentTab = tab
        val tx = supportFragmentManager.beginTransaction()
        // 開くタブだけ作る(ほかはまだ作らない)
        if (tab != TAB_LIBRARY || libraryOpen) fragment(tab)
        if (supportFragmentManager.findFragmentByTag(HOME_TAG) == null) {
            supportFragmentManager.beginTransaction().add(R.id.container, HomeFragment(), HOME_TAG).commitNow()
        }
        for (i in TAGS.indices) {
            val f = supportFragmentManager.findFragmentByTag(TAGS[i]) ?: continue
            if (i == tab && (i != TAB_LIBRARY || libraryOpen)) tx.show(f) else tx.hide(f)
        }
        supportFragmentManager.findFragmentByTag(HOME_TAG)?.let { if (tab == TAB_LIBRARY && !libraryOpen) tx.show(it) else tx.hide(it) }
        tx.commitNow()
        val inLibrary = tab == TAB_LIBRARY && libraryOpen
        toolbar.title = if (inLibrary) "ライブラリ" else arrayOf("ホーム", "プレイリスト", "設定", "検索")[tab]
        toolbar.navigationIcon = if (inLibrary) androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_arrow_back) else null
        libraryBack.isEnabled = inLibrary
        fab.text = if (tab == TAB_PLAYLISTS) "新規作成" else "曲を追加"
        fab.setIconResource(if (tab == TAB_PLAYLISTS) R.drawable.ic_playlist_add else R.drawable.ic_add)
        updateFab()
    }

    // ───────── アプリアイコンのショートカット ─────────

    private fun runPendingShortcut() {
        val sc = pendingShortcut ?: return
        when (sc.getStringExtra("sc")) {
            "search" -> { pendingShortcut = null; selectTab(TAB_SEARCH); return }
        }
        if (player.controller == null) return // つながったら呼ばれる
        pendingShortcut = null
        val playable = Repository.tracks.value.filterNot { Repository.cantPlay(it.id) }
        when (sc.getStringExtra("sc")) {
            "shuffle" -> if (playable.isNotEmpty()) player.play(playable, 0, shuffle = true) else toast("まだ曲がありません")
            "playlist" -> Repository.playlist(sc.getLongExtra("pid", 0))?.let { p ->
                val ts = Repository.tracksOf(p)
                if (ts.isNotEmpty()) player.play(ts, 0, ref = PlayLog.playlistRef(p)) else toast("「${p.name}」に曲がありません")
            }
        }
    }

    /** アイコン長押しメニュー: 全曲シャッフル・さがす・ピン留めした(なければよく使う)プレイリスト 2 つ */
    private fun updateShortcuts() = runCatching {
        fun intent(sc: String) = Intent(this, MainActivity::class.java).setAction(ACTION_SHORTCUT).putExtra("sc", sc)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val list = mutableListOf(
            androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "shuffle")
                .setShortLabel("全曲シャッフル")
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.sc_shuffle))
                .setIntent(intent("shuffle")).build(),
            androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "search")
                .setShortLabel("検索")
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.sc_search))
                .setIntent(intent("search")).build(),
        )
        val scores = PlayLog.groupScores()
        val pinned = PlayLog.pins().filter { it.kind == PlayLog.Kind.PLAYLIST }.mapNotNull { it.key.toLongOrNull()?.let(Repository::playlist) }
        val frequent = Repository.playlists.value.sortedByDescending { scores["PLAYLIST:${it.id}"] ?: 0 }
        (pinned + frequent).distinctBy { it.id }.filter { Repository.tracksOf(it).isNotEmpty() }.take(2).forEach { p ->
            list += androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "pl_${p.id}")
                .setShortLabel(p.name.take(20))
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.sc_playlist))
                .setIntent(intent("playlist").putExtra("pid", p.id)).build()
        }
        androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(this, list)
    }

    // ───────── ホーム ⇄ ライブラリ ─────────

    private fun library() = fragment(TAB_LIBRARY) as? LibraryFragment

    /** ホームからライブラリを開く("albums" / "artists" / "songs" / "folders") */
    fun openLibrary(mode: String) {
        libraryOpen = true
        if (currentTab != TAB_LIBRARY) selectTab(TAB_LIBRARY) else showTab(TAB_LIBRARY)
        library()?.showMode(mode)
    }

    fun openLibraryAlbum(key: String, title: String) {
        openLibrary("albums"); library()?.showAlbum(key, title)
    }

    fun openLibraryArtist(name: String) {
        openLibrary("artists"); library()?.showArtist(name)
    }

    fun closeLibrary() {
        if (!libraryOpen) return
        libraryOpen = false
        showTab(currentTab)
    }

    fun setFabVisible(v: Boolean) {
        fabAllowed = v
        updateFab()
    }

    private fun updateFab() {
        // 右下のボタンはプレイリストの「新規作成」だけ(曲の追加は「さがす」で)
        // プレイリストは一覧の先頭の「＋」カードで作るので、右下のボタンは出さない(カードに重なるため)
        fab.visibility = View.GONE
    }

    // ───────── アプリの更新 ─────────

    /** 新しい版があれば知らせる(manual = 設定から押した時は「最新です」も出す) */
    fun checkAppUpdate(manual: Boolean) {
        val st = getSharedPreferences("settings", MODE_PRIVATE)
        if (!manual && System.currentTimeMillis() - st.getLong("update_checked", 0L) < 24 * 60 * 60 * 1000L) return
        st.edit().putLong("update_checked", System.currentTimeMillis()).apply()
        lifecycleScope.launch {
            val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AppUpdater.latest(this@MainActivity) } }
            val rel = r.getOrNull()
            when {
                r.isFailure -> if (manual) toast("確認できませんでした(通信を確認してください)")
                rel == null -> if (manual) toast("公開されている更新がまだありません")
                rel.code <= AppUpdater.currentCode(this@MainActivity) -> if (manual) toast("最新の版です(v${AppUpdater.currentName(this@MainActivity)})")
                else -> MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("🎁 新しい版をご用意しました")
                    .setMessage("${rel.name}\n(今: v${AppUpdater.currentName(this@MainActivity)})\n\n" +
                        rel.notes.takeIf { it.isNotBlank() }?.let { "$it\n\n" }.orEmpty() +
                        "ダウンロードして更新しますか？ 最後に Android の「インストール」を押してください。\n" +
                        "データ(曲・プレイリスト・設定)はそのまま残ります。")
                    .setNegativeButton("あとで", null)
                    .setPositiveButton("更新する") { _, _ -> startAppUpdate(rel) }
                    .show()
            }
        }
    }

    fun startAppUpdate(rel: AppUpdater.Release) {
        toast("ダウンロードしています…")
        lifecycleScope.launch {
            val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AppUpdater.downloadAndInstall(this@MainActivity, rel) } }
            r.onFailure { toast("更新をダウンロードできませんでした: ${it.message}") }
        }
    }

    // ───────── 端末に無い曲の取り直し ─────────

    fun offerRedownload(showNone: Boolean) {
        lifecycleScope.launch {
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DownloadManager.missingOnDevice(this@MainActivity) }
            if (list.isEmpty()) { if (showNone) toast("この端末に無い曲はありません"); return@launch }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("この端末に無い曲が ${list.size} 曲あります")
                .setMessage("元の YouTube からダウンロードし直しますか？\nプレイリスト・編集内容・再生の記録はそのまま使えます。\n\n" +
                    "(別のスマホから曲のファイルをコピーする場合は、コピーした後に「設定 → フォルダを再読み込み」でも戻せます)")
                .setNegativeButton("あとで", null)
                .setPositiveButton("ダウンロード") { _, _ ->
                    ensureNotificationPermission()
                    DownloadManager.redownload(this@MainActivity, list)
                    toast("${list.size} 曲のダウンロードを始めました")
                }
                .show()
        }
    }

    // ───────── URL からすぐ追加 ─────────

    fun isYouTubeUrl(u: String): Boolean {
        val l = u.lowercase()
        return (l.startsWith("http://") || l.startsWith("https://")) && (l.contains("youtube.com/") || l.contains("youtu.be/"))
    }

    /**
     * URL が分かっている時のワンタップ追加。
     * 1 曲 → すぐダウンロード(取り消し付き) / アルバム・プレイリスト → 選択肢を出してすぐ開始
     */
    fun quickAdd(raw: String) {
        val url = Regex("https?://\\S+").find(raw)?.value ?: raw.trim()
        if (!isYouTubeUrl(url)) { openAddSheet(url); return }
        ensureNotificationPermission()
        if (DownloadManager.hasListParam(url)) {
            val hasVideo = youtubeVideoId(url) != null
            val items = mutableListOf<Pair<String, () -> Unit>>()
            items += "追加してプレイリスト作成" to {
                DownloadManager.add(this, url, wholeList = true, makePlaylist = true)
                toast("アルバム / プレイリストを取得しています…")
            }
            items += "曲だけ追加(全曲)" to {
                DownloadManager.add(this, url, wholeList = true, makePlaylist = false)
                toast("アルバム / プレイリストを取得しています…")
            }
            if (hasVideo) items += "この曲だけ" to { addSingle(url) }
            MaterialAlertDialogBuilder(this)
                .setTitle("アルバム / プレイリストの URL です")
                .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
                .setNegativeButton("キャンセル", null)
                .show()
        } else addSingle(url)
    }

    private fun addSingle(url: String) {
        val vid = youtubeVideoId(url)
        VideoAlias.trackFor(vid, includeMissing = true)?.let { t ->
            if (Repository.isOnline(t.id)) { DownloadManager.redownload(this, listOf(t)); toast("ダウンロードします") }
            else toast("ダウンロード済みです")
            return
        }
        val active = { j: DownloadJob -> j.status == JobStatus.QUEUED || j.status == JobStatus.RUNNING }
        if (vid != null && DownloadManager.jobs.value.any { active(it) && youtubeVideoId(it.url) == vid }) { toast("ダウンロード中です"); return }
        DownloadManager.add(this, url, wholeList = false, makePlaylist = false)
        val anchor = findViewById<View>(R.id.miniPlayer)?.takeIf { it.visibility == View.VISIBLE } ?: nav
        com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.container), "ダウンロードを開始しました", 4000)
            .setAnchorView(anchor)
            .setAction("取り消し") {
                DownloadManager.jobs.value.filter { active(it) && (vid == null || youtubeVideoId(it.url) == vid) }
                    .takeLast(1).forEach { DownloadManager.cancel(it.id); DownloadManager.remove(it.id) }
                toast("取り消しました")
            }
            .show()
    }

    fun openAddSheet(url: String?) {
        if (supportFragmentManager.findFragmentByTag("add") != null) return
        AddSheet.newInstance(url).show(supportFragmentManager, "add")
    }

    // ───────── settings ─────────

    /** 保存先: フォルダを選ぶ / 既定に戻す */
    fun showLocationDialog() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "フォルダを選ぶ…" to {
            runCatching { pickFolder.launch(saveLocation.treeUri) }.onFailure { toast("フォルダ選択を開けません") }
        }
        if (!saveLocation.isDefault) items += "既定(ダウンロード)に戻す" to {
            saveLocation.resetToDefault()
            downloads().refreshLocation()
            toast("保存先をダウンロードに戻しました")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("保存先: ${saveLocation.label()}")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("閉じる", null)
            .show()
    }

    fun setOrganize(on: Boolean) {
        saveLocation.organize = on
        downloads().refreshLocation()
    }

    /** 形式の切り替え(高速 M4A ⇄ MP3) */
    fun toggleFormat() {
        if (saveLocation.fastMode) {
            MaterialAlertDialogBuilder(this)
                .setTitle("MP3 で保存")
                .setMessage("ダウンロード後に MP3 へ変換します(1 曲あたり数十秒〜数分かかります)。\n" +
                    "他の機器(カーナビ等)でも再生したい場合に選んでください。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("MP3 にする") { _, _ ->
                    saveLocation.fastMode = false
                    downloads().refreshLocation()
                    toast("MP3 で保存します")
                }
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("高速モード(M4A)")
                .setMessage("MP3 に変換せず、YouTube の音声(AAC)をそのまま .m4a で保存します。\n\n" +
                    "・ダウンロード後の変換がなく、すぐ保存されます\n" +
                    "・再変換しないので音質も少し良くなります\n" +
                    "・このアプリでの再生・ウィジェット・アラームはそのまま使えます\n" +
                    "・古いカーナビ等 MP3 しか再生できない機器では再生できないことがあります")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("高速モードにする") { _, _ ->
                    saveLocation.fastMode = true
                    downloads().refreshLocation()
                    toast("高速モード(M4A)で保存します")
                }
                .show()
        }
    }

    /** このアプリを人に教える: 最新版をダウンロードできるリンクを共有 */
    fun shareApp() {
        val text = "MusicPlayer(Android アプリ)\n" +
            "このリンクから最新版をダウンロードしてインストールできます:\n${AppUpdater.SHARE_URL}\n\n" +
            "※ インストールの時に「この提供元のアプリを許可」をオンにしてください。\n" +
            "※ 入れた後は、アプリの「設定 → アプリを更新」で更新できます。"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "MusicPlayer")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, "MusicPlayer を共有")) }
    }

    fun showAbout() {
        val v = packageManager.getPackageInfo(packageName, 0).versionName
        MaterialAlertDialogBuilder(this)
            .setTitle("${getString(R.string.app_name)}")
            .setMessage("現在: v$v(yt-dlp ${runCatching { DownloadManager.engineVersion() }.getOrDefault("?")} · NewPipe ${NewPipeSource.version})\n\n" +
                "好きな曲を集めて、いつでもどこでも。\n\n" +
                "🔎 YouTube・YouTube Music から探して、試聴してから保存\n" +
                "📻 ラジオ・おすすめの曲・ランキングで新しい曲に出会う\n" +
                "🎵 歌詞・イコライザー・曲間のフェード\n" +
                "⏰ おやすみタイマーと音楽アラーム\n" +
                "🚗 Android Auto と Bluetooth 接続時の自動再生\n" +
                "☁ 毎日の自動バックアップとアプリ内アップデート")
            .setNegativeButton("共有") { _, _ -> shareApp() }
            .setNeutralButton("変更履歴") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("変更履歴")
                    .setMessage(Changelog.format(Changelog.all(this).take(8)).ifBlank { "ありません" })
                    .setPositiveButton("OK", null)
                    .show()
            }
            .setPositiveButton("OK", null)
            .show()
    }

    fun confirmReorganize() {
        val targets = Repository.tracks.value.filter {
            it.source == Track.SOURCE_DOWNLOAD && it.uri.startsWith("content://media/") &&
                it.folder.startsWith("Download/") && !Repository.isMissing(it.id)
        }
        if (targets.isEmpty()) {
            toast("整理できる曲がありません"); return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("保存済みの曲をフォルダ分け")
            .setMessage("ダウンロードフォルダに保存した ${targets.size} 曲を\n" +
                "Download/${SaveLocation.APP_DIR}/アーティスト/アルバム/ に移動します。\n\n" +
                "(フォルダを選んで保存した曲や、フォルダから読み込んだ曲は移動しません)")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("移動") { _, _ ->
                lifecycleScope.launch {
                    val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        saveLocation.reorganizeExisting(targets, DownloadManager::safeName)
                    }
                    toast(if (n > 0) "$n 曲を移動しました" else "移動が必要な曲はありませんでした")
                }
            }
            .show()
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 削除した曲を再生キューからも外す */
    private fun removeFromQueue(ids: Set<Long>) {
        val c = player.controller ?: return
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId.toLongOrNull() in ids) c.removeMediaItem(i)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_TAB = "tab"
        /** ダウンロードの一覧を開く(通知から) */
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        /** 「さがす」でこの言葉を検索する */
        const val EXTRA_SEARCH = "search_query"
        const val TAB_LIBRARY = 0
        const val TAB_PLAYLISTS = 1
        const val TAB_DOWNLOADS = 2
        const val TAB_SEARCH = 3
        private val TAGS = arrayOf("library", "playlists", "downloads", "search")
        private const val HOME_TAG = "home"
        const val ACTION_SHORTCUT = "com.example.ytmp3.SHORTCUT"
    }
}

package com.example.ytmp3

import android.os.Bundle
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 再生画面 */
class NowPlayingActivity : AppCompatActivity(), Player.Listener {
    private lateinit var connection: PlayerConnection
    private var controller: MediaController? = null
    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var seek: Slider
    private lateinit var pos: TextView
    private lateinit var dur: TextView
    private lateinit var playPause: FloatingActionButton
    private lateinit var shuffle: ImageButton
    private lateinit var repeat: ImageButton
    private lateinit var prev: ImageButton
    private lateinit var next: ImageButton
    private var seeking = false
    private lateinit var content: View

    // ───────── 歌詞 ─────────
    private lateinit var lyricsScroll: androidx.core.widget.NestedScrollView
    private lateinit var lyricsBox: android.widget.LinearLayout
    private var lyricsItem: android.view.MenuItem? = null
    private var showLyrics = false
    /** 曲ごとの歌詞の有無(true = ある / false = 無い / 無し = まだ分からない) */
    private val lyricsAvail = HashMap<String, Boolean>()
    /** 実際に歌詞を出しているか(歌詞が無い曲では出さない) */
    private val lyricsShown get() = showLyrics && controller?.currentMediaItem?.mediaId?.let { lyricsAvail[it] } != false
    private var lyricsFor: String? = null
    private var lyricLines: List<Lyrics.Line> = emptyList()
    private var lineViews: List<TextView> = emptyList()
    private var currentLine = -1
    private var lyricsJob: kotlinx.coroutines.Job? = null

    // ───────── 下にスワイプで閉じる ─────────
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var upSwipe = false
    private var tracker: VelocityTracker? = null
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    /** 下に滑らせて閉じている途中(触っても止めない・必ず閉じきる) */
    private var closing = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        if (fullscreen) return fullscreenTouch(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; dragging = false
                // シークバー上の操作はスワイプ扱いしない
                val r = android.graphics.Rect()
                seek.getGlobalVisibleRect(r)
                tracker?.recycle()
                val lr = android.graphics.Rect()
                val inLyrics = showLyrics && lyricsScroll.getGlobalVisibleRect(lr) &&
                    lr.contains(ev.rawX.toInt(), ev.rawY.toInt()) && lyricsScroll.canScrollVertically(-1)
                tracker = if (r.contains(ev.rawX.toInt(), ev.rawY.toInt()) || inLyrics) null else VelocityTracker.obtain()
                // 上スワイプ(再生キュー)はシークバーと歌詞の上では使わない
                val anyLyrics = showLyrics && lyricsScroll.getGlobalVisibleRect(lr) && lr.contains(ev.rawX.toInt(), ev.rawY.toInt())
                upSwipe = !r.contains(ev.rawX.toInt(), ev.rawY.toInt()) && !anyLyrics
                tracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                val t = tracker
                if (t != null) {
                    t.addMovement(ev)
                    val dy = ev.rawY - downY
                    val dx = ev.rawX - downX
                    if (!dragging && dy > touchSlop * 2 && dy > kotlin.math.abs(dx) * 1.5f) {
                        dragging = true
                        // 動かす間は 1 枚の絵として描く(半透明・移動でもカクつかないように)。
                        // 動画を映している時はしない(動き続ける映像と重ねると、前の絵が跡のように残る端末がある)
                        content.setLayerType(dragLayer(), null)
                        // 子ビューのタップ・押下をキャンセル
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dragging) {
                        content.translationY = (dy - touchSlop * 2).coerceAtLeast(0f)
                        content.alpha = 1f - (content.translationY / content.height).coerceIn(0f, 0.3f)
                        // 窓ぜんぶを描き直す(動かした跡が残らないように)
                        window.decorView.invalidate()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = tracker
                if (t != null && dragging) {
                    t.addMovement(ev)
                    t.computeCurrentVelocity(1000)
                    val dy = ev.rawY - downY
                    val vy = t.yVelocity
                    val fling = vy > 1200
                    t.recycle(); tracker = null; dragging = false
                    if (ev.actionMasked == MotionEvent.ACTION_UP && (dy > content.height * 0.25f || fling)) {
                        slideAwayAndClose(vy)
                    } else {
                        content.animate().translationY(0f).alpha(1f).setDuration(200)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .setUpdateListener { window.decorView.invalidate() }
                            .withEndAction { content.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
                    }
                    return true
                }
                tracker?.recycle(); tracker = null
                // 上にスワイプ → 再生キュー
                if (upSwipe && ev.actionMasked == MotionEvent.ACTION_UP) {
                    upSwipe = false
                    val dy = ev.rawY - downY
                    val dx = ev.rawX - downX
                    // スワイプだった時は、子ビューのタップ(ジャケットの一時停止など)を取り消す
                    val swiped = kotlin.math.abs(dx) > touchSlop * 3 || kotlin.math.abs(dy) > touchSlop * 3
                    if (swiped) {
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dy < -content.height * 0.12f && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f) {
                        QueueSheet.show(this)
                        return true
                    }
                    // 左右にスワイプ → 次の曲 / 前の曲
                    if (kotlin.math.abs(dx) > content.width * 0.2f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        val c = controller
                        if (c != null) {
                            val toNext = dx < 0
                            if (toNext && !c.hasNextMediaItem()) return true
                            // ジャケットを流れる向きに少し動かして分かりやすく
                            val w = art.width.toFloat()
                            art.animate().translationX(if (toNext) -w * 0.3f else w * 0.3f).alpha(0f).setDuration(120).withEndAction {
                                if (toNext) c.skipNext() else { c.seekToPreviousMediaItem(); if (c.playbackState == Player.STATE_IDLE) c.prepare() }
                                art.translationX = if (toNext) w * 0.3f else -w * 0.3f
                                art.animate().translationX(0f).alpha(if (lyricsShown) 0.12f else 1f).setDuration(160).start()
                            }.start()
                        }
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 全画面の時: 下にスワイプで縦向きに戻す(それ以外のタップは動画の操作へ) */
    private var fsDragging = false
    private fun fullscreenTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; fsDragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                if (!fsDragging && dy > touchSlop * 3 && dy > kotlin.math.abs(ev.rawX - downX) * 1.5f) {
                    fsDragging = true
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (fsDragging) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (fsDragging) {
                fsDragging = false
                if (ev.actionMasked == MotionEvent.ACTION_UP && ev.rawY - downY > content.height * 0.2f) setFixed(false)
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 動かす間の描き方: 動画を映している時は 1 枚の絵にまとめない(跡が残らないように) */
    private fun dragLayer(): Int =
        if (npVideo.visibility == View.VISIBLE) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE

    /** 指を離した所から、そのまま下へ滑らせて閉じる(速く払ったほど速く) */
    private fun slideAwayAndClose(velocity: Float) {
        val h = content.height.toFloat().coerceAtLeast(1f)
        val left = (h - content.translationY).coerceAtLeast(0f)
        val speed = velocity.coerceAtLeast(2500f) // 1 秒あたりの画素
        val ms = (left / speed * 1000).toLong().coerceIn(120, 260)
        closing = true
        content.setLayerType(dragLayer(), null)
        // 画面はもう下に滑り終えているので、窓の動き(上からもう一度滑る)は付けない
        val done = Runnable { leave(animate = false) }
        content.animate().translationY(h).alpha(0.7f).setDuration(ms)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.2f))
            .setUpdateListener { window.decorView.invalidate() }
            .withEndAction(done).start()
        // アニメーションが途中で止められても、閉じ残らないように
        content.postDelayed(done, ms + 250)
    }

    /** 前の画面に戻る(開いた元の画面へ。アプリの画面が下に無ければホームを開く) */
    private fun closeToHome() = leave(animate = true)

    @Suppress("DEPRECATION")
    private fun leave(animate: Boolean) {
        if (isFinishing) return
        closing = true
        if (isTaskRoot) startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
        if (animate) overridePendingTransition(R.anim.stay, R.anim.slide_down) else overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 再生中画面は 1 枚だけ(半透明なので、重なると下の画面が透けてぎこちなく見える)。前のものは閉じる
        live?.get()?.takeIf { it !== this && !it.isFinishing }?.let { old ->
            old.finish(); old.overridePendingTransition(0, 0)
        }
        live = java.lang.ref.WeakReference(this)
        setContentView(R.layout.activity_now_playing)
        setBackdrop(null)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { closeToHome() }
        findViewById<View>(R.id.actLyrics).setOnClickListener { toggleLyrics() }
        findViewById<View>(R.id.actQueue).setOnClickListener { QueueSheet.show(this) }
        findViewById<View>(R.id.actSleep).setOnClickListener { SleepTimerSheet.show(this) }
        // 上の「○○から」: タップでそのプレイリストを開く
        findViewById<View>(R.id.npSource).setOnClickListener {
            val ref = PlayLog.current ?: return@setOnClickListener
            if (ref.kind == PlayLog.Kind.PLAYLIST) ref.key.toLongOrNull()?.let { id ->
                startActivity(android.content.Intent(this, PlaylistDetailActivity::class.java).putExtra(PlaylistDetailActivity.EXTRA_ID, id))
            }
        }
        toolbar.menu.add("再生キュー").apply {
            setIcon(R.drawable.ic_queue_music)
            // 上スワイプと画面下の「次:」からも開けるので、⋮ の中へ
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener { QueueSheet.show(this@NowPlayingActivity); true }
        }
        // 保存の状態ボタン(いつも表示): 押すと今の状態と、できること(ダウンロード・動画・削除)を出す
        findViewById<View>(R.id.downloadBtn).setOnClickListener { storagePanel() }
        // ラジオ: タップで小さなパネル(オン・オフ / この曲から作り直す)
        findViewById<View>(R.id.actRadio).setOnClickListener { radioPanel() }
        // ラジオのボタンを長押し: 今の曲から作り直す
        findViewById<View>(R.id.actRadio).setOnLongClickListener { rebuildRadio(); true }
        // 👎 No Good: 押すと「この曲だけ / このアーティストごと」を選んでおすすめ・ラジオに出さない。もう一度押すと解除
        findViewById<View>(R.id.noGoodBtn).setOnClickListener {
            val item = controller?.currentMediaItem ?: return@setOnClickListener
            val r = Liked.fromItem(item) ?: return@setOnClickListener
            val a = Radio.mainArtist(r.artist)
            val ngSong = NoGood.isSong(r.videoId)
            val ngArtist = a.isNotBlank() && NoGood.isArtist(a)
            if (ngSong || ngArtist) {
                if (ngSong) r.videoId?.let { NoGood.removeSongs(setOf(it)) }
                if (ngArtist) NoGood.removeArtists(NoGood.artists.value.filter { LibraryMatch.artistKey(it.name) == LibraryMatch.artistKey(a) || it.key == a }.map { it.key }.toSet())
                android.widget.Toast.makeText(this, "No Good を解除しました", android.widget.Toast.LENGTH_SHORT).show()
                refresh(); return@setOnClickListener
            }
            val opts = mutableListOf("この曲はもう聴かない")
            if (a.isNotBlank()) opts += "「$a」はもう聴かない"
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                // 説明文(setMessage)と選択肢(setItems)を一緒に出すと選択肢が消えるので、説明は題名に入れる
                .setTitle("No Good(選ぶとすぐ次の曲へ)")
                .setItems(opts.toTypedArray()) { _, i ->
                    if (i == 0) {
                        NoGood.addSong(r); r.videoId?.let { DailyDiscover.drop(it) }
                        android.widget.Toast.makeText(this, "この曲はもう聴かない設定にしました。ミックス・ラジオ・おすすめに出なくなります(もう一度 👎 で解除)", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        NoGoodUi.setArtist(this, a)
                        dropUpcoming(a)
                    }
                    // 聴きたくない曲がそのまま流れ続けないよう、すぐ次へ
                    controller?.let { c -> if (c.hasNextMediaItem()) c.seekToNextMediaItem() else c.pause() }
                    refresh()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
        findViewById<View>(R.id.likeBtn).setOnClickListener {
            controller?.currentMediaItem?.let { Liked.fromItem(it) }?.let { r ->
                val added = Liked.toggle(r)
                android.widget.Toast.makeText(this@NowPlayingActivity,
                    if (added) "「お気に入り」に追加しました" else "「お気に入り」から外しました", android.widget.Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        videoMenuItem = toolbar.menu.add("動画で見る").apply {
            isVisible = false
            setOnMenuItemClickListener {
                findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.avToggle).check(R.id.avVideo); true
            }
        }
        saveVideoItem = toolbar.menu.add("動画を保存").apply {
            isVisible = false
            setOnMenuItemClickListener {
                val item = controller?.currentMediaItem ?: return@setOnMenuItemClickListener true
                val t = trackOf(item)
                val vid = Liked.vidOf(item) ?: return@setOnMenuItemClickListener true
                if (t != null) DownloadManager.addVideos(this@NowPlayingActivity, listOf(t))
                else DownloadManager.addVideoForUrl(this@NowPlayingActivity, "https://www.youtube.com/watch?v=$vid",
                    item.mediaMetadata.title?.toString().orEmpty())
                android.widget.Toast.makeText(this@NowPlayingActivity,
                    if (VideoPrefs.wifiOnly(this@NowPlayingActivity) && Net.metered) "Wi-Fi につながったら動画を保存します" else "動画を保存します",
                    android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }
        youtubeItem = toolbar.menu.add("YouTube で開く").apply {
            isVisible = false
            setOnMenuItemClickListener {
                val vid = Liked.vidOf(controller?.currentMediaItem) ?: return@setOnMenuItemClickListener true
                controller?.pause()
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.youtube.com/watch?v=$vid")))
                }
                true
            }
        }
        toolbar.menu.add("この曲からラジオを作り直す").setOnMenuItemClickListener { rebuildRadio(); true }
        listItem = toolbar.menu.add("アルバムごと追加").apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
            isVisible = false
            setOnMenuItemClickListener {
                StreamDownload.listFor(controller?.currentMediaItem)?.let { StreamDownload.addListDialog(this@NowPlayingActivity, it) }
                true
            }
        }
        // 共有(よくある矢印のボタン): アプリを使う人どうしの共有リンク
        toolbar.menu.add("共有").apply {
            setIcon(R.drawable.ic_share)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                val item = controller?.currentMediaItem ?: return@setOnMenuItemClickListener true
                val vid = StreamDownload.videoId(item) ?: trackOf(item)?.sourceUrl?.let { youtubeVideoId(it) }
                val m = item.mediaMetadata
                val title = m.title?.toString().orEmpty()
                if (vid == null) toast("端末に入れた曲は共有できません")
                else trackActions.shareIds(title, Share.Kind.SONG,
                    listOf(vid to listOf(title, m.artist?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" - ")))
                true
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(Repository.tracks, DownloadManager.jobs) { _, _ -> }.collect { refresh() }
            }
        }
        val sleepLabel = findViewById<TextView>(R.id.actSleepLabel)
        val sleepIcon = findViewById<android.widget.ImageView>(R.id.actSleepIcon)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val st = SleepTimer.state.value
                    sleepLabel.text = when (st) {
                        is SleepTimer.State.At -> "あと ${formatTime(SleepTimer.remainingMs())}"
                        SleepTimer.State.EndOfTrack -> "曲の終わり"
                        else -> "タイマー"
                    }
                    setActive(sleepIcon, sleepLabel, st !is SleepTimer.State.Off)
                    delay(1000)
                }
            }
        }
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_up, R.anim.stay)
        content = findViewById(android.R.id.content)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            // 全画面の時は、まず縦向きに戻す
            override fun handleOnBackPressed() = if (fullscreen) setFixed(false) else closeToHome()
        })
        art = findViewById(R.id.art)
        // ジャケットをタップで一時停止 / 再生(歌詞の行のタップはその行へ移動)
        art.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.togglePlay()
            // 押した感じを少しだけ
            art.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction {
                art.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
        }
        lyricsScroll = findViewById(R.id.lyricsScroll)
        lyricsBox = findViewById(R.id.lyricsBox)
        showLyrics = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("show_lyrics", false)
        applyLyricsVisibility()
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        album = findViewById(R.id.album)
        // アーティスト名をタップ → そのアーティストを検索 / 曲名をタップ → その曲を検索
        fun mainArtistName(): String? = controller?.currentMediaItem?.mediaMetadata?.artist?.toString()
            ?.let { Radio.mainArtist(it) }?.takeIf { it.isNotBlank() && it != "不明なアーティスト" && it != "YouTube" }
        fun openSearch(q: String) {
            startActivity(
                android.content.Intent(this, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_SEARCH, q)
            )
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.stay, R.anim.slide_down)
        }
        artist.setOnClickListener { mainArtistName()?.let { openSearch(it) } }
        title.setOnClickListener {
            val t = controller?.currentMediaItem?.mediaMetadata?.title?.toString()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            // 曲名だけだと別の曲が出やすいので、アーティストも添える
            openSearch(listOfNotNull(t, mainArtistName()).joinToString(" "))
        }
        val ripple = android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        album.setOnClickListener {
            val a = albumShown.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            openSearch(listOfNotNull(a, mainArtistName()).joinToString(" "))
        }
        artist.background = androidx.core.content.ContextCompat.getDrawable(this, ripple)
        album.background = androidx.core.content.ContextCompat.getDrawable(this, ripple)
        title.background = androidx.core.content.ContextCompat.getDrawable(this, ripple)
        seek = findViewById(R.id.seek)
        pos = findViewById(R.id.pos)
        dur = findViewById(R.id.dur)
        playPause = findViewById(R.id.playPause)
        shuffle = findViewById(R.id.shuffle)
        repeat = findViewById(R.id.repeat)
        prev = findViewById(R.id.prev)
        next = findViewById(R.id.next)
        findViewById<View>(R.id.upNext).setOnClickListener { QueueSheet.show(this) }

        connection = PlayerConnection(this) { c ->
            controller = c
            c.addListener(this)
            // 他の画面(検索で動画を開いた時など)で変わっていたら合わせる
            c.currentMediaItem?.let { syncToggle(it) }
            applyVideoMode()
            refresh()
        }
        setupVideoToggle()
        setupRotation()

        playPause.setOnClickListener {
            controller?.togglePlay()
        }
        prev.setOnClickListener { controller?.skipPrevious() }
        next.setOnClickListener { controller?.skipNext() }
        shuffle.setOnClickListener { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        repeat.setOnClickListener {
            controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        seek.addOnChangeListener { _, value, fromUser -> if (fromUser) pos.text = formatTime(value.toLong()) }
        seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { seeking = true }
            override fun onStopTrackingTouch(slider: Slider) {
                seeking = false
                controller?.seekTo(slider.value.toLong())
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updatePosition()
                    delay(500)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.connect()
    }

    override fun onStop() {
        npVideo.player = null
        fsVideo.player = null
        VideoGate.release(gateKey, controller)
        controller?.removeListener(this)
        controller = null
        connection.release()
        super.onStop()
    }

    override fun onEvents(player: Player, events: Player.Events) = refresh()

    private var downloadItem: android.view.MenuItem? = null
    /** ジャケットを表示中の曲(同じ曲で何度も読み込まないように) */
    private var artFor: String? = null
    private var artJob: kotlinx.coroutines.Job? = null

    /**
     * 曲が変わった時のジャケット。仮の絵(プレースホルダ)は挟まずに、前の絵から新しい絵へふわっと切り替える。
     * 手元の小さい絵(すぐ出る) → ネットの絵 → 高画質、の順に、取れたものから差し替え。背景の色は最初に取れた絵から 1 回だけ
     */
    private fun loadArt(item: androidx.media3.common.MediaItem) {
        artJob?.cancel()
        val id = item.mediaId
        val uri = item.mediaMetadata.artworkUri?.toString()
        val local = item.mediaId.toLongOrNull()?.let { Repository.track(it) }?.thumbPath
            ?.takeIf { java.io.File(it).exists() }?.let { Thumbs.load(it) }
        if (local != null) showArt(local)
        artJob = lifecycleScope.launch {
            var first = local
            if (first == null && uri != null && uri.startsWith("http")) {
                first = runCatching {
                    val req = coil.request.ImageRequest.Builder(this@NowPlayingActivity).data(uri).allowHardware(false).build()
                    (coil.Coil.imageLoader(this@NowPlayingActivity).execute(req).drawable)?.toBitmap()
                }.getOrNull()
                if (artFor != id) return@launch
                if (first != null) showArt(first)
            }
            val hi = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { HiArt.load(this@NowPlayingActivity, id, uri) }.getOrNull()
            }
            if (artFor != id) return@launch
            if (hi != null) showArt(hi)
            else if (first == null) { art.setImageResource(R.drawable.art_placeholder); syncVideoCover() }
            // 背景をジャケットの色に(本物の絵が取れた時だけ。仮の絵の色にはしない)
            if (!Skin.artColors(this@NowPlayingActivity)) return@launch
            val src = (first ?: hi)?.let { b -> runCatching { android.graphics.Bitmap.createScaledBitmap(b, 96, 96, true) }.getOrNull() }
                ?: return@launch
            val night = Skin.isNight(this@NowPlayingActivity)
            val accent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val p = androidx.palette.graphics.Palette.from(src).maximumColorCount(16).generate()
                    (if (night) p.vibrantSwatch ?: p.darkVibrantSwatch else p.vibrantSwatch ?: p.lightVibrantSwatch)
                        ?.rgb ?: p.dominantSwatch?.rgb
                }.getOrNull()
            }
            if (artFor == id && accent != null) setBackdrop(accent)
        }
    }

    /** ジャケットを差し替える(前の絵から短くクロスフェード) */
    private fun showArt(bmp: android.graphics.Bitmap) {
        val next = android.graphics.drawable.BitmapDrawable(resources, bmp)
        val old = art.drawable?.let { (it as? android.graphics.drawable.TransitionDrawable)?.getDrawable(1) ?: it }
        if (old == null) art.setImageDrawable(next)
        else {
            val td = android.graphics.drawable.TransitionDrawable(arrayOf(old, next)).apply { isCrossFadeEnabled = true }
            art.setImageDrawable(td)
            td.startTransition(180)
            // 切り替え後は普通の絵に戻す(縦横比の違う絵が引き伸ばされたままにならないように)
            art.postDelayed({ if (art.drawable === td) { art.setImageDrawable(next); syncVideoCover() } }, 220)
        }
        syncVideoCover()
    }

    /** 画面の背景。accent があればジャケットの色のグラデーション、無ければスキンの背景 */
    private fun setBackdrop(accent: Int?) {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) ?: return
        val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
        val skin = Skin.current(this)
        val skinBg = if (accent == null) Skin.backgroundDrawable(this) else null
        val next: android.graphics.drawable.Drawable = if (skinBg != null) {
            skinBg
        } else {
            val night = Skin.isNight(this)
            val top = if (accent != null) androidx.core.graphics.ColorUtils.blendARGB(surface, accent, if (night) 0.5f else 0.38f)
                else androidx.core.graphics.ColorUtils.blendARGB(surface,
                    MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary), 0.2f)
            android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(top, androidx.core.graphics.ColorUtils.blendARGB(surface, top, 0.4f), surface))
        }
        val old = root.background
        if (old == null || accent == null) { root.background = next; return }
        // ふわっと切り替え
        // 前の色は濃いまま残し、その上に新しい色を重ねていく(両方を薄くすると途中で背景が透けて、後ろの画面が見えるため)
        val prev = (old as? android.graphics.drawable.TransitionDrawable)?.getDrawable(1) ?: old
        val td = android.graphics.drawable.TransitionDrawable(arrayOf(prev, next)).apply { isCrossFadeEnabled = false }
        root.background = td
        td.startTransition(450)
    }
    private var listItem: android.view.MenuItem? = null
    private var videoMenuItem: android.view.MenuItem? = null
    private var youtubeItem: android.view.MenuItem? = null
    private var saveVideoItem: android.view.MenuItem? = null
    private var checkingVid: String? = null
    private var likeItem: android.view.MenuItem? = null
    private var autoItem: android.view.MenuItem? = null

    // ───────── 保存の状態(☁ オンライン / ✓ 端末にある) ─────────

    private enum class Storage { NOT_IN_LIBRARY, ONLINE, DOWNLOADING, ON_DEVICE }
    // 画面ができる前に作る(結果を受け取る登録は、画面が表示された後だとアプリが落ちる)
    private val trackActions = TrackActions(this)

    /** 今の曲の保存の状態 */
    private fun storageOf(item: androidx.media3.common.MediaItem): Storage {
        val t = trackOf(item)
        val vid = t?.sourceUrl?.let { youtubeVideoId(it) } ?: StreamDownload.videoId(item)
        val busy = (t != null && DownloadManager.progressFor(t) != null) ||
            (vid != null && DownloadManager.jobs.value.any {
                (it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED) && youtubeVideoId(it.url) == vid
            })
        return when {
            busy -> Storage.DOWNLOADING
            t == null -> Storage.NOT_IN_LIBRARY
            Repository.isOnline(t.id) -> Storage.ONLINE
            else -> Storage.ON_DEVICE
        }
    }

    /** 保存ボタンのパネル: 今の状態の説明と、できることだけを並べる */
    private fun storagePanel() {
        val item = controller?.currentMediaItem ?: return
        val st = storageOf(item)
        val t = trackOf(item)
        val vid = t?.sourceUrl?.let { youtubeVideoId(it) } ?: StreamDownload.videoId(item)
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val dlg = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
        }
        fun label(text: String, style: Int, color: Int? = null) = TextView(this).apply {
            this.text = text
            setTextAppearance(style)
            color?.let { setTextColor(it) }
            setPadding(pad, 0, pad, (2 * dp).toInt())
        }
        val sub = MaterialColors.getColor(box, com.google.android.material.R.attr.colorOnSurfaceVariant)
        // 今の状態
        val (head, note) = when (st) {
            Storage.NOT_IN_LIBRARY -> "ライブラリにない曲" to "試聴中です。追加すると、あとで一覧から聴けます"
            Storage.ONLINE -> "☁ オンライン" to "ネットから再生しています(端末の容量は使いません)"
            Storage.DOWNLOADING -> "⬇ ダウンロード中" to (t?.let { DownloadManager.progressFor(it) } ?: "準備中")
            Storage.ON_DEVICE -> "✓ ダウンロード済み" to
                ("端末に保存されています" + if (t?.hasVideo == true) "(🎬 動画も保存済み)" else "")
        }
        box.addView(label(head, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium))
        box.addView(label(note, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, sub)
            .apply { setPadding(pad, 0, pad, (12 * dp).toInt()) })
        // できること(1 行 = 見出し + 説明)
        fun option(text: String, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, (12 * dp).toInt(), pad, (12 * dp).toInt())
                minimumHeight = (56 * dp).toInt()
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.45f
                if (enabled) setOnClickListener { dlg.dismiss(); onClick(); refresh() }
            }
            row.addView(TextView(this).apply {
                this.text = text
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            })
            row.addView(TextView(this).apply {
                this.text = desc
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(sub)
            })
            box.addView(row)
        }
        val noVideo = vid != null && VideoCheck.known(vid) == false
        val videoDesc = if (noVideo) "この曲には動画がありません(ジャケットの静止画だけ)" else "🎬 動画も端末に保存します(容量を多く使います)"
        when (st) {
            Storage.NOT_IN_LIBRARY -> {
                val r = Liked.fromItem(item)?.copy(durationSec = (controller?.duration ?: 0L).coerceAtLeast(0L) / 1000)
                option("☁ ライブラリに追加(オンライン)", "容量を使わずに追加。聴く時はネットから再生します", r != null) {
                    r?.let { OnlineLibrary.add(it) }?.let { toast("☁「${it.title}」をライブラリに追加しました") }
                }
                option("⬇ 音声をダウンロード", "端末に保存して、オフラインでも聴けるように", vid != null) {
                    vid?.let { DownloadManager.add(this, "https://www.youtube.com/watch?v=$it", wholeList = false, makePlaylist = false) }
                    toast("「$title」をダウンロードします")
                }
                option("⬇ 音声と動画をダウンロード", videoDesc, vid != null && !noVideo) {
                    vid?.let { DownloadManager.addVideoForUrl(this, "https://www.youtube.com/watch?v=$it", title) }
                    toast("「$title」を動画もいっしょにダウンロードします")
                }
            }
            Storage.ONLINE -> t?.let { tr ->
                option("⬇ 音声をダウンロード", "端末に保存して、オフラインでも聴けるように") {
                    DownloadManager.redownload(this, listOf(tr)); toast("「${tr.title}」をダウンロードします")
                }
                option("⬇ 音声と動画をダウンロード", videoDesc, !noVideo && vid != null) {
                    DownloadManager.redownload(this, listOf(tr)); DownloadManager.addVideos(this, listOf(tr))
                    toast("「${tr.title}」を動画もいっしょにダウンロードします")
                }
            }
            Storage.DOWNLOADING ->
                option("ダウンロードの一覧を見る", "進み具合や、うまくいかない時の詳しい内容") { DownloadsSheet.show(this) }
            Storage.ON_DEVICE -> t?.let { tr ->
                if (tr.hasVideo) option("🎬 動画だけ削除", "音声は残ります(${StorageSheet.size(tr.videoBytes)} 空きます)") {
                    trackActions.removeVideos(listOf(tr))
                } else if (vid != null) option("🎬 動画もダウンロード", videoDesc, !noVideo) { trackActions.saveVideos(listOf(tr)) }
                if (vid != null) option("☁ ダウンロードを削除(オンライン再生に戻す)", "ファイルを消して容量を空けます。曲はライブラリに残り、ネットから聴けます") {
                    trackActions.removeDownload(listOf(tr))
                } else option("☁ オンライン再生には戻せません", "端末に入れた曲です(ネットに元がありません)", enabled = false) {}
            }
        }
        dlg.setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(box) })
        dlg.show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    /** ラジオのパネル: オン・オフのスイッチと「この曲から作り直す」 */
    private fun radioPanel() {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val dlg = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (16 * dp).toInt(), pad, (24 * dp).toInt())
        }
        fun text(t: String, style: Int, top: Int = 0) = TextView(this).apply {
            text = t
            setTextAppearance(style)
            setPadding(0, (top * dp).toInt(), 0, 0)
        }
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "ラジオ（似た曲の自動再生）"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            isChecked = AutoContinue.isOn(this@NowPlayingActivity)
            minHeight = (48 * dp).toInt()
        }
        box.addView(sw)
        box.addView(text("キューが終わっても、似た曲が続きます",
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall))
        val c0 = controller
        val repeatNote = text("いまはリピート中なので、ラジオは効いていません。オンにするとリピートを切ります",
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 6).apply {
            setTextColor(MaterialColors.getColor(box, androidx.appcompat.R.attr.colorError))
            visibility = if (sw.isChecked && c0 != null && c0.repeatMode != Player.REPEAT_MODE_OFF) View.VISIBLE else View.GONE
        }
        box.addView(repeatNote)
        sw.setOnCheckedChangeListener { _, on -> setRadio(on); repeatNote.visibility = View.GONE }
        // 既にオンでリピート中: スイッチはオンのまま。押し直さなくても下の説明を押せばリピートを切れる
        repeatNote.setOnClickListener { setRadio(true); repeatNote.visibility = View.GONE }
        box.addView(com.google.android.material.button.MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "この曲から作り直す"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (20 * dp).toInt() }
            setOnClickListener { dlg.dismiss(); rebuildRadio() }
        })
        box.addView(text("次からの曲を、今の曲に似た曲で入れ替えます",
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 4))
        dlg.setContentView(box)
        dlg.show()
    }

    /** ラジオのオン・オフ(オンにしたらリピートを切って、必要ならすぐ似た曲を足す) */
    private fun setRadio(on: Boolean) {
        AutoContinue.setOn(this, on)
        // リピート中はキューが終わらず似た曲を足せないので、ラジオをオンにしたらリピートを切る
        if (on) controller?.let { c -> if (c.repeatMode != Player.REPEAT_MODE_OFF) c.repeatMode = Player.REPEAT_MODE_OFF }
        // 最後の曲を聴いている最中にオンにしたら、すぐ似た曲を足す
        if (on) runCatching {
            startService(android.content.Intent(this, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_APPEND_SIMILAR))
        }
        refresh()
    }

    /** 今の曲はそのまま、この後の曲を今の曲に似た曲で入れ替える */
    private fun rebuildRadio() {
        // 作り直したらラジオはオン(リピート中だと似た曲が続かないので、リピートも切る)
        AutoContinue.setOn(this, true)
        controller?.let { c -> if (c.repeatMode != Player.REPEAT_MODE_OFF) c.repeatMode = Player.REPEAT_MODE_OFF }
        android.widget.Toast.makeText(this, "ラジオをオンにして、この曲から次の曲を選び直しています…", android.widget.Toast.LENGTH_SHORT).show()
        runCatching {
            startService(android.content.Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_REBUILD_RADIO))
        }
        refresh()
    }

    /** 画面下のボタン: オンならアクセント色 */
    private fun setActive(icon: android.widget.ImageView, label: TextView, on: Boolean) {
        val c = MaterialColors.getColor(icon,
            if (on) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(c)
        label.setTextColor(c)
    }

    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null) {
            finish(); return
        }
        updateKeepScreenOn(c)
        videoMenuItem?.isVisible = videoAvailable(item) && !videoMode
        // 「動画を保存」: 動画がある曲だけ押せる(分からない曲は裏で調べる)
        val sv = Liked.vidOf(item)
        saveVideoItem?.apply {
            isVisible = sv != null && trackOf(item)?.hasVideo != true
            val k = if (VideoMediaSourceFactory.isVideo(item) && StreamDownload.videoId(item) != null) true else VideoCheck.known(sv)
            isEnabled = k == true
            title = when (k) { true -> "動画を保存"; false -> "動画を保存(この曲は動画なし)"; null -> "動画を保存(確認中…)" }
            if (k == null && sv != null && checkingVid != sv) {
                checkingVid = sv
                lifecycleScope.launch { VideoCheck.check(sv); if (checkingVid == sv) refresh() }
            }
        }
        youtubeItem?.isVisible = Liked.vidOf(item) != null
        updateVideoUi(item)
        val radioOn = AutoContinue.isOn(this)
        setActive(findViewById(R.id.actRadioIcon), findViewById(R.id.actRadioLabel), radioOn)
        // リピート中は似た曲を足さない(キューが終わらないため)ので、その時は分かるように
        val repeating = c.repeatMode != Player.REPEAT_MODE_OFF
        findViewById<TextView>(R.id.actRadioLabel).text = if (radioOn) "ラジオ オン" else "ラジオ オフ"
        // リピート中はラジオが効かないので薄く(押すとリピートを切ってラジオを効かせる)
        findViewById<View>(R.id.actRadio).alpha = if (radioOn && repeating) 0.4f else 1f
        findViewById<View>(R.id.actRadio).contentDescription = "似た曲を自動で流す: " +
            (if (radioOn && repeating) "オン(リピート中は効きません。押すとリピートをオフ)" else if (radioOn) "オン" else "オフ")
        // 何から再生しているか
        val source = PlayLog.current?.let { r ->
            when (r.kind) {
                PlayLog.Kind.PLAYLIST -> "プレイリスト「${r.title}」から"
                PlayLog.Kind.ALBUM -> "アルバム「${r.title}」から"
                PlayLog.Kind.ARTIST -> "${r.title} から"
                PlayLog.Kind.STREAM -> if (r.subtitle.startsWith("ラジオ")) "${r.title}" else "「${r.title}」から"
                PlayLog.Kind.TRACK -> ""
            }
        }.orEmpty()
        findViewById<TextView>(R.id.npSource).apply {
            text = source
            visibility = if (source.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<android.widget.ImageButton>(R.id.noGoodBtn).let { b ->
            val vid = Liked.vidOf(item)
            b.visibility = if (vid != null) View.VISIBLE else View.GONE
            val r = Liked.fromItem(item)
            val on = NoGood.isSong(vid) || (r != null && r.artist.isNotBlank() && NoGood.isArtist(Radio.mainArtist(r.artist)))
            // ♡ と同じ: ふだんは輪郭だけ、No Good の時は塗りつぶしで色付き
            b.setImageResource(if (on) R.drawable.ic_thumb_down else R.drawable.ic_thumb_down_outline)
            b.setColorFilter(MaterialColors.getColor(art,
                if (on) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
            b.contentDescription = if (on) "No Good(押すと解除)" else "No Good(おすすめ・ラジオに出さない)"
        }
        findViewById<android.widget.ImageButton>(R.id.likeBtn).let { b ->
            val vid = Liked.vidOf(item)
            b.visibility = if (vid != null) View.VISIBLE else View.GONE
            val on = Liked.contains(vid)
            b.setImageResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            b.setColorFilter(MaterialColors.getColor(art,
                if (on) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
            b.contentDescription = if (on) "お気に入りから外す" else "お気に入りに追加"
        }
        listItem?.let { mi ->
            val l = StreamDownload.listFor(item)
            mi.isVisible = l != null
            mi.title = if (l?.kind == Searcher.Kind.ALBUM) "アルバムごと追加" else "プレイリストごと追加"
        }
        findViewById<android.widget.ImageButton>(R.id.downloadBtn).let { b ->
            val st = storageOf(item)
            b.visibility = View.VISIBLE
            // 印は一覧と同じ: ☁ = オンライン / ⬇ = ダウンロード中 / ✓ = 端末にある(アクセント色) / ＋ = ライブラリにない
            b.setImageResource(when (st) {
                Storage.NOT_IN_LIBRARY -> R.drawable.ic_add
                Storage.ONLINE -> R.drawable.ic_cloud
                Storage.DOWNLOADING -> R.drawable.ic_download
                Storage.ON_DEVICE -> R.drawable.ic_download_done
            })
            b.setColorFilter(MaterialColors.getColor(art,
                if (st == Storage.ON_DEVICE) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant))
            b.contentDescription = when (st) {
                Storage.NOT_IN_LIBRARY -> "ライブラリにない曲: 追加・ダウンロード"
                Storage.ONLINE -> "オンライン(ネットから再生): ダウンロード"
                Storage.DOWNLOADING -> "ダウンロード中"
                Storage.ON_DEVICE -> "ダウンロード済み: 動画・削除"
            }
        }
        title.text = item.mediaMetadata.title ?: ""
        findViewById<TextView>(R.id.npDlProgress).apply {
            val p = trackOf(item)?.let { DownloadManager.progressFor(it) }
                ?: StreamDownload.videoId(item)?.let { vid ->
                    DownloadManager.jobs.value.firstOrNull { (it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED) && youtubeVideoId(it.url) == vid }
                        ?.let { j -> (if (j.video) "🎬 " else "⬇ ") + if (j.progress > 0) "${j.progress}%" else "準備中" }
                }
            text = when {
                p == null -> ""
                p.contains("待機") -> "ダウンロード $p"
                else -> "$p ダウンロード中"
            }
            visibility = if (p == null) View.GONE else View.VISIBLE
        }
        artist.text = item.mediaMetadata.artist ?: ""
        showAlbum(item)
        if (artFor != item.mediaId) {
            artFor = item.mediaId
            loadArt(item)
        }
        // 歌詞は表示していなくても裏で探しておく(無い曲は「歌詞」ボタンを押せなくする)
        if (lyricsFor != item.mediaId) loadLyrics(item)
        playPause.setImageResource(if (c.wantsToPlay()) R.drawable.ic_pause else R.drawable.ic_play)
        findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.playLoading)
            .showLoading(c.isLoadingToPlay())
        prev.alpha = 1f
        next.alpha = if (c.hasNextMediaItem()) 1f else 0.4f
        findViewById<TextView>(R.id.upNext).text = if (c.hasNextMediaItem()) {
            val n = c.getMediaItemAt(c.nextMediaItemIndex).mediaMetadata
            "︿  次: ${n.title ?: ""}" + (n.artist?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        } else "︿  次の曲はありません"
        val on = MaterialColors.getColor(shuffle, androidx.appcompat.R.attr.colorPrimary)
        val off = MaterialColors.getColor(shuffle, androidx.appcompat.R.attr.colorControlNormal)
        shuffle.setColorFilter(if (c.shuffleModeEnabled) on else off)
        shuffle.alpha = if (c.shuffleModeEnabled) 1f else 0.6f
        repeat.setImageResource(if (c.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeat.setColorFilter(if (c.repeatMode != Player.REPEAT_MODE_OFF) on else off)
        repeat.alpha = if (c.repeatMode != Player.REPEAT_MODE_OFF) 1f else 0.6f
        updatePosition()
    }

    private fun updatePosition() {
        val c = controller ?: return
        // 最初のコマの知らせが来ない時の保険: 再生が進んでいたら 2.5 秒で隠しをやめる
        if (coverSince > 0 && android.os.SystemClock.elapsedRealtime() - coverSince > 6000 && noFrameLogged != c.currentMediaItem?.mediaId) {
            noFrameLogged = c.currentMediaItem?.mediaId
            val vs = c.videoSize
            ErrorLog.add("動画", "映像が出ない: 「${c.currentMediaItem?.mediaMetadata?.title ?: ""}」 再生${c.isPlaying} 状態${c.playbackState} " +
                "映像${vs.width}x${vs.height} 映像トラック無効=${c.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)}")
        }
        if (coverSince > 0 && c.isPlaying && android.os.SystemClock.elapsedRealtime() - coverSince > 2500) {
            renderedFor = c.currentMediaItem
            c.currentMediaItem?.let { updateVideoUi(it) }
        }
        val d = c.duration
        if (d <= 0) {
            dur.text = "--:--"
            return
        }
        dur.text = formatTime(d)
        if (seeking) return
        val max = d.toFloat()
        if (seek.valueTo != max) {
            seek.valueFrom = 0f
            seek.value = 0f
            seek.valueTo = max
        }
        val p = c.currentPosition.coerceIn(0, d).toFloat()
        seek.value = p
        pos.text = formatTime(p.toLong())
        highlightLyric(p.toLong())
    }

    // ───────── 歌詞 ─────────

    private fun toggleLyrics() {
        showLyrics = !showLyrics
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("show_lyrics", showLyrics).apply()
        applyLyricsVisibility()
        controller?.currentMediaItem?.let { if (lyricsFor != it.mediaId || !lyricsAvail.containsKey(it.mediaId)) loadLyrics(it) }
    }

    // ───────── 🎧 音声だけ / ▶ 動画 ─────────

    private val npVideo by lazy { findViewById<androidx.media3.ui.PlayerView>(R.id.npVideo) }
    /** 映像オンの申し込み名(画面ごとに別。古い画面が閉じた時に新しい画面の映像まで止めないように) */
    private val gateKey = "nowplaying@" + System.identityHashCode(this)
    private val settingsPrefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    /** 動画モード(保存して次回も) */
    private var videoMode: Boolean
        get() = settingsPrefs.getBoolean("np_video", false)
        set(v) { settingsPrefs.edit().putBoolean("np_video", v).apply() }
    /** 動画に差し替えを試した曲(失敗した曲で何度も取りに行かない) */
    private val videoTried = mutableSetOf<String>()
    private var syncingToggle = false

    /** この曲で動画が使えるか(試聴の曲で、動画が取れなかった曲でなければ) */
    private fun videoAvailable(item: androidx.media3.common.MediaItem): Boolean =
        savedVideo(item) != null || (netAllowed(item) && Liked.vidOf(item)?.let { it !in noVideo } == true)

    /** この曲のライブラリの記録(試聴の曲でもライブラリにあればその曲) */
    /** 表示中のアルバム名(タップで検索に使う) */
    private var albumShown = ""
    /** アルバム名を YouTube Music に問い合わせた曲(同じ曲で何度も問い合わせない) */
    private val albumAsked = HashMap<String, String>()

    /**
     * アルバム名を表示。曲にアルバム名が無い時(YouTube から追加した曲など)は
     * YouTube Music で調べて表示し、ライブラリの曲にも覚えさせる
     */
    private fun showAlbum(item: androidx.media3.common.MediaItem) {
        val t = trackOf(item)
        val known = (t?.album?.takeIf { it.isNotBlank() } ?: item.mediaMetadata.albumTitle?.toString())?.trim().orEmpty()
        val vid = StreamDownload.videoId(item) ?: t?.sourceUrl?.let { youtubeVideoId(it) }
        val name = known.ifEmpty { vid?.let { albumAsked[it] }.orEmpty() }
        albumShown = name
        album.text = if (name.isEmpty()) "" else "💿 $name"
        album.visibility = if (name.isEmpty()) View.GONE else View.VISIBLE
        if (known.isNotEmpty() || vid == null || albumAsked.containsKey(vid)) return
        albumAsked[vid] = ""
        val mediaId = item.mediaId
        lifecycleScope.launch {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { InnerTube.songInfo(vid)?.album }.getOrNull()
            }?.trim().orEmpty()
            if (found.isEmpty()) return@launch
            albumAsked[vid] = found
            t?.let { tr -> Repository.track(tr.id)?.takeIf { it.album.isBlank() }?.let { Repository.updateTracks(listOf(it.copy(album = found))) } }
            if (controller?.currentMediaItem?.mediaId == mediaId) controller?.currentMediaItem?.let { showAlbum(it) }
        }
    }

    /**
     * この先のキューから、このアーティストの曲を外す。
     * ミックス・ラジオ・検索など自動で並んだ曲だけ(自分で作ったプレイリストやアルバムを流している時は、自分で選んだ曲なので外さない)
     */
    private fun dropUpcoming(artist: String) {
        val c = controller ?: return
        val auto = PlayLog.current?.kind.let { it == null || it == PlayLog.Kind.STREAM }
        val key = LibraryMatch.artistKey(artist)
        for (i in c.mediaItemCount - 1 downTo c.currentMediaItemIndex + 1) {
            val item = c.getMediaItemAt(i)
            val a = Radio.mainArtist(item.mediaMetadata.artist?.toString().orEmpty())
            if (LibraryMatch.artistKey(a) != key) continue
            // ライブラリの曲(数字の ID)は、自動で足された時以外は残す
            if (auto || item.mediaId.toLongOrNull() == null) c.removeMediaItem(i)
        }
    }

    private fun trackOf(item: androidx.media3.common.MediaItem): Track? =
        item.mediaId.toLongOrNull()?.let { Repository.track(it) }
            ?: StreamDownload.videoId(item)?.let { VideoAlias.trackFor(it, includeMissing = true) }

    /** 保存した動画(ファイルが今もあれば)。毎回ファイルを開かないよう覚えておく */
    private val savedVideoCache = HashMap<String, String?>()
    private fun savedVideo(item: androidx.media3.common.MediaItem): String? {
        val t = trackOf(item) ?: return null
        if (!t.hasVideo) return null
        val key = t.id.toString() + "|" + t.videoUri
        return savedVideoCache.getOrPut(key) { VideoPrefs.localVideo(this, t) }
    }

    /** ネットの動画を使ってよいか: 試聴の曲はいつも。ライブラリの曲は設定でオンの時だけ(初期値は保存した動画だけ) */
    private fun netAllowed(item: androidx.media3.common.MediaItem): Boolean =
        StreamDownload.videoId(item) != null ||
            (VideoPrefs.netForLibrary(this) && item.mediaId.toLongOrNull() != null && Liked.vidOf(item) != null)

    /**
     * index の曲を動画に差し替える。保存した動画があればそれ(通信なし)、無ければネットの MV。
     * 曲版(静止画だけ)は対になる MV を探す。current = 今の曲(失敗を知らせる)
     */
    private fun prepareVideo(index: Int, current: Boolean) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        val item = c.getMediaItemAt(index)
        if (VideoMediaSourceFactory.isVideo(item)) return
        savedVideo(item)?.let { connection.useLocalVideo(index, it); return }
        if (!netAllowed(item)) return
        val vid = Liked.vidOf(item) ?: return
        if (vid in noVideo || !(if (current) videoTried else nextTried).add(vid)) return
        val mediaId = item.mediaId
        if (current) { fetchingVideo = true; updateLoading() }
        lifecycleScope.launch {
            val res = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val (mv0, sure) = InnerTube.musicVideoLookup(vid)
                    if (sure) VideoCheck.put(vid, mv0)
                    val mv = mv0 ?: throw NoVideo()
                    Searcher.videoSources("https://www.youtube.com/watch?v=$mv")
                }
            }
            if (current) { fetchingVideo = false; updateLoading() }
            val cc = controller ?: return@launch
            // まだ同じ位置に同じ曲があって、🎬 のままなら差し替え
            if (!videoMode || index >= cc.mediaItemCount || cc.getMediaItemAt(index).mediaId != mediaId) return@launch
            res.onSuccess { s -> videoTried += vid; nextTried += vid; connection.upgradeToVideo(index, s) }
            res.onFailure {
                noVideo += vid
                if (current) {
                    cc.currentMediaItem?.let { cur -> syncToggle(cur) }
                    // お知らせは出さない(🎬 ボタンが薄くなるので分かる。曲ごとに出ると気になるため)
                    if (it !is NoVideo) ErrorLog.add("動画", "動画の URL を取得できない ($vid)", it)
                }
            }
        }
    }

    private class NoVideo : Exception()

    /** 🎵/🎬 の見た目を合わせる。動画が無い曲は 🎬 を押せなくして 🎵 を選んだ表示に(設定はそのまま) */
    private fun syncToggle(item: androidx.media3.common.MediaItem) {
        val g = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.avToggle)
        val avail = videoAvailable(item)
        val v = findViewById<View>(R.id.avVideo)
        v.isEnabled = avail
        v.alpha = if (avail) 1f else 0.38f
        v.contentDescription = if (avail) "動画" else "この曲は動画がありません"
        val want = if (avail && videoMode) R.id.avVideo else R.id.avAudio
        if (g.checkedButtonId != want) { syncingToggle = true; g.check(want); syncingToggle = false }
    }
    private var meteredWarned = false

    private fun setupVideoToggle() {
        val g = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.avToggle)
        g.check(if (videoMode) R.id.avVideo else R.id.avAudio)
        g.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || syncingToggle) return@addOnButtonCheckedListener
            val want = id == R.id.avVideo
            if (want == videoMode) return@addOnButtonCheckedListener
            videoMode = want
            // 🎬 を選んだら動画が見えるように歌詞は閉じる
            if (want && showLyrics) toggleLyrics()
            if (want && Net.metered && !meteredWarned) {
                meteredWarned = true
                android.widget.Toast.makeText(this, "モバイル回線です。動画は音声より通信量が多くなります", android.widget.Toast.LENGTH_SHORT).show()
            }
            applyVideoMode()
            refresh()
        }
        findViewById<View>(R.id.npFullscreen).setOnClickListener { setFixed(true) }
        findViewById<View>(R.id.fsExit).setOnClickListener { setFixed(false) }
        // 映像のタップはジャケットと同じ(一時停止 / 再生)
        npVideo.setOnClickListener { art.performClick() }
    }

    /**
     * 動画を見ている間は画面を消さない。🎬(映像)で再生を続けている間はずっと
     * (曲の切れ目の読み込み中や、途中に動画の無い曲があっても切らない。消すのは一時停止・最後まで流し終えた・🎵 にした時)。
     * 画面を消す時間はいったん点けたままにした後で消すと、すでに時間が過ぎていればすぐ消えるので、切れ目で外さないことが大事
     */
    private fun updateKeepScreenOn(c: Player) {
        val on = videoMode && c.playWhenReady && c.playbackState != Player.STATE_ENDED
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 動画モードなら映像を映す(必要なら今の曲を動画に差し替え)、音声モードなら映像を止める */
    private fun applyVideoMode() {
        val c = controller ?: return
        if (videoMode) {
            VideoGate.acquire(gateKey, c)
            bindVideoViews()
            ensureVideo()
        } else {
            bindVideoViews()
            VideoGate.release(gateKey, c)
            // 🎵: ライブラリの曲は、いつもの音声ファイルに戻す(先に動画にしておいた次の曲も)
            for (i in 0 until c.mediaItemCount) connection.restoreAudio(i)
        }
    }

    private fun ensureVideo() {
        val c = controller ?: return
        if (!videoMode) return
        prepareVideo(c.currentMediaItemIndex, current = true)
    }

    /** 映像を映す先: 全画面なら全画面の方、そうでなければジャケットの場所(音声だけの時はどちらも外す) */
    private fun bindVideoViews() {
        val c = controller?.takeIf { videoMode }
        if (fullscreen) { npVideo.player = null; fsVideo.player = c } else { fsVideo.player = null; npVideo.player = c }
    }

    /** 切り替えボタンは試聴(オンライン)の曲だけ。映像は動画になっている時だけジャケットの代わりに */
    private fun updateVideoUi(item: androidx.media3.common.MediaItem) {
        val avail = videoAvailable(item)
        // 切り替えはいつも表示。動画が無い曲では 🎬 を押せなくする
        findViewById<View>(R.id.avToggle).visibility = View.VISIBLE
        syncToggle(item)
        if (avail && videoMode) ensureVideo()
        // 歌詞を表示中も動画は映す(ジャケットと同じく薄くして、上に歌詞)
        val show = avail && videoMode && VideoMediaSourceFactory.isVideo(item)
        findViewById<View>(R.id.npVideoBox).apply {
            visibility = if (show) View.VISIBLE else View.GONE
            alpha = if (lyricsShown) 0.15f else 1f
        }
        art.visibility = if (show) View.INVISIBLE else View.VISIBLE
        // この動画の最初のコマが出るまではジャケットで隠す
        val ready = renderedFor != null && renderedFor == item
        if (show && !ready && coverSince == 0L) {
            coverSince = android.os.SystemClock.elapsedRealtime()
            // 動画から動画へ移る時は、ジャケットを挟まず黒で待つ(絵がちらちら挟まらないように)
            coverBlack = frameShown
            syncVideoCover()
        }
        if (ready || !show) coverSince = 0L
        frameShown = show && ready
        findViewById<View>(R.id.npVideoCover).visibility = if (show && !ready) View.VISIBLE else View.GONE
        // 動画を映している間に、次の曲も先に動画にしておく(曲の切り替わりでジャケットに戻らないように)
        if (show && ready) prefetchNextVideo()
        if (show != videoShown) { videoShown = show; applyOrientationPolicy() }
        applyFullscreen()
        updateLoading()
    }

    /** 動画の URL を取得中 */
    private var fetchingVideo = false
    /** 最初のコマを映し終えた曲(その曲になるまでジャケットで隠す) */
    private var renderedFor: androidx.media3.common.MediaItem? = null
    private var coverSince = 0L
    private var noFrameLogged: String? = null

    override fun onRenderedFirstFrame() {
        renderedFor = controller?.currentMediaItem
        controller?.currentMediaItem?.let { updateVideoUi(it) }
    }

    /** 隠し用の絵をジャケットと同じに */
    private fun syncVideoCover() {
        val cover = findViewById<ImageView>(R.id.npVideoCover)
        if (coverBlack) {
            cover.setImageDrawable(null)
            cover.setBackgroundColor(android.graphics.Color.BLACK)
            return
        }
        cover.background = null
        val d = art.drawable
        cover.setImageDrawable((d as? android.graphics.drawable.TransitionDrawable)?.getDrawable(1) ?: d)
    }

    /** 映像のコマを今映しているか(次の曲へ移る時の隠し方を決める) */
    private var frameShown = false
    private var coverBlack = false
    private val nextTried = mutableSetOf<String>()

    /** 次の曲が試聴の曲なら、今のうちに動画の URL を取って動画に差し替えておく */
    private fun prefetchNextVideo() {
        val c = controller ?: return
        if (!videoMode) return
        val idx = c.nextMediaItemIndex
        if (idx == androidx.media3.common.C.INDEX_UNSET || idx >= c.mediaItemCount) return
        prepareVideo(idx, current = false)
    }

    /** くるくる: 動画の準備中・読み込み待ち・最初のコマが出るまで */
    private fun updateLoading() {
        val c = controller
        val waiting = c != null && videoShown && c.playWhenReady && !c.isPlaying && c.playbackState == Player.STATE_BUFFERING
        findViewById<View>(R.id.npLoading).visibility =
            if (videoMode && !lyricsShown && (fetchingVideo || waiting || coverSince > 0)) View.VISIBLE else View.GONE
    }

    // ───────── 全画面(横向き)・画面の回転 ─────────

    private val fsVideo by lazy { findViewById<androidx.media3.ui.PlayerView>(R.id.fsVideo) }
    /** 動画をジャケットの場所に映しているか */
    private var videoShown = false
    /** 横向きで動画を画面いっぱいに映しているか */
    private var fullscreen = false
    /** ボタンで向きを変えた後、端末をその向きにするまで自動回転を待つ */
    private var lockedUntilMatch = false

    private fun rotationLocked() = settingsPrefs.getBoolean(KEY_LOCK, false)
    private fun orientationFor(land: Boolean) =
        if (land) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

    private fun setupRotation() {
        val toggle = View.OnClickListener {
            val locked = !rotationLocked()
            val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            settingsPrefs.edit().putBoolean(KEY_LOCK, locked).putBoolean(KEY_LOCK_LAND, land).apply()
            lockedUntilMatch = false
            requestedOrientation = if (locked) orientationFor(land) else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            android.widget.Toast.makeText(this,
                if (locked) "画面の回転を固定しました(${if (land) "横" else "縦"})" else "画面の回転を自動にしました",
                android.widget.Toast.LENGTH_SHORT).show()
            updateLockButtons()
        }
        findViewById<View>(R.id.npRotate).setOnClickListener(toggle)
        findViewById<View>(R.id.fsRotate).setOnClickListener(toggle)
        updateLockButtons()
        orientationListener.enable()
    }

    private fun updateLockButtons() {
        val locked = rotationLocked()
        listOf(R.id.npRotate, R.id.fsRotate).forEach { id ->
            findViewById<ImageButton>(id).apply {
                setImageResource(if (locked) R.drawable.ic_screen_lock_rotation else R.drawable.ic_screen_rotation)
                contentDescription = if (locked) "画面の回転: 固定中(タップで自動に)" else "画面の回転: 自動(タップで固定)"
            }
        }
    }

    /** 縦 / 横にする。固定中はその向きで固定(保存)、自動なら端末をその向きにしたら自動に戻す */
    private fun setFixed(land: Boolean) {
        // 横にする時は不透明に(透明な画面は向きを変えられない)
        if (land) setOpaque(true)
        requestedOrientation = orientationFor(land)
        if (rotationLocked()) settingsPrefs.edit().putBoolean(KEY_LOCK_LAND, land).apply()
        else lockedUntilMatch = true
    }

    /**
     * 動画を映している時だけ回転できるように(固定中は保存した向き)。それ以外はいつもどおり。
     * 透明な画面は向きを変えられないので、横にする時だけ不透明にする(Android 11 以降)。
     * 縦の間は透明のまま(下にスワイプした時に後ろが見え、閉じても真っ暗にならないように)。
     * 端末を横に傾けたら不透明にして横向きへ(orientationListener)、縦に戻ったら透明に戻す(onConfigurationChanged)
     */
    private fun applyOrientationPolicy() {
        if (!videoShown) {
            lockedUntilMatch = false
            setOpaque(false)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        if (lockedUntilMatch) return
        val screenLand = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        when {
            // 横で固定
            rotationLocked() && settingsPrefs.getBoolean(KEY_LOCK_LAND, false) -> {
                setOpaque(true); requestedOrientation = orientationFor(true)
            }
            // 縦で固定: 透明のまま(縦のまま)
            rotationLocked() -> { setOpaque(false); requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            // 自動: 今が横なら不透明のまま端末の向きに合わせる。縦なら透明にして、傾けるのを待つ
            screenLand -> { setOpaque(true); requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }
            else -> { setOpaque(false); requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    /** 不透明かどうか(不透明の間は窓の下地を塗る: ずらした時に前の絵が跡のように残らないように) */
    private var opaque = false
    private fun setOpaque(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        if (on == opaque) return
        opaque = on
        if (on) window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
            MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorSurface)))
        runCatching { setTranslucent(!on) }
        if (!on) window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private val orientationListener by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val deviceLand = deg in 60..120 || deg in 240..300
                val devicePort = deg <= 30 || deg >= 330 || deg in 150..210
                val screenLand = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (lockedUntilMatch) {
                    if ((screenLand && deviceLand) || (!screenLand && devicePort)) {
                        lockedUntilMatch = false
                        applyOrientationPolicy()
                    }
                    return
                }
                // 自動回転: 縦(透明)で動画を映している時に端末を横にしたら、不透明にして横向きへ
                if (videoShown && !rotationLocked() && !screenLand && deviceLand && !closing) {
                    setOpaque(true)
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFullscreen()
        // 縦に戻ったら透明に戻す(自動回転の時。固定中・向きを待っている間は applyOrientationPolicy の決まりどおり)
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) applyOrientationPolicy()
    }

    /** 横向きで動画を映している時は画面いっぱいに(操作バー付き)。縦に戻ったらいつもの画面 */
    private fun applyFullscreen() {
        val want = videoShown && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (want == fullscreen) return
        fullscreen = want
        findViewById<View>(R.id.fsBox).visibility = if (want) View.VISIBLE else View.GONE
        findViewById<View>(R.id.npMain).visibility = if (want) View.INVISIBLE else View.VISIBLE
        content.translationY = 0f; content.alpha = 1f
        bindVideoViews()
        val ic = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (want) {
            ic.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else ic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        orientationListener.disable()
        super.onDestroy()
    }

    private fun applyLyricsVisibility() {
        controller?.currentMediaItem?.let { updateVideoUi(it) }
        lyricsScroll.visibility = if (lyricsShown) View.VISIBLE else View.GONE
        art.animate().alpha(if (lyricsShown) 0.12f else 1f).setDuration(200).start()
        setActive(findViewById(R.id.actLyricsIcon), findViewById(R.id.actLyricsLabel), lyricsShown)
        // 歌詞が無い曲はボタンを押せなくする(見た目も薄く)
        val none = controller?.currentMediaItem?.mediaId?.let { lyricsAvail[it] } == false
        findViewById<View>(R.id.actLyrics).apply {
            isEnabled = !none
            alpha = if (none) 0.38f else 1f
            contentDescription = if (none) "この曲の歌詞はありません" else "歌詞"
        }
        findViewById<TextView>(R.id.actLyricsLabel).text = if (none) "歌詞なし" else "歌詞"
    }

    private fun lyricsMessage(text: String) {
        lyricsBox.removeAllViews()
        lineViews = emptyList(); lyricLines = emptyList(); currentLine = -1
        lyricsBox.addView(TextView(this).apply {
            this.text = text
            gravity = android.view.Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            alpha = 0.8f
        })
    }

    private fun loadLyrics(item: androidx.media3.common.MediaItem) {
        lyricsFor = item.mediaId
        lyricsJob?.cancel()
        lyricsMessage("歌詞を探しています…")
        val m = item.mediaMetadata
        val track = item.mediaId.toLongOrNull()?.let { Repository.track(it) }
        val title = track?.title ?: m.title?.toString().orEmpty()
        val artistName = track?.artist ?: m.artist?.toString().orEmpty()
        val album = track?.album ?: m.albumTitle?.toString().orEmpty()
        val durSec = (track?.durationMs ?: controller?.duration?.takeIf { it > 0 } ?: 0L) / 1000
        val id = item.mediaId
        lyricsJob = lifecycleScope.launch {
            val r = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Lyrics.get(this@NowPlayingActivity, title, artistName, album, durSec,
                        StreamDownload.videoId(item) ?: track?.sourceUrl?.let { youtubeVideoId(it) })
                }
            }
            if (lyricsFor != id) return@launch
            val res = r.getOrNull()
            if (res == null) lyricsAvail.remove(id) else lyricsAvail[id] = !res.isEmpty
            applyLyricsVisibility()
            when {
                // 取れなかった時は、歌詞ボタンを押した時にもう一度探す(裏で何度も取りに行かないように)
                res == null -> lyricsMessage("歌詞を取得できませんでした\n(通信を確認してください)")
                res.isEmpty -> lyricsMessage("この曲の歌詞は見つかりませんでした")
                res.synced.isNotEmpty() -> showSynced(res.synced)
                else -> showPlain(res.plain)
            }
        }
    }

    private fun lineView(text: String) = TextView(this).apply {
        this.text = text.ifBlank { "♪" }
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        val d = resources.displayMetrics.density
        setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
    }

    private fun showSynced(lines: List<Lyrics.Line>) {
        lyricsBox.removeAllViews()
        lyricLines = lines
        currentLine = -1
        lineViews = lines.map { l ->
            lineView(l.text).apply {
                alpha = 0.4f
                // タップでその行から再生
                setOnClickListener { controller?.seekTo(l.timeMs); controller?.play() }
            }.also { lyricsBox.addView(it) }
        }
        lyricsScroll.scrollTo(0, 0)
        controller?.let { highlightLyric(it.currentPosition) }
    }

    private fun showPlain(text: String) {
        lyricsBox.removeAllViews()
        lineViews = emptyList(); lyricLines = emptyList(); currentLine = -1
        lyricsBox.addView(lineView(text).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setLineSpacing(0f, 1.4f)
        })
        lyricsBox.addView(TextView(this).apply {
            this.text = "(時間付きの歌詞がないため、自動スクロールしません)"
            alpha = 0.6f
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        })
        lyricsScroll.scrollTo(0, 0)
    }

    private fun highlightLyric(posMs: Long) {
        if (!lyricsShown || lyricLines.isEmpty()) return
        val idx = lyricLines.indexOfLast { it.timeMs <= posMs + 300 }
        if (idx == currentLine) return
        lineViews.getOrNull(currentLine)?.alpha = 0.4f
        currentLine = idx
        val v = lineViews.getOrNull(idx) ?: return
        v.alpha = 1f
        // 今の行を真ん中あたりへ
        lyricsScroll.smoothScrollTo(0, (v.top - lyricsScroll.height / 2 + v.height / 2).coerceAtLeast(0))
    }

    companion object {
        /** 今開いている再生中画面 */
        private var live: java.lang.ref.WeakReference<NowPlayingActivity>? = null

        /** 画面の回転の固定(前の動画プレビューと同じ設定を引き継ぐ) */
        private const val KEY_LOCK = "video_rotation_locked"
        private const val KEY_LOCK_LAND = "video_rotation_land"

        /** 🎬 動画 / 🎵 音声だけ(検索で動画を開いた時は 🎬 に) */
        /** 動画を取れなかった曲(アプリを開いている間は 🎬 を押せなくする) */
        private val noVideo = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun setVideoMode(c: android.content.Context, on: Boolean) =
            c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("np_video", on).apply()
    }
}

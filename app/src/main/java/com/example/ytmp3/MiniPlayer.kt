package com.example.ytmp3

import android.content.Intent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 画面下部のミニプレーヤー。タップで再生画面を開く */
class MiniPlayer(private val activity: AppCompatActivity, private val root: View) : Player.Listener {
    private val art: ImageView = root.findViewById(R.id.mpArt)
    private val title: TextView = root.findViewById(R.id.mpTitle)
    private val artist: TextView = root.findViewById(R.id.mpArtist)
    private val play: ImageButton = root.findViewById(R.id.mpPlay)
    private val loading: com.google.android.material.progressindicator.CircularProgressIndicator = root.findViewById(R.id.mpLoading)
    private val next: ImageButton = root.findViewById(R.id.mpNext)
    private val download: ImageButton = root.findViewById(R.id.mpDownload)
    private val addList: ImageButton = root.findViewById(R.id.mpAddList)
    private val progress: LinearProgressIndicator = root.findViewById(R.id.mpProgress)
    private var controller: MediaController? = null
    private var lastOpen = 0L

    /** 再生中の曲が変わったとき(リストのハイライト用) */
    var onTrackChanged: (Long?) -> Unit = {}

    init {
        root.setOnClickListener {
            // 続けて押しても 1 枚だけ開く
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastOpen < 800) return@setOnClickListener
            lastOpen = now
            // 動画も再生画面で(🎬 の時はジャケットの場所に映像)
            activity.startActivity(Intent(activity, NowPlayingActivity::class.java))
        }
        // 長押しで再生キュー
        root.setOnLongClickListener { QueueSheet.show(activity); true }
        play.setOnClickListener {
            controller?.togglePlay()
        }
        next.setOnClickListener { controller?.skipNext() }
        // 試聴中の曲をダウンロード
        download.setOnClickListener {
            (activity as? MainActivity)?.ensureNotificationPermission()
            StreamDownload.download(activity, controller?.currentMediaItem)
            refresh()
        }
        // 試聴中のアルバム/プレイリストをまとめて追加
        addList.setOnClickListener {
            val l = StreamDownload.listFor(controller?.currentMediaItem) ?: return@setOnClickListener
            StreamDownload.addListDialog(activity, l) { (activity as? MainActivity)?.ensureNotificationPermission() }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(Repository.tracks, DownloadManager.jobs) { _, _ -> }.collect { refresh() }
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    controller?.let { c ->
                        val d = c.duration
                        if (d > 0) progress.setProgressCompat((c.currentPosition * 1000 / d).toInt(), false)
                    }
                    delay(500)
                }
            }
        }
    }

    fun attach(c: MediaController) {
        controller?.removeListener(this)
        controller = c
        c.addListener(this)
        refresh()
    }

    fun detach() {
        controller?.removeListener(this)
        controller = null
    }

    override fun onEvents(player: Player, events: Player.Events) = refresh()

    private fun refresh() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            root.visibility = View.GONE
            onTrackChanged(null)
            return
        }
        root.visibility = View.VISIBLE
        val meta: MediaMetadata = item.mediaMetadata
        title.text = meta.title ?: ""
        // 止まっている時は「前回の続き · 1:35 / 2:16」(ホームの上のカードの代わり)。再生中はアーティスト
        // アプリを開いてからまだ再生していなければ「前回の続き」、再生した後に止めたなら「一時停止中」
        if (c.wantsToPlay()) playedSinceLaunch = true
        artist.text = if (!c.wantsToPlay() && c.currentPosition > 0) {
            val d = c.duration
            (if (playedSinceLaunch) "一時停止中 · " else "前回の続き · ") + formatTime(c.currentPosition) + if (d > 0) " / " + formatTime(d) else ""
        } else meta.artist ?: ""
        Thumbs.bindItem(art, item.mediaId, meta.artworkUri)
        play.setImageResource(if (c.wantsToPlay()) R.drawable.ic_pause else R.drawable.ic_play)
        loading.showLoading(c.isLoadingToPlay())
        next.isEnabled = c.hasNextMediaItem()
        next.alpha = if (c.hasNextMediaItem()) 1f else 0.4f
        StreamDownload.bind(download, item)
        StreamDownload.bindList(addList, item)
        onTrackChanged(item.mediaId.toLongOrNull())
    }

    companion object {
        /** アプリを開いてから再生したか(画面をまたいで共通) */
        @Volatile private var playedSinceLaunch = false
    }
}

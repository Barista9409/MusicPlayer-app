package com.example.ytmp3

import android.content.Context
import android.widget.ImageButton
import android.widget.Toast
import androidx.media3.common.MediaItem

/** 試聴中(ライブラリにない)曲をその場でダウンロードするボタンの共通処理 */
object StreamDownload {
    enum class State { NONE, QUEUED, IN_LIBRARY, ONLINE }

    /** 試聴中の曲なら動画 ID、ライブラリの曲なら null */
    fun videoId(item: MediaItem?): String? =
        item?.mediaId?.takeIf { it.startsWith(PlayerConnection.STREAM_PREFIX) }?.removePrefix(PlayerConnection.STREAM_PREFIX)

    /** ダウンロードボタンの対象: 試聴の曲、または ☁ オンラインのライブラリの曲 */
    fun dlVid(item: MediaItem?): String? = videoId(item)
        ?: item?.mediaId?.toLongOrNull()?.takeIf { Repository.isOnline(it) }?.let { Repository.track(it) }?.sourceUrl?.let { youtubeVideoId(it) }

    fun state(vid: String): State {
        val t = VideoAlias.trackFor(vid, includeMissing = true)
        val queued = DownloadManager.jobs.value.any {
            (it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING) && youtubeVideoId(it.url) == vid
        }
        return when {
            queued -> State.QUEUED
            t != null && Repository.isOnline(t.id) -> State.ONLINE
            t != null -> State.IN_LIBRARY
            else -> State.NONE
        }
    }

    /** ボタンの見た目を合わせる(試聴中でなければ隠す) */
    fun bind(button: ImageButton, item: MediaItem?) {
        val vid = dlVid(item)
        if (vid == null) { button.visibility = android.view.View.GONE; return }
        button.visibility = android.view.View.VISIBLE
        val st = state(vid)
        val canDl = st == State.NONE || st == State.ONLINE
        button.setImageResource(if (canDl) R.drawable.ic_download else R.drawable.ic_check)
        button.alpha = if (canDl) 1f else 0.6f
        button.contentDescription = when (st) {
            State.NONE -> "この曲をダウンロード"
            State.ONLINE -> "ダウンロード"
            State.QUEUED -> "ダウンロード中"
            State.IN_LIBRARY -> "ダウンロード済み"
        }
    }

    /**
     * 再生画面・動画プレビューの ＋ / ⬇ ボタン:
     * まだ無い曲は「ライブラリに追加」(☁ オンライン / ⬇ ダウンロード を設定どおりに)、☁ の曲はダウンロード
     */
    fun addToLibrary(context: Context, item: MediaItem?, durationMs: Long = 0, onDone: () -> Unit = {}) {
        val vid = dlVid(item) ?: return
        if (state(vid) != State.NONE) { download(context, item); onDone(); return }
        // 長さも入れておく(ライブラリの一覧で 0:00 にならないように)
        val r = item?.let { Liked.fromItem(it) }?.copy(durationSec = durationMs.coerceAtLeast(0) / 1000) ?: return
        OnlineLibrary.addSong(context, r, onDone) { download(context, item) }
    }

    /** ＋ / ⬇ ボタンの見た目: (アイコン, 説明) */
    fun addButtonLook(vid: String): Pair<Int, String> = when (state(vid)) {
        State.NONE -> R.drawable.ic_add to "ライブラリに追加"
        State.ONLINE -> R.drawable.ic_download to "ダウンロード(オフラインでも聴けるように)"
        State.QUEUED -> R.drawable.ic_download to "ダウンロード中"
        State.IN_LIBRARY -> R.drawable.ic_download_done to "ダウンロード済み"
    }

    fun download(context: Context, item: MediaItem?) {
        val vid = dlVid(item) ?: return
        when (state(vid)) {
            State.IN_LIBRARY -> toast(context, "ダウンロード済みです")
            State.ONLINE -> VideoAlias.trackFor(vid, includeMissing = true)?.let {
                DownloadManager.redownload(context, listOf(it))
                toast(context, "「${it.title}」をダウンロードします(オフラインでも聴けるように)")
            }
            State.QUEUED -> toast(context, "ダウンロード中です")
            State.NONE -> {
                DownloadManager.add(context, "https://www.youtube.com/watch?v=$vid", wholeList = false, makePlaylist = false)
                toast(context, "「${item?.mediaMetadata?.title ?: ""}」をダウンロードします")
            }
        }
    }

    // ───────── アルバム/プレイリストごと追加 ─────────

    /** 今試聴しているアルバム/プレイリストと、その曲の mediaId */
    @Volatile var previewList: Searcher.Result? = null
    @Volatile var previewListIds: Set<String> = emptySet()

    /** この曲がアルバム/プレイリストの試聴中ならそのリスト */
    fun listFor(item: MediaItem?): Searcher.Result? =
        previewList?.takeIf { item != null && item.mediaId in previewListIds }

    fun bindList(button: ImageButton, item: MediaItem?) {
        val l = listFor(item)
        button.visibility = if (l == null) android.view.View.GONE else android.view.View.VISIBLE
        button.contentDescription = if (l?.kind == Searcher.Kind.ALBUM) "アルバムごと追加" else "プレイリストごと追加"
    }

    /** 検索結果の追加ボタンと同じ選択肢 */
    fun addListDialog(context: Context, list: Searcher.Result, onAdded: () -> Unit = {}) {
        val what = if (list.kind == Searcher.Kind.ALBUM) "アルバム" else "プレイリスト"
        val items = arrayOf(
            "☁ オンラインでプレイリスト作成",
            "⬇ ダウンロードしてプレイリスト作成",
            "⬇ 曲だけダウンロード(プレイリストは作らない)",
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle("この${what}をライブラリに追加")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> OnlineLibrary.addList(context, list)
                    1 -> addList(context, list, true)
                    else -> addList(context, list, false)
                }
                onAdded()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun addList(context: Context, list: Searcher.Result, makePlaylist: Boolean) {
        DownloadManager.add(context, list.url, wholeList = true, makePlaylist = makePlaylist)
        toast(context, "「${list.title}」の曲を追加します")
    }

    private fun toast(context: Context, s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
}

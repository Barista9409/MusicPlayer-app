package com.example.ytmp3

import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 曲の削除・プレイリスト追加など、複数画面で使う操作 */
class TrackActions(private val activity: AppCompatActivity) {
    private var pendingIds: Set<Long> = emptySet()
    /** 削除の確認後、ライブラリには残してオンラインに戻すか */
    private var pendingKeep = false
    var onRemoved: (Set<Long>) -> Unit = {}

    private val deleteRequest: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode == AppCompatActivity.RESULT_OK) {
                if (pendingKeep) { Repository.markOnline(pendingIds); toast("ダウンロードを削除しました") }
                else { remove(pendingIds); toast("削除しました") }
            } else {
                toast("ファイルの削除がキャンセルされました")
            }
            pendingIds = emptySet()
        }

    // ───────── プレイリストに追加 ─────────

    fun addToPlaylist(trackIds: List<Long>, done: () -> Unit = {}) {
        if (trackIds.isEmpty()) return
        val lists = Repository.playlists.value
        val names = arrayOf("＋ 新しいプレイリスト…") + lists.map { "${it.name}(${it.trackIds.size}曲)" }
        MaterialAlertDialogBuilder(activity)
            .setTitle("${trackIds.size} 曲をプレイリストに追加")
            .setItems(names) { _, which ->
                if (which == 0) {
                    promptName("新しいプレイリスト", "") { name ->
                        val p = Repository.createPlaylist(name, trackIds)
                        toast("「${p.name}」を作成しました")
                        done()
                    }
                } else {
                    val p = lists[which - 1]
                    val n = Repository.addToPlaylist(p.id, trackIds)
                    toast(if (n == 0) "すでに追加済みです" else "「${p.name}」に $n 曲追加しました")
                    done()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    fun promptName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(initial)
            setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "プレイリスト名"
        }
        val box = FrameLayout(activity).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .show()
        input.requestFocus()
    }

    // ───────── 削除 ─────────

    /**
     * ライブラリから削除。既定では端末のファイルも削除する(「ファイルは残す」も選べる)。
     * @param groupName フォルダ/アルバム単位で消すときの名前
     */
    /**
     * アプリを使う人どうしで共有(LINE など)。YouTube の曲だけが対象(端末に入れた曲は相手が聴けないため)。
     * 30 曲まではリンク。それより多い時は「長いリンク」か「ファイル」を選ぶ
     */
    fun share(name: String, kind: Share.Kind, tracks: List<Track>) {
        val pairs = tracks.mapNotNull { t ->
            youtubeVideoId(t.sourceUrl)?.let { it to listOf(t.title, t.artist).filter { x -> x.isNotBlank() }.joinToString(" - ") }
        }.distinctBy { it.first }
        shareIds(name, kind, pairs, tracks.size - pairs.size)
    }

    /** (動画 ID, 「曲名 - アーティスト」) で共有。skipped = 含められなかった曲の数 */
    fun shareIds(name: String, kind: Share.Kind, pairs: List<Pair<String, String>>, skipped: Int = 0) {
        if (pairs.isEmpty()) { toast("共有できる曲がありません(端末に入れた曲は共有できません)"); return }
        val p = Share.Payload(kind, name, pairs.map { it.first })
        val note = if (skipped > 0) "(端末に入れた $skipped 曲は相手が聴けないので含めません)" else ""
        fun sendLink() {
            if (note.isNotEmpty()) toast(note)
            activity.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                // 本文はリンクだけ(「コピー」でリンクだけが写るように)。何の共有かは、共有画面の見出しと件名に
                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, Share.link(p))
                .putExtra(android.content.Intent.EXTRA_TITLE, Share.title(p))
                .putExtra(android.content.Intent.EXTRA_SUBJECT, Share.title(p)), "共有"))
        }
        if (pairs.size <= Share.LINK_MAX) { sendLink(); return }
        MaterialAlertDialogBuilder(activity)
            .setTitle("${pairs.size} 曲を共有")
            .setMessage("曲が多いので、リンクが長くなります(約 ${Share.link(p).length} 文字)。$note\n\n" +
                "ファイルで送ると、トークがすっきりします。")
            .setNegativeButton("キャンセル", null)
            .setNeutralButton("リンクで送る") { _, _ -> sendLink() }
            .setPositiveButton("ファイルで送る") { _, _ ->
                runCatching {
                    val dir = java.io.File(activity.cacheDir, "share").apply { mkdirs() }
                    val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "playlist" }
                    val f = java.io.File(dir, "$safe.txt")
                    f.writeText(Share.fileText(p, pairs.map { it.second }))
                    val uri = androidx.core.content.FileProvider.getUriForFile(activity, activity.packageName + ".files", f)
                    activity.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "共有"))
                }.onFailure { toast("ファイルを作れませんでした: ${it.message}") }
            }
            .show()
    }

    /**
     * プレイリストを削除: 「プレイリストだけ」か「曲もライブラリから」を選ぶ。
     * 曲も消す時は、ほかのプレイリストにも入っている曲は残す。ファイルの扱いはいつもの削除の確認で選ぶ
     */
    fun confirmDeletePlaylist(p: Playlist, done: () -> Unit = {}) {
        val tracks = p.trackIds.distinct().mapNotNull { Repository.track(it) }
        val inOthers = Repository.playlists.value.filter { it.id != p.id }.flatMap { it.trackIds }.toSet()
        val removable = tracks.filter { it.id !in inOthers }
        val kept = tracks.size - removable.size
        val b = MaterialAlertDialogBuilder(activity).setTitle("「${p.name}」を削除").setNegativeButton("キャンセル", null)
        if (removable.isEmpty()) {
            b.setMessage(if (tracks.isEmpty()) "空のプレイリストを削除します" else "プレイリストを削除します(曲はほかのプレイリストにも入っているので、ライブラリに残ります)")
                .setPositiveButton("削除") { _, _ -> Repository.deletePlaylist(p.id); toast("プレイリストを削除しました"); done() }
                .show()
            return
        }
        var choice = 0
        val items = arrayOf(
            "プレイリストだけ削除\n曲はライブラリに残ります",
            "曲もライブラリから削除(${removable.size} 曲)" + if (kept > 0) "\nほかのプレイリストにも入っている $kept 曲は残します" else "",
        )
        b.setSingleChoiceItems(items, 0) { _, w -> choice = w }
            .setPositiveButton("削除") { _, _ ->
                if (choice == 0) { Repository.deletePlaylist(p.id); toast("プレイリストを削除しました"); done() }
                // 曲も: ファイルを消すか残すかを、いつもの確認で選んでから、プレイリストも消す(キャンセルなら何も消さない)
                else confirmDelete(removable, p.name) { Repository.deletePlaylist(p.id); done() }
            }
            .show()
    }

    fun confirmDelete(tracks: List<Track>, groupName: String? = null, done: () -> Unit = {}) {
        if (tracks.isEmpty()) return
        val ids = tracks.map { it.id }.toSet()
        // ファイルの無い曲(見つからない曲・☁ オンラインの曲)は、ライブラリから外すだけ
        val gone = tracks.filter { Repository.isMissing(it.id) && it.altUris.isEmpty() }
        val withFile = tracks - gone.toSet()

        if (withFile.isEmpty() && gone.all { Repository.isOnline(it.id) }) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("ライブラリから外す")
                .setMessage((if (tracks.size == 1) "「${tracks[0].title}」(☁ オンライン)" else "☁ オンラインの ${tracks.size} 曲") +
                    "をライブラリとプレイリストから外します。端末のファイルはありません。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("外す") { _, _ -> remove(ids); toast("${tracks.size} 曲を外しました"); done() }
                .show()
            return
        }
        // ファイルが見つからない曲だけなら、ライブラリから外すだけ
        if (withFile.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("ライブラリから削除")
                .setMessage(if (tracks.size == 1) "「${tracks[0].title}」はファイルが見つかりません。ライブラリから削除しますか？"
                    else "ファイルが見つからない ${tracks.size} 曲をライブラリから削除しますか？")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("削除") { _, _ -> remove(ids); toast("${tracks.size} 曲を削除しました"); done() }
                .show()
            return
        }

        val what = when {
            groupName != null -> "「$groupName」の ${tracks.size} 曲"
            tracks.size == 1 -> "「${tracks[0].title}」"
            else -> "${tracks.size} 曲"
        }
        val sample = if (tracks.size in 2..4) "\n" + tracks.joinToString("\n") { "・${it.title}" } else ""
        val totalMb = withFile.sumOf { it.sizeBytes } / 1_048_576.0
        val size = if (totalMb > 0) String.format(" (%.1f MB)", totalMb) else ""
        MaterialAlertDialogBuilder(activity)
            .setTitle("ライブラリから削除")
            .setMessage("$what を削除します。$sample\n\n端末のフォルダにある音楽ファイル$size も削除され、元に戻せません。" +
                "\nプレイリストからも外れます。")
            .setNegativeButton("キャンセル", null)
            .setNeutralButton("ファイルは残す") { _, _ ->
                remove(ids, ignore = true); toast("ライブラリから外しました(ファイルは残っています)"); done()
            }
            .setPositiveButton("削除") { _, _ ->
                remove(gone.map { it.id }.toSet())
                deleteFiles(withFile); done()
            }
            .show()
    }

    /**
     * ライブラリを初期化: 全曲を一覧から外す(ファイルも消すかは選択)。
     * ファイルを残す時は、フォルダの再読み込みで勝手に戻らないよう「除外」にしておく。
     */
    fun resetLibrary(deleteFiles: Boolean, clearPlaylists: Boolean, clearHistory: Boolean) {
        val all = Repository.tracks.value
        if (clearPlaylists) Repository.playlists.value.forEach { Repository.deletePlaylist(it.id) }
        if (clearHistory) PlayLog.clearAll()
        if (all.isEmpty()) { toast("初期化しました"); return }
        if (deleteFiles) {
            val gone = all.filter { Repository.cantPlay(it.id) && it.altUris.isEmpty() }
            remove(gone.map { it.id }.toSet())
            deleteFiles(all - gone.toSet())
        } else {
            remove(all.map { it.id }.toSet(), ignore = true)
            toast("${all.size} 曲をライブラリから外しました(ファイルは残っています)")
        }
        // 使わなくなった高画質ジャケットを片付け
        runCatching { java.io.File(activity.filesDir, "thumbs_hi").deleteRecursively() }
    }

    /**
     * ダウンロードを削除してオンラインに戻す: ファイルだけ消して、曲・プレイリスト・記録・編集内容は残す。
     * YouTube 由来の曲だけ(自分で入れたファイルは戻せないので対象外)
     */
    fun removeDownload(tracks: List<Track>) {
        val ok = tracks.filter { youtubeVideoId(it.sourceUrl) != null && !Repository.isMissing(it.id) }
        if (ok.isEmpty()) { toast("ネットに元がない曲は、ダウンロードを削除できません"); return }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(if (ok.size == 1) "ダウンロードを削除" else "${ok.size} 曲のダウンロードを削除")
            .setMessage("ファイルだけ削除します。曲はライブラリに残り、ネットから再生できます。" +
                (if (ok.size < tracks.size) "\n(自分で入れた曲 ${tracks.size - ok.size} 曲は対象外)" else ""))
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ -> deleteFiles(ok, keepInLibrary = true) }
            .show()
    }

    private fun deleteFiles(tracks: List<Track>, keepInLibrary: Boolean = false) {
        activity.lifecycleScope.launch {
            val needConsent = mutableListOf<Pair<Long, Uri>>()
            var legacy: IntentSender? = null
            val done = mutableSetOf<Long>()
            withContext(Dispatchers.IO) {
                for (t in tracks) {
                    // 本体と、重複としてまとめた予備ファイルをすべて削除
                    var ok = true
                    for (u in listOf(t.uri) + t.altUris) {
                        val uri = Uri.parse(u)
                        try {
                            if (DocumentsContract.isDocumentUri(activity, uri)) {
                                DocumentsContract.deleteDocument(activity.contentResolver, uri)
                            } else {
                                activity.contentResolver.delete(uri, null, null)
                            }
                        } catch (e: RecoverableSecurityException) {
                            needConsent += t.id to uri; ok = false
                            if (legacy == null) legacy = e.userAction.actionIntent.intentSender
                        } catch (e: SecurityException) {
                            needConsent += t.id to uri; ok = false
                        } catch (e: Exception) {
                            // 既に無いなど
                        }
                    }
                    if (ok) done += t.id
                }
            }
            if (keepInLibrary) Repository.markOnline(done) else remove(done)
            if (needConsent.isEmpty()) {
                toast(if (keepInLibrary) "${done.size} 曲のダウンロードを削除しました" else "${done.size} 曲を削除しました")
                return@launch
            }
            pendingKeep = keepInLibrary
            pendingIds = needConsent.map { it.first }.toSet()
            // Android 10 の個別許可は 1 件ずつなので、残りは後で再実行してもらう
            val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = needConsent.map { it.second }.filter { it.authority == MediaStore.AUTHORITY }
                if (uris.isNotEmpty()) MediaStore.createDeleteRequest(activity.contentResolver, uris).intentSender else null
            } else legacy
            if (sender != null) {
                deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                pendingIds = emptySet()
                toast("一部のファイルは削除できませんでした")
            }
        }
    }

    private fun remove(ids: Set<Long>, ignore: Boolean = false) {
        if (ids.isEmpty()) return
        Repository.removeTracks(ids, ignore)
        onRemoved(ids)
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()

    // ───────── 動画 ─────────

    /** 曲に動画(MV)を保存する(YouTube の曲だけ) */
    fun saveVideos(tracks: List<Track>) {
        // 1 曲で、動画があるかまだ分からない時: 先に調べて、無ければ知らせる
        val one = tracks.singleOrNull()
        val oneVid = one?.let { youtubeVideoId(it.sourceUrl) }
        if (one != null && oneVid != null && !one.hasVideo && VideoCheck.known(oneVid) == null) {
            activity.lifecycleScope.launch {
                if (VideoCheck.check(oneVid) == false) toast("この曲には動画がありません(ジャケットの静止画だけ)")
                else queueVideos(tracks)
            }
            return
        }
        queueVideos(tracks)
    }

    private fun queueVideos(tracks: List<Track>) {
        val n = DownloadManager.addVideos(activity, tracks)
        when {
            n == 0 -> toast(when {
                tracks.all { it.hasVideo } -> "動画は保存済みです"
                tracks.all { VideoCheck.known(youtubeVideoId(it.sourceUrl)) == false } -> "動画がない曲です(ジャケットの静止画だけ)"
                else -> "YouTube の曲だけ動画を保存できます"
            })
            VideoPrefs.wifiOnly(activity) && Net.metered -> toast("Wi-Fi につながったら $n 曲の動画を保存します")
            else -> toast("$n 曲の動画を保存します")
        }
    }

    /** 保存した動画だけ消す(曲と音声は残す) */
    fun removeVideos(tracks: List<Track>) {
        val ts = tracks.filter { it.hasVideo }
        if (ts.isEmpty()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(if (ts.size == 1) "動画を削除" else "${ts.size} 曲の動画を削除")
            .setMessage("動画のファイルだけ削除します(${StorageSheet.size(ts.sumOf { it.videoBytes })})。曲と音声はそのまま残ります")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ -> DownloadManager.removeVideos(activity, ts); toast("動画を削除しました") }
            .show()
    }

    /** 曲の「⋮」に動画の項目を足す */
    fun addVideoMenu(menu: android.view.Menu, t: Track) {
        if (t.hasVideo) { menu.add("🎬 動画を削除").setOnMenuItemClickListener { removeVideos(listOf(t)); true }; return }
        val vid = youtubeVideoId(t.sourceUrl) ?: return
        // 動画が無いと分かっている曲は押せない表示に。分からない曲は押した時に調べる
        when (VideoCheck.known(vid)) {
            false -> menu.add("🎬 動画なし(ジャケットの静止画だけ)").isEnabled = false
            else -> menu.add("🎬 動画も保存").setOnMenuItemClickListener { saveVideos(listOf(t)); true }
        }
    }

    /** まとめて: 動画を保存・削除 */
    fun addVideoGroupMenu(menu: android.view.Menu, tracks: List<Track>) {
        val can = tracks.filter { youtubeVideoId(it.sourceUrl) != null && !it.hasVideo }
        val have = tracks.filter { it.hasVideo }
        if (can.isNotEmpty()) menu.add("🎬 動画もまとめて保存(${can.size} 曲)").setOnMenuItemClickListener { saveVideos(can); true }
        if (have.isNotEmpty()) menu.add("🎬 動画を削除(${have.size} 曲)").setOnMenuItemClickListener { removeVideos(have); true }
    }
}

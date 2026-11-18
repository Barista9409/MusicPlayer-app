package com.example.ytmp3

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ダウンロードせずに、オンラインの曲としてライブラリに入れる(☁)。
 * あとで ⬇ でダウンロードすればオフラインでも聴ける。
 */
object OnlineLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ───────── 曲を「ライブラリに追加」する時のやり方 ─────────

    /** "ask" = 毎回選ぶ / "online" = いつも ☁ / "download" = いつも ⬇ */
    fun addMode(c: Context): String = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("add_mode", "ask") ?: "ask"
    fun setAddMode(c: Context, m: String) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("add_mode", m).apply()
    fun addModeLabel(c: Context) = when (addMode(c)) { "online" -> "いつも ☁ オンライン"; "download" -> "いつも ⬇ ダウンロード"; else -> "毎回選ぶ" }

    /**
     * 1 曲をライブラリに追加。☁ オンライン(ダウンロードしない)か ⬇ ダウンロードかを、設定に合わせて(または選んでもらって)
     */
    fun addSong(context: Context, r: Searcher.Result, onDone: () -> Unit = {}, download: () -> Unit) {
        fun online() {
            add(r)?.let { Toast.makeText(context, "☁「${r.title}」を追加しました", Toast.LENGTH_SHORT).show() }
            onDone()
        }
        fun dl() { download(); onDone() }
        when (addMode(context)) {
            "online" -> online()
            "download" -> dl()
            else -> com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("「${r.title}」をライブラリに追加")
                .setItems(arrayOf(
                    "☁ オンラインで追加(容量を使わない)",
                    "⬇ ダウンロードして追加",
                )) { _, i -> if (i == 0) online() else dl() }
                .setNegativeButton("キャンセル", null)
                .setNeutralButton("毎回聞かない…") { _, _ ->
                    val modes = arrayOf("online", "download")
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                        .setTitle("これからは")
                        .setItems(arrayOf("いつも ☁ オンラインで追加", "いつも ⬇ ダウンロードして追加")) { _, i ->
                            setAddMode(context, modes[i])
                            if (i == 0) online() else dl()
                            Toast.makeText(context, "設定 → 📁 ライブラリ で変えられます", Toast.LENGTH_SHORT).show()
                        }
                        .show()
                }
                .show()
        }
    }

    /** 1 曲(すでにライブラリにあればその曲) */
    fun add(r: Searcher.Result): Track? {
        // 聴けない曲(🔒)で別の音源が見つかっていれば、そちらで追加(追加しても聴けない曲を入れないように)
        val vid = AltSources.playableId(r.videoId, r.restricted) ?: return null
        VideoAlias.trackFor(vid, includeMissing = true)?.let { return it }
        val t = Track(
            id = Repository.newId(), title = r.title, artist = r.artist, album = "", uri = "",
            savedAt = System.currentTimeMillis(), sizeBytes = 0, durationMs = r.durationSec * 1000, location = "",
            thumbPath = null, sourceUrl = "https://music.youtube.com/watch?v=$vid",
        )
        Repository.addTrack(t)
        Repository.markOnline(setOf(t.id))
        // ジャケットを保存(ライブラリ・プレイリストで表示)
        r.thumb?.let { url ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val f = java.io.File(Repository.thumbDir, "online_${t.id}.jpg")
                    java.net.URL(PlayerConnection.bigArt(url)).openStream().use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    Repository.track(t.id)?.let { cur -> Repository.updateTracks(listOf(cur.copy(thumbPath = f.absolutePath))) }
                }
            }
        }
        return t
    }

    /** アルバム・プレイリストの曲をまとめてオンラインで追加し、同じ名前のプレイリストを作る */
    fun addList(context: Context, list: Searcher.Result) {
        val app = context.applicationContext
        Toast.makeText(app, "「${list.title}」の曲を読み込み中…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val songs = runCatching { withContext(Dispatchers.IO) { Searcher.listTracks(list) } }.getOrDefault(emptyList())
            if (songs.isEmpty()) { Toast.makeText(app, "曲を読み込めませんでした", Toast.LENGTH_SHORT).show(); return@launch }
            val ids = songs.mapNotNull { add(it)?.id }.distinct()
            val p = Repository.createPlaylist(list.title)
            Repository.setPlaylistOrder(p.id, ids)
            Toast.makeText(app, "プレイリスト「${list.title}」を作りました(☁ ${ids.size} 曲・ダウンロードなし)", Toast.LENGTH_LONG).show()
        }
    }
}

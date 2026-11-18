package com.example.ytmp3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File

/**
 * 曲の情報(タイトル・アーティスト・アルバム・トラック番号・ジャケット)をアプリ内で編集する。
 * 複数曲の時はアーティスト・アルバム・ジャケットをまとめて変更(空欄は変更しない)。
 * ファイル自体のタグは書き換えない(アプリの記録だけ)。
 */
class TrackEditor(private val activity: AppCompatActivity) {
    private var onImage: ((Uri) -> Unit)? = null
    /** 書き込みの許可待ちの曲 */
    private var waitingTags: List<Track> = emptyList()
    private val writeRequest = activity.registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val list = waitingTags
        waitingTags = emptyList()
        if (res.resultCode == android.app.Activity.RESULT_OK) writeTags(list, askPermission = false)
    }

    /** ファイルのタグにも書き込む(設定でオフにできる) */
    private fun writeTags(tracks: List<Track>, askPermission: Boolean = true) {
        if (!TagWriter.isOn(activity) || tracks.isEmpty()) return
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val need = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TagWriter.writeAll(app, tracks) }
            if (need.isEmpty() || !askPermission) return@launch
            // このアプリで保存していないファイルは、本人の許可をもらってから
            val uris = need.map { it.second }.filter { it.authority == android.provider.MediaStore.AUTHORITY }
            if (uris.isEmpty()) return@launch
            waitingTags = need.map { it.first }
            runCatching {
                val sender = android.provider.MediaStore.createWriteRequest(activity.contentResolver, uris).intentSender
                writeRequest.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
            }
        }
    }
    private val pickImage = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImage?.invoke(uri)
    }

    fun edit(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val single = tracks.size == 1
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_track, null)
        val art = v.findViewById<ImageView>(R.id.art)
        val title = v.findViewById<TextInputEditText>(R.id.title)
        val artist = v.findViewById<TextInputEditText>(R.id.artist)
        val album = v.findViewById<TextInputEditText>(R.id.album)
        val trackNo = v.findViewById<TextInputEditText>(R.id.trackNo)
        val note = v.findViewById<TextView>(R.id.note)

        fun common(f: (Track) -> String) = tracks.map(f).distinct().singleOrNull().orEmpty()
        val t0 = tracks[0]
        Thumbs.bind(art, tracks.firstOrNull { it.thumbPath != null }?.thumbPath)
        artist.setText(common { it.artist })
        album.setText(common { it.album })
        if (single) {
            title.setText(t0.title)
            if (t0.trackNo > 0) trackNo.setText(t0.trackNo.toString())
            note.text = "ファイルのタグは変わりません(このアプリでの表示だけ変わります)"
        } else {
            v.findViewById<View>(R.id.titleBox).visibility = View.GONE
            v.findViewById<View>(R.id.trackNoBox).visibility = View.GONE
            v.findViewById<View>(R.id.cleanTitle).visibility = View.GONE
            note.text = "${tracks.size} 曲をまとめて変更します。空欄の項目は変更しません。\n「✨ タイトルを整える」は 1 曲ずつ、またはまとめて整理メニューから"
        }

        // ジャケット
        var newThumb: String? = null
        v.findViewById<View>(R.id.pickArt).setOnClickListener {
            onImage = { uri ->
                val path = saveImage(uri)
                if (path == null) Toast.makeText(activity, "画像を読み込めませんでした", Toast.LENGTH_SHORT).show()
                else { newThumb = path; Thumbs.bind(art, path) }
            }
            runCatching { pickImage.launch("image/*") }
        }
        // タイトルを整える
        v.findViewById<View>(R.id.cleanTitle).setOnClickListener {
            val (t, a) = cleanTitle(title.text.toString(), artist.text.toString())
            title.setText(t)
            if (artist.text.isNullOrBlank() && a.isNotBlank()) artist.setText(a)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(if (single) "曲の情報を編集" else "${tracks.size} 曲の情報を編集")
            .setView(v)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val a = artist.text.toString().trim()
                val al = album.text.toString().trim()
                val updated = tracks.map { t ->
                    if (single) t.copy(
                        title = title.text.toString().trim().ifBlank { t.title },
                        artist = a, album = al,
                        trackNo = trackNo.text.toString().toIntOrNull() ?: t.trackNo,
                        thumbPath = newThumb ?: t.thumbPath, edited = true,
                    ) else t.copy(
                        artist = a.ifBlank { t.artist }, album = al.ifBlank { t.album },
                        thumbPath = newThumb ?: t.thumbPath, edited = true,
                    )
                }
                Repository.updateTracks(updated)
                writeTags(updated)
                Toast.makeText(activity, "保存しました", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 複数曲のタイトルを一度に整える */
    fun cleanTitles(tracks: List<Track>) {
        val changes = tracks.mapNotNull { t ->
            val (nt, na) = cleanTitle(t.title, t.artist)
            val newArtist = if (t.artist.isBlank() && na.isNotBlank()) na else t.artist
            if (nt != t.title || newArtist != t.artist) t.copy(title = nt, artist = newArtist, edited = true) else null
        }
        if (changes.isEmpty()) { Toast.makeText(activity, "整えるタイトルはありませんでした", Toast.LENGTH_SHORT).show(); return }
        val sample = changes.take(5).joinToString("\n") { c -> "・${tracks.first { it.id == c.id }.title}\n　→ ${c.title}" }
        MaterialAlertDialogBuilder(activity)
            .setTitle("${changes.size} 曲のタイトルを整える")
            .setMessage(sample + if (changes.size > 5) "\n…ほか ${changes.size - 5} 曲" else "")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("整える") { _, _ ->
                Repository.updateTracks(changes)
                writeTags(changes)
                Toast.makeText(activity, "${changes.size} 曲を整えました", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 選んだ画像を 600px 以内に縮めて保存 */
    private fun saveImage(uri: Uri): String? = runCatching {
        val src = activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: error("decode")
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = if (side > 600) Bitmap.createScaledBitmap(square, 600, 600, true) else square
        val f = File(Repository.thumbDir, "edit_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        f.absolutePath
    }.getOrNull()

    companion object {
        private val NOISE = Regex(
            "\\b(official|video|audio|lyrics?|visualizer|m/?v|music\\s*video|full\\s*ver(sion)?|hd|4k)\\b|" +
                "公式|ミュージックビデオ|歌詞|リリック|フルバージョン",
            RegexOption.IGNORE_CASE
        )

        /** 「(Official Video)」「【MV】」などを外し、「アーティスト - 曲名」なら分ける。→ (タイトル, 見つけたアーティスト) */
        fun cleanTitle(title: String, artist: String): Pair<String, String> {
            var t = title
            // かっこの中に宣伝的な言葉があれば、かっこごと消す
            t = Regex("[(\\[【（「『]([^)\\]】）」』]*)[)\\]】）」』]").replace(t) { m ->
                if (NOISE.containsMatchIn(m.groupValues[1])) "" else m.value
            }
            // 「| ○○」「/ ○○」の後ろが宣伝なら消す
            t = Regex("\\s*[|｜/／]\\s*([^|｜/／]*)$").replace(t) { m -> if (NOISE.containsMatchIn(m.groupValues[1])) "" else m.value }
            // 末尾の「Official Music Video」など
            t = Regex("\\s*(official\\s*(music\\s*)?(video|audio)|music\\s*video|\\bmv)\\s*$", RegexOption.IGNORE_CASE).replace(t, "")
            var foundArtist = ""
            // 「アーティスト - 曲名」
            val dash = Regex("^(.+?)\\s+[-–—]\\s+(.+)$").find(t)
            if (dash != null) {
                val (left, right) = dash.destructured
                if (artist.isBlank() || left.trim().equals(artist.trim(), ignoreCase = true)) {
                    foundArtist = left.trim(); t = right
                }
            }
            // 「アーティスト『曲名』」「アーティスト「曲名」」
            Regex("^(.+?)\\s*[「『](.+?)[」』]\\s*$").find(t)?.let { m ->
                val (left, inner) = m.destructured
                if (artist.isBlank() || left.trim().equals(artist.trim(), ignoreCase = true)) {
                    foundArtist = left.trim(); t = inner
                }
            }
            t = t.replace(Regex("\\s{2,}"), " ").trim().trim('-', '–', '|', '/').trim()
            return (t.ifBlank { title }) to foundArtist
        }
    }
}

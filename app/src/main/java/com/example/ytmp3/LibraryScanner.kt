package com.example.ytmp3

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * 端末のフォルダ(既定: Music)にある曲をライブラリに取り込み、
 * ライブラリの曲のファイルが存在するかを確認する。
 */
object LibraryScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _lastResult = MutableStateFlow("")
    val lastResult: StateFlow<String> = _lastResult.asStateFlow()

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    /** 読み込むフォルダ(相対パス、末尾 "/")。"" は内部ストレージ全体 */
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    private const val PREFS = "settings"
    private const val KEY_FOLDERS = "scan_folders"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FOLDERS, null)
        _folders.value = if (raw == null) listOf("Music/") else runCatching {
            val a = JSONArray(raw); List(a.length()) { a.getString(it) }
        }.getOrDefault(listOf("Music/"))
    }

    fun setFolders(context: Context, list: List<String>) {
        val norm = list.map { normalize(it) }.distinct()
        _folders.value = norm
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FOLDERS, JSONArray(norm).toString()).apply()
    }

    fun normalize(path: String): String {
        val p = path.trim().trim('/')
        return if (p.isEmpty()) "" else "$p/"
    }

    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    /** 取り込み + 存在確認(バックグラウンド) */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { refreshNow(app) }
    }

    /** 存在確認だけ */
    fun verify(context: Context) {
        val app = context.applicationContext
        scope.launch { mutex.withLock { verifyNow(app) } }
    }

    suspend fun refreshNow(context: Context) = mutex.withLock {
        _scanning.value = true
        try {
            if (hasPermission(context)) scanNow(context)
            verifyNow(context)
            // ダウンロードした曲どうしの重複もまとめる
            Repository.mergeDuplicates()
        } catch (e: Exception) {
            _lastResult.value = "読み込みに失敗: ${e.message}"
        } finally {
            _scanning.value = false
        }
    }

    // ───────── scan ─────────

    private class Row(
        val id: Long, val title: String, val artist: String, val album: String, val albumId: Long,
        val duration: Long, val size: Long, val name: String, val folder: String, val added: Long,
        val trackNo: Int, val discNo: Int,
    )

    private fun scanNow(context: Context) {
        val folders = _folders.value
        if (folders.isEmpty()) {
            _lastResult.value = "読み込むフォルダが設定されていません"
            return
        }
        val rows = queryAudio(context, folders)
        val existing = Repository.tracks.value
        val byKey = HashMap<String, Track>()
        existing.forEach { t -> t.allKeys.forEach { k -> byKey[k] = t } }
        val ignored = Repository.ignored.value
        val news = mutableListOf<Track>()
        val updates = mutableListOf<Track>()
        val albumThumbs = HashMap<Long, String?>()

        for (r in rows) {
            val key = (r.folder + r.name).lowercase()
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, r.id).toString()
            if (key in ignored) continue
            val old = byKey[key]
            if (old != null) {
                if (old.fileKey != key) {
                    // 重複としてまとめた予備ファイル。トラック番号だけ補う
                    if (old.trackNo == 0 && r.trackNo > 0) updates += old.copy(trackNo = r.trackNo, discNo = r.discNo)
                    continue
                }
                // 既存: フォルダ読み込み由来の曲は情報を更新。ダウンロードした曲はトラック番号だけ補う
                if (old.source == Track.SOURCE_SCAN && old.edited) {
                    // アプリで編集した曲は名前などを上書きしない(場所と長さだけ更新)
                    if (old.uri != uri || old.durationMs != r.duration)
                        updates += old.copy(uri = uri, durationMs = r.duration, sizeBytes = r.size)
                } else if (old.source == Track.SOURCE_SCAN &&
                    (old.title != r.title || old.artist != r.artist || old.album != r.album || old.uri != uri ||
                        old.trackNo != r.trackNo || old.discNo != r.discNo)
                ) {
                    updates += old.copy(title = r.title, artist = r.artist, album = r.album, uri = uri,
                        durationMs = r.duration, sizeBytes = r.size, trackNo = r.trackNo, discNo = r.discNo)
                } else if (old.source != Track.SOURCE_SCAN && (old.uri != uri || (old.trackNo == 0 && r.trackNo > 0))) {
                    // 同じ場所のファイル: 機種変更・復元でファイルの番号が変わっていたら付け直す
                    updates += old.copy(uri = uri,
                        trackNo = if (old.trackNo == 0) r.trackNo else old.trackNo,
                        discNo = if (old.trackNo == 0) r.discNo else old.discNo)
                }
                continue
            }
            val thumb = albumThumbs.getOrPut(r.albumId) { albumThumb(context, r.albumId) }
            news += Track(
                id = Repository.newId(), title = r.title, artist = r.artist, album = r.album,
                uri = uri, savedAt = r.added, sizeBytes = r.size, durationMs = r.duration,
                location = SaveLocation.folderLabel(r.folder), thumbPath = thumb, sourceUrl = "",
                folder = r.folder, fileName = r.name, source = Track.SOURCE_SCAN,
                trackNo = r.trackNo, discNo = r.discNo,
            )
        }
        Repository.updateTracks(updates.associateBy { it.id }.values.toList())
        Repository.addTracks(news)
        val merged = Repository.mergeDuplicates()
        _lastResult.value = buildString {
            append(if (news.isEmpty()) "新しい曲はありませんでした(${rows.size} 曲を確認)" else "${news.size} 曲を追加しました")
            if (merged > 0) append(" · 重複 $merged 曲をまとめました")
        }
    }

    private fun queryAudio(context: Context, folders: List<String>): List<Row> {
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
        )
        val all = folders.any { it.isEmpty() }
        val sel = StringBuilder("${MediaStore.Audio.Media.IS_MUSIC} != 0")
        val args = mutableListOf<String>()
        if (!all) {
            sel.append(" AND (")
            folders.forEachIndexed { i, f ->
                if (i > 0) sel.append(" OR ")
                sel.append("${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?")
                args += "$f%"
            }
            sel.append(")")
        }
        val out = mutableListOf<Row>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel.toString(), args.toTypedArray(), null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(7) ?: continue
                out += Row(
                    id = c.getLong(0),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                    artist = c.getString(2)?.takeIf { it != "<unknown>" }.orEmpty(),
                    album = c.getString(3)?.takeIf { it != "<unknown>" }.orEmpty(),
                    albumId = c.getLong(4),
                    duration = c.getLong(5),
                    size = c.getLong(6),
                    name = name,
                    folder = c.getString(8).orEmpty(),
                    added = c.getLong(9) * 1000,
                    // TRACK は "ディスク番号 * 1000 + トラック番号"
                    trackNo = c.getInt(10) % 1000,
                    discNo = c.getInt(10) / 1000,
                )
            }
        }
        return out
    }

    /** アルバムのジャケット画像を thumbs/album_<id>.jpg に保存 */
    private fun albumThumb(context: Context, albumId: Long): String? {
        if (albumId <= 0) return null
        val f = File(Repository.thumbDir, "album_$albumId.jpg")
        if (f.exists()) return f.absolutePath
        return try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
            val bmp = context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            f.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    // ───────── verify ─────────

    private fun verifyNow(context: Context) {
        val tracks = Repository.tracks.value
        val canReadAll = hasPermission(context)
        val missing = mutableSetOf<Long>()
        val backfill = mutableListOf<Track>()

        // MediaStore の曲はまとめて問い合わせる
        val media = tracks.filter { Uri.parse(it.uri).authority == MediaStore.AUTHORITY }
        val found = HashMap<Long, Pair<String, String>>() // mediaId -> (folder, name)
        media.mapNotNull { runCatching { ContentUris.parseId(Uri.parse(it.uri)) }.getOrNull() }
            .chunked(400).forEach { ids ->
                val sel = "${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",")})"
                runCatching {
                    context.contentResolver.query(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
                        sel, null, null
                    )?.use { c ->
                        while (c.moveToNext()) found[c.getLong(0)] = c.getString(1).orEmpty() to c.getString(2).orEmpty()
                    }
                }
            }
        for (t in media) {
            val mid = runCatching { ContentUris.parseId(Uri.parse(t.uri)) }.getOrNull() ?: continue
            val hit = found[mid]
            if (hit == null) {
                // 権限がないと他アプリのファイルは見えないので、その場合は判定しない
                if (canReadAll || t.source == Track.SOURCE_DOWNLOAD) missing += t.id
            } else if (t.fileName.isEmpty()) {
                backfill += t.copy(folder = hit.first, fileName = hit.second)
            }
        }

        // SAF(フォルダ選択で保存した曲)
        for (t in tracks) {
            val uri = Uri.parse(t.uri)
            if (uri.authority == MediaStore.AUTHORITY) continue
            val exists = runCatching {
                if (DocumentsContract.isDocumentUri(context, uri)) DocumentFile.fromSingleUri(context, uri)?.exists() == true
                else true
            }.getOrDefault(true)
            if (!exists) missing += t.id
            else if (t.fileName.isEmpty() && DocumentsContract.isDocumentUri(context, uri)) {
                val rel = SaveLocation.relativeFromDocId(runCatching { DocumentsContract.getDocumentId(uri) }.getOrDefault(""))
                if (rel.isNotEmpty()) {
                    val folder = rel.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                    backfill += t.copy(folder = folder, fileName = rel.substringAfterLast('/'))
                }
            }
        }
        Repository.updateTracks(backfill)

        // 本体が見つからない曲は、重複としてまとめた予備ファイルに切り替える
        for (t in tracks.filter { it.id in missing && it.altUris.isNotEmpty() }) {
            val alt = t.altUris.firstOrNull { exists(context, Uri.parse(it), canReadAll) } ?: continue
            val idx = t.altUris.indexOf(alt)
            Repository.promoteAlt(t.id, alt, t.altKeys.getOrNull(idx))
            missing -= t.id
        }
        Repository.setMissing(missing)
    }

    private fun exists(context: Context, uri: Uri, canReadAll: Boolean): Boolean = runCatching {
        if (uri.authority == MediaStore.AUTHORITY) {
            val id = ContentUris.parseId(uri)
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns._ID} = $id", null, null
            )?.use { it.count > 0 } ?: false
        } else if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentFile.fromSingleUri(context, uri)?.exists() == true
        } else true
    }.getOrDefault(false)
}

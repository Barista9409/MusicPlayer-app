package com.example.ytmp3

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class SavedFile(val uri: Uri, val folder: String, val fileName: String)

/**
 * 保存先の管理。
 * 既定はダウンロードフォルダ(MediaStore 経由・権限不要)。
 * ユーザーがフォルダを選んだ場合は SAF のツリー URI に保存する。
 */
class SaveLocation(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)?.takeIf { u ->
            // バックアップから戻した時など、許可が無い保存先は使わない(既定に戻す)
            context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
        }
        private set(value) = prefs.edit().putString(KEY_TREE, value?.toString()).apply()

    val isDefault get() = treeUri == null

    fun label(): String {
        val uri = treeUri ?: return "ダウンロード (既定)"
        return try {
            folderLabel(relativeFromDocId(DocumentsContract.getTreeDocumentId(uri)), volumeOf(DocumentsContract.getTreeDocumentId(uri)))
        } catch (e: Exception) {
            DocumentFile.fromTreeUri(context, uri)?.name ?: uri.toString()
        }
    }

    fun setTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // 以前の保存先の権限は解放しない(そこに保存した曲を再生し続けるため)
        context.contentResolver.takePersistableUriPermission(uri, flags)
        treeUri = uri
    }

    fun resetToDefault() {
        treeUri = null
    }

    /** 高速モード: MP3 に変換せず m4a(AAC)のまま保存(既定: オン) */
    var fastMode: Boolean
        get() = prefs.getBoolean(KEY_FAST, true)
        set(value) = prefs.edit().putBoolean(KEY_FAST, value).apply()

    /** ダウンロード時にアーティスト/アルバムでフォルダ分けするか(既定: する) */
    var organize: Boolean
        get() = prefs.getBoolean(KEY_ORGANIZE, true)
        set(value) = prefs.edit().putBoolean(KEY_ORGANIZE, value).apply()

    /**
     * src を保存先にコピーする。
     * フォルダ分けが有効なら 保存先/MusicPlayer/<sub...>/<fileName> に保存する。
     */
    fun save(src: File, sub: List<String>, fileName: String): SavedFile {
        val dirs = if (organize) listOf(APP_DIR) + sub.filter { it.isNotBlank() } else emptyList()
        val name = if (organize) fileName else src.name
        val tree = treeUri
        return if (tree == null) saveToDownloads(src, dirs, name) else saveToTree(tree, src, dirs, name)
    }

    private fun saveToDownloads(src: File, dirs: List<String>, fileName: String): SavedFile {
        val relative = (listOf(Environment.DIRECTORY_DOWNLOADS) + dirs).joinToString("/") + "/"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(fileName))
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        // 名前が原因で作れない場合は、簡単な名前 → 日時の名前 の順に再挑戦
        var inserted: Uri? = null
        for ((dirsTry, nameTry) in candidates(dirs, fileName)) {
            values.put(MediaStore.Downloads.DISPLAY_NAME, nameTry)
            values.put(MediaStore.Downloads.RELATIVE_PATH, (listOf(Environment.DIRECTORY_DOWNLOADS) + dirsTry).joinToString("/") + "/")
            inserted = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            if (inserted != null) break
        }
        val uri: Uri = inserted ?: throw IllegalStateException("ダウンロードフォルダに保存できません")
        try {
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        // 同名ファイルがあると "(1)" 付きに改名されるので実際の名前を取得
        var name = fileName
        var folder = relative
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    c.getString(1)?.let { folder = it }
                }
            }
        }
        return SavedFile(uri, folder, name)
    }

    private fun saveToTree(tree: Uri, src: File, dirs: List<String>, fileName: String): SavedFile {
        var dir = DocumentFile.fromTreeUri(context, tree)
        if (dir == null || !dir.canWrite()) {
            throw IllegalStateException("保存先フォルダに書き込めません。保存先を選び直してください")
        }
        val root = dir!!
        var created: DocumentFile? = null
        // 名前が原因で作れない場合は、簡単な名前 → 日時の名前 の順に再挑戦
        for ((dirsTry, nameTry) in candidates(dirs, fileName)) {
            var d: DocumentFile? = root
            for (name in dirsTry) {
                val parent = d ?: break
                val existing = runCatching { parent.findFile(name) }.getOrNull()
                d = if (existing != null && existing.isDirectory) existing
                else runCatching { parent.createDirectory(name) }.getOrNull()
            }
            val parent = d ?: continue
            created = runCatching { parent.createFile(mimeOf(nameTry), nameTry.substringBeforeLast('.')) }.getOrNull()
            if (created != null) break
        }
        val doc: DocumentFile = created ?: throw IllegalStateException("保存先にファイルを作成できません(${label()})")
        try {
            context.contentResolver.openOutputStream(doc.uri)!!.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
        val docId = runCatching { DocumentsContract.getDocumentId(doc.uri) }.getOrNull().orEmpty()
        val rel = relativeFromDocId(docId)
        val name = doc.name ?: rel.substringAfterLast('/')
        val folder = rel.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        return SavedFile(doc.uri, folder, name)
    }

    /** 保存を試す (フォルダ, ファイル名) の候補 */
    private fun candidates(dirs: List<String>, fileName: String): List<Pair<List<String>, String>> {
        val ext = "." + fileName.substringAfterLast('.', "mp3")
        val base = fileName.substringBeforeLast('.')
        val simpleDirs = dirs.map { FileNames.simple(it, 80).ifEmpty { "_" } }
        val simpleName = FileNames.simple(base, 120).ifEmpty { "audio" } + ext
        val stamp = "audio_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ext
        return listOf(
            dirs to fileName,
            simpleDirs to simpleName,
            dirs.take(1) to stamp,
            emptyList<String>() to stamp,
        ).distinct()
    }

    /**
     * 既に保存した曲(既定のダウンロードフォルダ分)をフォルダ分けし直す。
     * @return 移動した曲数
     */
    fun reorganizeExisting(tracks: List<Track>, safe: (String) -> String): Int {
        val resolver = context.contentResolver
        var moved = 0
        val updated = mutableListOf<Track>()
        for (t in tracks) {
            val uri = Uri.parse(t.uri)
            if (t.source != Track.SOURCE_DOWNLOAD || uri.authority != MediaStore.AUTHORITY) continue
            if (!t.folder.startsWith(Environment.DIRECTORY_DOWNLOADS + "/")) continue
            val sub = buildList {
                add(safe(t.artist).ifEmpty { "不明なアーティスト" })
                if (t.album.isNotBlank()) add(safe(t.album))
            }
            val relative = (listOf(Environment.DIRECTORY_DOWNLOADS, APP_DIR) + sub).joinToString("/") + "/"
            if (t.folder.equals(relative, ignoreCase = true)) continue
            val ext = t.fileName.substringAfterLast('.', "mp3")
            val name = (if (t.trackNo > 0) String.format("%02d ", t.trackNo) else "") + safe(t.title).ifEmpty { "audio" } + ".$ext"
            try {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                }
                if (resolver.update(uri, v, null, null) > 0) {
                    var folder = relative
                    var fname = name
                    runCatching {
                        resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) { c.getString(0)?.let { folder = it }; c.getString(1)?.let { fname = it } }
                        }
                    }
                    updated += t.copy(folder = folder, fileName = fname, location = folderLabel(folder))
                    moved++
                }
            } catch (_: Exception) {
                // 他アプリのファイル等は移動できない
            }
        }
        Repository.updateTracks(updated)
        return moved
    }

    companion object {
        private const val KEY_TREE = "save_tree_uri"
        private const val KEY_ORGANIZE = "organize_downloads"
        private const val KEY_FAST = "fast_mode_m4a"

        fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "m4a", "aac" -> "audio/mp4"
            // 動画(音楽の一覧に混ざらないよう動画として保存)
            "mp4" -> "video/mp4"
            "opus", "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        const val APP_DIR = "MusicPlayer"

        /** "primary:Music/Foo" → "Music/Foo" */
        fun relativeFromDocId(docId: String) = docId.substringAfter(':', "")

        fun volumeOf(docId: String) = docId.substringBefore(':')

        fun folderLabel(relative: String, volume: String = "primary"): String {
            val vol = if (volume == "primary") "内部ストレージ" else "SDカード"
            val p = relative.trim('/')
            return if (p.isEmpty()) vol else "$vol/$p"
        }
    }
}

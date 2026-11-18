package com.example.ytmp3

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * バックアップ(1 つの .zip): 曲の一覧と編集内容・プレイリスト・再生の記録とピン留め・アラーム・設定・ジャケット画像。
 * 曲のファイル自体は含まない(端末の Music / Download フォルダはそのまま使う)。
 */
object Backup {
    internal val FILES = listOf("history.json", "playlists.json", "ignored.json", "playlog.json", "alarms.json", "liked.json", "online.json", "video_alias.json", "no_good.json", "listen_time.json")
    private val PREFS = listOf("settings", "library_ui", "search", "playback_queue")
    /** 端末ごとの許可なので持ち越さない */
    private val SKIP_KEYS = setOf("save_tree_uri")
    private const val MANIFEST = "musicplayer-backup.json"
    /** バックアップの拡張子(.zip)。以前の .mpbackup も復元できる */
    const val EXT = "zip"
    const val MIME = "application/zip"
    /** 復元した直後(端末に無い曲の取り直しを聞く) */
    const val KEY_JUST_RESTORED = "just_restored"

    fun fileName(): String {
        val d = android.text.format.DateFormat.format("yyyyMMdd-HHmm", System.currentTimeMillis())
        return "MusicPlayer-backup-$d.$EXT"
    }

    /** 書き出し。戻り値は入れた曲数 */
    fun export(context: Context, uri: Uri): Int {
        val dir = context.filesDir
        // 同じファイルに上書き(自動バックアップ)するので "wt"(使えない保存先なら普通に開く)
        val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri)
        out?.use { os ->
            ZipOutputStream(os.buffered()).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                val v = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                put(MANIFEST, JSONObject().put("app", "MusicPlayer").put("version", v).put("time", System.currentTimeMillis())
                    .toString().toByteArray())
                FILES.forEach { n -> File(dir, n).takeIf { it.exists() }?.let { put("files/$n", it.readBytes()) } }
                File(dir, "thumbs").listFiles()?.forEach { f -> if (f.isFile) put("thumbs/${f.name}", f.readBytes()) }
                put("prefs.json", prefsJson(context).toString().toByteArray())
            }
        } ?: error("保存先を開けません")
        return Repository.tracks.value.size
    }

    /** 復元(今のデータは置き換え)。終わったらアプリを再起動する必要がある */
    fun restore(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}) {
        val dir = context.filesDir
        val tmp = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var ok = false
        // 進み具合: 読んだバイト数 / ファイルの大きさ(展開 90% + 書き戻し 10%)
        val total = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        var lastPct = -1
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val ins = object : java.io.FilterInputStream(raw) {
                var read = 0L
                private fun tick(n: Long) {
                    if (n <= 0 || total <= 0) return
                    read += n
                    val p = (read * 90 / total).toInt().coerceIn(0, 90)
                    if (p != lastPct) { lastPct = p; onProgress(p) }
                }
                override fun read(): Int = super.read().also { if (it >= 0) tick(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { tick(it.toLong()) }
            }
            ZipInputStream(ins.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    // 想定外の場所には書かない
                    if (e.isDirectory || name.contains("..") || name.startsWith("/")) continue
                    if (name == MANIFEST) ok = true
                    val out = File(tmp, name)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        } ?: error("ファイルを開けません")
        if (!ok) error("MusicPlayer のバックアップではありません")

        // ジャケット
        val thumbs = File(dir, "thumbs").apply { mkdirs() }
        File(tmp, "thumbs").listFiles()?.forEach { f -> f.copyTo(File(thumbs, f.name), overwrite = true) }
        // ファイル(曲の一覧は、ジャケットの場所をこの端末の場所に付け直す)
        FILES.forEach { n ->
            val src = File(tmp, "files/$n").takeIf { it.exists() } ?: return@forEach
            if (n == "history.json") {
                val arr = runCatching { JSONArray(src.readText()) }.getOrNull()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val p = o.optString("thumbPath")
                        if (p.isNotBlank()) {
                            val local = File(thumbs, File(p).name)
                            o.put("thumbPath", if (local.exists()) local.absolutePath else "")
                        }
                    }
                    File(dir, n).writeText(arr.toString())
                    return@forEach
                }
            }
            src.copyTo(File(dir, n), overwrite = true)
        }
        onProgress(95)
        // 設定
        File(tmp, "prefs.json").takeIf { it.exists() }?.let { applyPrefs(context, JSONObject(it.readText())) }
        onProgress(100)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(KEY_JUST_RESTORED, true).commit()
        tmp.deleteRecursively()
    }

    private fun prefsJson(context: Context): JSONObject {
        val o = JSONObject()
        for (name in PREFS) {
            val p = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                if (k in SKIP_KEYS || v == null) return@forEach
                val e = JSONObject()
                when (v) {
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is String -> e.put("t", "s").put("v", v)
                    is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                    else -> return@forEach
                }
                p.put(k, e)
            }
            o.put(name, p)
        }
        return o
    }

    private fun applyPrefs(context: Context, o: JSONObject) {
        for (name in PREFS) {
            val p = o.optJSONObject(name) ?: continue
            val ed = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            p.keys().forEach { k ->
                if (k in SKIP_KEYS) return@forEach
                val e = p.optJSONObject(k) ?: return@forEach
                when (e.optString("t")) {
                    "b" -> ed.putBoolean(k, e.optBoolean("v"))
                    "i" -> ed.putInt(k, e.optInt("v"))
                    "l" -> ed.putLong(k, e.optLong("v"))
                    "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                    "s" -> ed.putString(k, e.optString("v"))
                    "ss" -> e.optJSONArray("v")?.let { a -> ed.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet()) }
                }
            }
            ed.commit()
        }
    }
}

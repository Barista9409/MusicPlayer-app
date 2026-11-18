package com.example.ytmp3

import android.content.Context
import android.net.Uri
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.Artwork
import java.io.File

/**
 * 「情報を編集」した内容を、音楽ファイル自体のタグ(タイトル・アーティスト・アルバム・トラック番号・ジャケット)にも書き込む。
 * 一時ファイルにコピー → タグを書く → 元の場所に上書き。
 */
object TagWriter {
    /** 書き込みに本人の許可が要る(このアプリで保存していないファイル) */
    class NeedPermission(val uri: Uri) : Exception("permission")

    /** 再生中の曲(書き換えると再生が止まるので、曲が変わってから書く) */
    @Volatile var playingId: String? = null
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** 再生中で後回しにした曲を書く(再生サービスが曲の切り替わりで呼ぶ) */
    fun flushPending(c: Context) {
        for (id in pending.toList()) {
            if (id.toString() == playingId) continue
            pending.remove(id)
            Repository.track(id)?.let { t -> runCatching { write(c, t) } }
        }
    }

    /** 書き込み(再生中の曲は後回し)。戻り値: 許可が要った曲 */
    fun writeAll(c: Context, tracks: List<Track>): List<Pair<Track, Uri>> {
        val need = mutableListOf<Pair<Track, Uri>>()
        for (t in tracks) {
            if (t.id.toString() == playingId) { pending += t.id; continue }
            // ファイルの無い曲(☁ オンライン)は、アプリの中だけで変更
            if (Repository.isMissing(t.id) || t.uri.isBlank()) continue
            try { write(c, t) } catch (e: NeedPermission) { need += t to e.uri } catch (_: Exception) { }
        }
        return need
    }

    fun isOn(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("write_tags", true)
    fun setOn(c: Context, on: Boolean) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("write_tags", on).apply()

    /** 1 曲書き込む(裏で呼ぶ)。許可が要る時は NeedPermission */
    fun write(c: Context, t: Track) {
        val uri = Uri.parse(t.uri)
        val ext = t.fileName.substringAfterLast('.', "").lowercase().ifBlank { "m4a" }
        if (ext !in setOf("mp3", "m4a", "mp4", "aac", "flac", "ogg", "opus", "wav")) return
        val dir = File(c.cacheDir, "tagedit").apply { mkdirs() }
        val tmp = File(dir, "edit.$ext")
        c.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } } ?: error("ファイルを開けません")
        val af = AudioFileIO.read(tmp)
        val tag = af.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, t.title)
        if (t.artist.isNotBlank()) tag.setField(FieldKey.ARTIST, t.artist) else runCatching { tag.deleteField(FieldKey.ARTIST) }
        if (t.album.isNotBlank()) tag.setField(FieldKey.ALBUM, t.album) else runCatching { tag.deleteField(FieldKey.ALBUM) }
        if (t.trackNo > 0) runCatching { tag.setField(FieldKey.TRACK, t.trackNo.toString()) }
        // ジャケット(高画質があればそれ)
        val artPath = HiArt.cachedPath(t) ?: t.thumbPath
        artPath?.let { File(it) }?.takeIf { it.exists() }?.let { img ->
            runCatching {
                val art: Artwork = org.jaudiotagger.tag.images.AndroidArtwork().apply {
                    setBinaryData(img.readBytes())
                    setMimeType("image/jpeg")
                    setPictureType(3) // 表紙
                }
                tag.deleteArtworkField()
                tag.setField(art)
            }
        }
        af.commit()
        try {
            c.contentResolver.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                ?: error("書き込めません")
        } catch (e: SecurityException) {
            throw NeedPermission(uri)
        } finally {
            tmp.delete()
        }
    }
}

package com.example.ytmp3

import android.content.Context
import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 「お気に入り」: 試聴で出会った曲を、ダウンロードせずに取っておくリスト(新しい順) */
object Liked {
    val list = MutableStateFlow<List<Searcher.Result>>(emptyList())
    private var file: File? = null

    fun init(c: Context) {
        file = File(c.filesDir, "liked.json")
        list.value = runCatching {
            val a = JSONArray(file!!.readText())
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
                Searcher.Result(Searcher.Kind.SONG, o.getString("id"), o.optString("url"), o.optString("title"),
                    o.optString("artist"), o.optLong("dur"), o.optString("thumb").ifBlank { null })
            }
        }.getOrDefault(emptyList())
    }

    fun contains(vid: String?) = vid != null && list.value.any { it.videoId == vid }

    /** 切り替え。戻り値: 追加したら true */
    fun toggle(r: Searcher.Result): Boolean {
        val vid = r.videoId ?: return false
        val now = list.value
        val added = now.none { it.videoId == vid }
        list.value = if (added) listOf(r.copy(kind = Searcher.Kind.SONG)) + now else now.filterNot { it.videoId == vid }
        save()
        // ♡ した曲はライブラリにも入れる(持っていなければ ☁ オンラインで。ダウンロードはしない)
        if (added) runCatching { OnlineLibrary.add(r) }
        return added
    }

    /** 再生中の試聴曲から */
    /** ♡ の対象の動画 ID(試聴の曲・YouTube 由来のライブラリの曲) */
    fun vidOf(item: MediaItem?): String? = StreamDownload.videoId(item)
        ?: item?.mediaId?.toLongOrNull()?.let { Repository.track(it) }?.sourceUrl?.let { youtubeVideoId(it) }

    fun fromItem(item: MediaItem): Searcher.Result? {
        val vid = vidOf(item) ?: return null
        val m = item.mediaMetadata
        return Searcher.Result(Searcher.Kind.SONG, vid, "https://music.youtube.com/watch?v=$vid",
            m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), 0, m.artworkUri?.toString())
    }

    fun remove(vid: String) { list.value = list.value.filterNot { it.videoId == vid }; save() }

    private fun save() {
        val a = JSONArray()
        list.value.forEach { r ->
            a.put(JSONObject().put("id", r.videoId ?: r.id).put("url", r.url).put("title", r.title)
                .put("artist", r.artist).put("dur", r.durationSec).put("thumb", r.thumb ?: ""))
        }
        runCatching { file?.writeText(a.toString()) }
    }
}

package com.example.ytmp3

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「ラジオ」: ある曲に似た曲を YouTube Music から取って、続けて試聴する */
object Radio {
    /** ライブラリの曲から */
    fun forTrack(activity: AppCompatActivity, player: PlayerConnection, t: Track) =
        start(activity, player, youtubeVideoId(t.sourceUrl), t.title, t.artist, "「${t.title}」のラジオ", t.artist)

    /** 検索結果・試聴中の曲から */
    fun forVideo(activity: AppCompatActivity, player: PlayerConnection, vid: String, title: String, artist: String) =
        start(activity, player, vid, title, artist, "「$title」のラジオ", artist)

    /** 「A, B & C」「A feat. B」→「A」(共演者を外したメインのアーティスト) */
    fun mainArtist(artist: String): String =
        artist.split(", ", "、", " & ", " ＆ ", " x ", " × ", " feat.", " feat ", " ft.", " with ")
            .first().trim().ifBlank { artist.trim() }

    /** アーティストから: よく聴く曲(なければ YouTube Music の人気曲)を種にする */
    /** アーティストのラジオで最初に流れる曲(よく聴く曲のうち YouTube 由来のもの) */
    fun seedTrack(name: String): Track? {
        val counts = PlayLog.counts(90)
        return Repository.tracks.value.filter { it.artistOrUnknown == name || mainArtist(it.artist) == name }
            .sortedByDescending { counts[it.id] ?: 0 }
            .firstOrNull { youtubeVideoId(it.sourceUrl) != null }
    }

    fun forArtist(activity: AppCompatActivity, player: PlayerConnection, name: String) {
        val seed = seedTrack(name)?.let { youtubeVideoId(it.sourceUrl) }
        start(activity, player, seed, "", name, "$name のラジオ", name)
    }

    private fun start(
        activity: AppCompatActivity, player: PlayerConnection,
        seedVid: String?, findTitle: String, findArtist: String, label: String, artist: String,
    ) {
        Toast.makeText(activity, "$label を準備中…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val vid = seedVid ?: InnerTube.findSongId(findTitle, findArtist) ?: error("曲が見つかりません")
                    vid to InnerTube.radio(vid)
                }
            }
            val (vid, list) = r.getOrNull() ?: run {
                Toast.makeText(activity, "ラジオを作れませんでした", Toast.LENGTH_SHORT).show(); return@launch
            }
            if (list.isEmpty()) { Toast.makeText(activity, "ラジオを作れませんでした", Toast.LENGTH_SHORT).show(); return@launch }
            Searcher.prefetch(list, 2)
            val ref = PlayLog.streamRef(Searcher.radioList(vid, label, artist, "https://i.ytimg.com/vi/$vid/hqdefault.jpg"))
            player.playSearch(list, 0, ref = ref)
        }
    }
}

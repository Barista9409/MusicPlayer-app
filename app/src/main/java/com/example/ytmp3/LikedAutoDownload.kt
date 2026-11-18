package com.example.ytmp3

import android.content.Context
import android.net.ConnectivityManager

/** ♡(気になる)した試聴曲を、Wi-Fi など通信量を気にしなくていい時に自動でダウンロード(既定: オフ。♡ した曲は ☁ オンラインでライブラリに入る) */
object LikedAutoDownload {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun isOn(c: Context) = prefs(c).getBoolean("liked_autodl", false)
    fun setOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean("liked_autodl", on).apply()

    /** まだ持っていない ♡ の曲をダウンロードに入れる(アプリを開いている時に呼ぶ) */
    suspend fun run(c: Context) {
        if (!isOn(c) || !DownloadManager.ready()) return
        val unmetered = c.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false
        if (!unmetered) return
        Liked.list.value.forEach { r ->
            val vid = r.videoId ?: return@forEach
            when (StreamDownload.state(vid)) {
                StreamDownload.State.NONE ->
                    runCatching { DownloadManager.add(c, "https://www.youtube.com/watch?v=$vid", wholeList = false, makePlaylist = false) }
                // ☁ オンラインでライブラリにある曲は、その曲のままダウンロード(プレイリスト等はそのまま)
                StreamDownload.State.ONLINE -> VideoAlias.trackFor(vid, includeMissing = true)?.let {
                    runCatching { DownloadManager.redownload(c, listOf(it)) }
                }
                else -> {}
            }
        }
    }
}

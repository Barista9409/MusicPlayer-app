package com.example.ytmp3

import android.content.Context

/** 動画の保存・表示の設定(設定 → 📁 ライブラリ → 動画) */
object VideoPrefs {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 保存する画質の上限(縦の画素数) */
    fun quality(c: Context) = p(c).getInt("video_quality", 720)
    fun setQuality(c: Context, v: Int) = p(c).edit().putInt("video_quality", v).apply()

    /** Wi-Fi の時だけ保存(モバイル回線の時は Wi-Fi を待つ) */
    fun wifiOnly(c: Context) = p(c).getBoolean("video_wifi_only", true)
    fun setWifiOnly(c: Context, on: Boolean) = p(c).edit().putBoolean("video_wifi_only", on).apply()

    /** 動画を保存したら音声ファイルを消す(動画の音で聴く。容量の節約) */
    fun dropAudio(c: Context) = p(c).getBoolean("video_drop_audio", false)
    fun setDropAudio(c: Context, on: Boolean) = p(c).edit().putBoolean("video_drop_audio", on).apply()

    /** 🎬 の時、ライブラリの曲でも保存していなければネットの動画を映す(オフ = 保存した動画だけ) */
    fun netForLibrary(c: Context) = p(c).getBoolean("video_net_library", false)
    fun setNetForLibrary(c: Context, on: Boolean) = p(c).edit().putBoolean("video_net_library", on).apply()

    /** 保存した動画のファイルが今もあるか(消されていたら使わない) */
    fun localVideo(c: Context, t: Track?): String? {
        val u = t?.videoUri?.takeIf { it.isNotEmpty() } ?: return null
        val ok = runCatching {
            c.contentResolver.openFileDescriptor(android.net.Uri.parse(u), "r")?.use { true } ?: false
        }.getOrDefault(false)
        return if (ok) u else null
    }
}

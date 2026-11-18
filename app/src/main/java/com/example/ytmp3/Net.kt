package com.example.ytmp3

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 通信の状態と、ネットから流してよいか。
 * - 電波が無い / オフラインモード / (設定で)モバイル回線 → ☁ の曲は一時保存済みのものだけ再生
 */
object Net {
    /** 変わったら増える(一覧の描き直し用) */
    val changed = MutableStateFlow(0L)
    @Volatile var connected = true; private set
    @Volatile var metered = false; private set
    private lateinit var app: Context

    fun init(c: Context) {
        app = c.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        fun update() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            metered = cm.isActiveNetworkMetered
            changed.value = System.currentTimeMillis()
        }
        update()
        var current: Network? = cm.activeNetwork
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Wi-Fi ⇔ モバイルなど回線が替わったら、前の回線で取った再生 URL は使えない
                    if (current != null && current != network) Searcher.forgetUrls()
                    current = network
                    update()
                }
                override fun onLost(network: Network) = update()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
            })
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** オフラインモード: ネットを使わない(ダウンロード済み・一時保存済みの曲だけ) */
    fun offlineMode(c: Context) = prefs(c).getBoolean("offline_mode", false)
    fun setOfflineMode(c: Context, on: Boolean) { prefs(c).edit().putBoolean("offline_mode", on).apply(); changed.value = System.currentTimeMillis() }

    /** モバイル回線ではネットから再生しない(通信量の節約) */
    fun noMobileStream(c: Context) = prefs(c).getBoolean("no_mobile_stream", false)
    fun setNoMobileStream(c: Context, on: Boolean) { prefs(c).edit().putBoolean("no_mobile_stream", on).apply(); changed.value = System.currentTimeMillis() }

    /** 今ネットから流してよいか */
    fun streamAllowed(c: Context = app): Boolean =
        !offlineMode(c) && connected && !(metered && noMobileStream(c))

    /** なぜ流せないか(表示用) */
    fun reason(c: Context = app): String = when {
        offlineMode(c) -> "オフラインモード中"
        !connected -> "電波がありません"
        metered && noMobileStream(c) -> "モバイル回線ではネットから再生しない設定"
        else -> ""
    }

    /** この曲を今再生できるか(ダウンロード済み・一時保存済み・ネット可) */
    fun canPlayNow(t: Track): Boolean = when {
        !Repository.isMissing(t.id) -> true
        !Repository.isOnline(t.id) -> false
        streamAllowed() -> true
        else -> StreamCache.isCached(app, youtubeVideoId(t.sourceUrl))
    }

    fun canPlayNow(r: Searcher.Result): Boolean {
        val vid = r.videoId ?: return false
        VideoAlias.trackFor(vid)?.let { if (!Repository.isMissing(it.id)) return true }
        return streamAllowed() || StreamCache.isCached(app, vid)
    }
}

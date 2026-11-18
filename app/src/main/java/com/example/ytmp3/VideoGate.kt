package com.example.ytmp3

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * 映像を読み込むかどうか(再生画面の ▶ と動画プレビューの両方から使う)。
 * どれか 1 つでも映像を映している画面があれば映像オン、どれも無ければ音声だけ(通信量・電池の節約)。
 * 画面の切り替わりで「開く側の onStart」が「閉じる側の onStop」より先に来ても消えないように数で管理する。
 */
object VideoGate {
    private val owners = mutableSetOf<String>()

    fun acquire(key: String, p: Player?) { owners += key; apply(p) }
    fun release(key: String, p: Player?) { owners -= key; apply(p) }

    private fun apply(p: Player?) {
        p ?: return
        runCatching {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, owners.isEmpty()).build()
        }
    }
}

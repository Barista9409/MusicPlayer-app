package com.example.ytmp3

import androidx.media3.common.Player

/** 再生ボタンを押した後、音が出るのを待っている(読み込み中) */
fun Player.isLoadingToPlay() = playWhenReady && playbackState == Player.STATE_BUFFERING

/** 再生中、または再生しようとしている(ボタンは「一時停止」を見せる) */
fun Player.wantsToPlay() = isPlaying || isLoadingToPlay()

/** 再生/一時停止ボタンを押した時 */
fun Player.togglePlay() {
    if (wantsToPlay()) pause()
    else {
        if (playbackState == Player.STATE_IDLE) prepare()
        if (playbackState == Player.STATE_ENDED) seekTo(0)
        play()
    }
}

/** くるくるを出す・消す(短い読み込みでちらつかないよう、少し遅れて出る) */
fun com.google.android.material.progressindicator.CircularProgressIndicator.showLoading(on: Boolean) {
    if (on) show() else hide()
}

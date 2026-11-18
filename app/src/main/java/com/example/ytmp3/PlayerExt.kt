package com.example.ytmp3

import androidx.media3.common.Player

/**
 * 曲を選んで再生。前の曲の読み込みに失敗して止まっている(待機中)時も、準備し直してから流す。
 * (準備し直さないと、選んでも止まったままになる)
 */
fun Player.playAt(index: Int, positionMs: Long = 0L) {
    if (index !in 0 until mediaItemCount) return
    seekTo(index, positionMs)
    if (playbackState == Player.STATE_IDLE) prepare()
    play()
}

/** ⏭ / ⏮ も同じく、止まっていたら準備し直す */
fun Player.skipNext() {
    seekToNextMediaItem()
    if (playbackState == Player.STATE_IDLE) prepare()
}

fun Player.skipPrevious() {
    seekToPrevious()
    if (playbackState == Player.STATE_IDLE) prepare()
}

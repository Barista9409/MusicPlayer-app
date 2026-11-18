package com.example.ytmp3

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * おやすみタイマー。PlaybackService のプレーヤーを直接止める(同一プロセス)。
 * 時間指定は最後の数秒をフェードアウトしてから一時停止する。
 */
object SleepTimer {
    sealed class State {
        object Off : State()
        data class At(val endAt: Long, val totalMs: Long) : State()
        object EndOfTrack : State()
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 最後をフェードアウトするか */
    var fadeOut = true
    private const val FADE_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var fading = false

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // 「曲の終わりまで」で曲の最後に一時停止したら終了
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                _state.value == State.EndOfTrack
            ) cancel()
        }
    }

    /** PlaybackService から呼ぶ */
    fun attach(p: Player) {
        player?.removeListener(listener)
        player = p
        p.addListener(listener)
        applyEndOfTrack()
    }

    fun detach(p: Player) {
        if (player === p) {
            p.removeListener(listener)
            player = null
        }
    }

    fun start(minutes: Int) {
        clearCallbacks()
        val total = minutes * 60_000L
        _state.value = State.At(System.currentTimeMillis() + total, total)
        applyEndOfTrack()
        val fadeStart = (total - FADE_MS).coerceAtLeast(0)
        handler.postDelayed(fadeRunnable, if (fadeOut) fadeStart else total)
    }

    fun startEndOfTrack() {
        clearCallbacks()
        _state.value = State.EndOfTrack
        applyEndOfTrack()
    }

    fun cancel() {
        clearCallbacks()
        _state.value = State.Off
        applyEndOfTrack()
        restoreVolume()
    }

    fun remainingMs(): Long = when (val s = _state.value) {
        is State.At -> (s.endAt - System.currentTimeMillis()).coerceAtLeast(0)
        else -> 0
    }

    private fun applyEndOfTrack() {
        (player as? androidx.media3.exoplayer.ExoPlayer)?.setPauseAtEndOfMediaItems(_state.value == State.EndOfTrack)
    }

    private fun clearCallbacks() {
        handler.removeCallbacks(fadeRunnable)
        handler.removeCallbacks(fadeStep)
        fading = false
    }

    private fun restoreVolume() {
        player?.let { if (it.volume < 1f) it.volume = 1f }
    }

    private val fadeRunnable = Runnable {
        if (fadeOut && player?.isPlaying == true) {
            fading = true
            handler.post(fadeStep)
        } else stopNow()
    }

    private val fadeStep = object : Runnable {
        override fun run() {
            val p = player ?: return stopNow()
            val left = remainingMs()
            if (left <= 0 || !p.isPlaying) return stopNow()
            p.volume = (left.toFloat() / FADE_MS).coerceIn(0f, 1f)
            handler.postDelayed(this, 250)
        }
    }

    private fun stopNow() {
        clearCallbacks()
        player?.pause()
        _state.value = State.Off
        // 次に再生した時は通常の音量で
        handler.postDelayed({ restoreVolume() }, 500)
    }
}

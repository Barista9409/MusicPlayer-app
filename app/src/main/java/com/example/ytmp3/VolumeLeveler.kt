package com.example.ytmp3

import android.content.Context
import android.media.audiofx.DynamicsProcessing

/**
 * 音量をそろえる: 曲ごとの音量差をならす(大きい音は抑え、小さい音は持ち上げる)。
 * Android の DynamicsProcessing(コンプレッサー + リミッター)を再生の音声セッションに掛ける。
 */
object VolumeLeveler {
    private const val PREFS = "settings"
    private const val KEY = "normalize"
    private var dp: DynamicsProcessing? = null
    private var session = 0

    fun isOn(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        apply(context)
    }

    /** 再生サービスから: 音声セッションが決まった/変わった時 */
    fun attach(context: Context, sessionId: Int) {
        // 同じ音声セッションなら掛け直さない(掛け直しの瞬間に音が乱れることがあるため)
        if (sessionId == session && dp != null) return
        release()
        session = sessionId
        apply(context)
    }

    fun apply(context: Context) {
        val on = isOn(context)
        if (session == 0) return
        if (dp == null && on) dp = create(session)
        runCatching { dp?.enabled = on }
    }

    fun release() {
        runCatching { dp?.release() }
        dp = null
    }

    private fun create(sessionId: Int): DynamicsProcessing? = runCatching {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            false, 0, // 前段 EQ なし
            true, 1, // コンプレッサー 1 帯域(全体)
            false, 0, // 後段 EQ なし
            true, // リミッター
        ).build()
        // -26dB を超えた分を 1/3 に圧縮して、+7dB 持ち上げる(小さい曲は大きく、大きい曲は控えめに)
        val band = DynamicsProcessing.MbcBand(
            true, 20000f, 20f, 300f, 3f, -26f, 8f, -90f, 1f, 0f, 7f
        )
        cfg.setMbcBandAllChannelsTo(0, band)
        // 音割れ防止
        cfg.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        DynamicsProcessing(0, sessionId, cfg)
    }.onFailure { android.util.Log.w("VolumeLeveler", "DynamicsProcessing failed", it) }.getOrNull()
}

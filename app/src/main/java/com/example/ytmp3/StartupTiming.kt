package com.example.ytmp3

/**
 * 起動の速さを測って、遅かった時だけ「問題の報告」の記録に残す(どこが重いかを調べるため)。
 *  - アプリの準備(App.onCreate)のうち時間のかかった上位 3 つ
 *  - ホームが最初に描けるまで、操作できるようになるまで
 */
object StartupTiming {
    private val steps = mutableListOf<Pair<String, Long>>()
    private var appMs = 0L
    private var reported = false

    /** アプリの準備の 1 つを測る */
    inline fun <T> step(name: String, block: () -> T): T {
        val t = android.os.SystemClock.uptimeMillis()
        try { return block() } finally { record(name, android.os.SystemClock.uptimeMillis() - t) }
    }

    fun record(name: String, ms: Long) { synchronized(steps) { steps += name to ms } }
    fun appDone(ms: Long) { appMs = ms }

    /** 画面(MainActivity)を開いた時刻 */
    @Volatile private var activityAt = 0L
    fun activityCreated() { if (activityAt == 0L) activityAt = android.os.SystemClock.uptimeMillis() }

    /** ホームが最初に描けて、画面が操作を受け付けるようになった時(firstRenderMs = 最初の描画にかかった時間) */
    fun homeReady(firstRenderMs: Long) {
        if (reported) return
        reported = true
        // アプリが裏で先に動いていた時(再生・ウィジェット・更新の後など)は、画面を開いた時から数える
        // (そうしないと、裏で動いていた時間まで「起動が遅い」に入ってしまう)
        val proc = android.os.Process.getStartUptimeMillis()
        val opened = activityAt.takeIf { it > 0 && it - proc > 2000 }
        val total = android.os.SystemClock.uptimeMillis() - (opened ?: proc)
        // 速い時は残さない(記録が埋まらないように)
        if (total < 1500 && firstRenderMs < 300) return
        val top = synchronized(steps) { steps.sortedByDescending { it.second }.take(3) }
            .joinToString("、") { "${it.first} ${it.second}ms" }
        ErrorLog.add("起動", "操作できるまで ${total}ms${if (opened != null) "(画面を開いてから。アプリは裏で先に動いていた)" else ""} · アプリの準備 ${appMs}ms(重い順: $top) · ホームの最初の描画 ${firstRenderMs}ms")
    }

    /** ホームの描き直しが遅かった時 */
    fun slowRender(ms: Long) {
        if (ms >= 400) ErrorLog.add("起動", "ホームの描き直しに ${ms}ms(この間は画面が操作できない)")
    }
}

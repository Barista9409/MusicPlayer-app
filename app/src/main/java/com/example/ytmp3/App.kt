package com.example.ytmp3

import android.app.Application

class App : Application() {
    override fun onCreate() {
        val t0 = android.os.SystemClock.uptimeMillis()
        super.onCreate()
        StartupTiming.step("ErrorLog.init") { ErrorLog.init(this) }
        StartupTiming.step("VideoCheck.init") { VideoCheck.init(this) }
        StartupTiming.step("NoGood.init") { NoGood.init(this) }
        StartupTiming.step("Skin.install") { Skin.install(this) }
        StartupTiming.step("Repository.init") { Repository.init(this) }
        StartupTiming.step("AutoContinue.init") { AutoContinue.init(this) }
        StartupTiming.step("ListenTime.init") { ListenTime.init(this) }
        StartupTiming.step("PlayLog.init") { PlayLog.init(this) }
        StartupTiming.step("VideoAlias.init") { VideoAlias.init(this) }
        StartupTiming.step("AltSources.init") { AltSources.init(this) }
        StartupTiming.step("SkipLog.init") { SkipLog.init(this) }
        StartupTiming.step("DailyMix.init") { DailyMix.init(this) }
        StartupTiming.step("MoodMix.init") { MoodMix.init(this) }
        StartupTiming.step("MixPrefs.init") { MixPrefs.init(this) }
        StartupTiming.step("ArtistGenres.init") { ArtistGenres.init(this) }
        StartupTiming.step("MixHistory.init") { MixHistory.init(this) }
        StartupTiming.step("NoGoodSimilar.init") { NoGoodSimilar.init(this) }
        StartupTiming.step("AppIcon.sync") { runCatching { AppIcon.sync(this) } }
        StartupTiming.step("Net.init") { Net.init(this) }
        StartupTiming.step("Liked.init") { Liked.init(this) }
        StartupTiming.step("InnerTube.init") { InnerTube.init(this) }
        PoToken.init(this)
        FetchStats.init(this)
        // 名乗り方の読み込み(yt-dlp の中身を読むので裏で)と、ネットの曲をすぐ鳴らすための下準備
        // (再生 URL の取り方・YouTube に名乗る情報。最初の 1 曲目が待たされないように)
        Thread {
            runCatching { InnerTube.clients() }
            runCatching { InnerTube.streamClients() }
            if (Net.streamAllowed(this)) runCatching { InnerTube.visitorData() }
        }.start()
        StartupTiming.step("LibraryScanner") { LibraryScanner.init(this) }
        StartupTiming.step("Alarms") { Alarms.init(this); Alarms.scheduleAll() }
        StartupTiming.step("DownloadManager") { DownloadManager.init(this) }
        // すぐ要らない準備は、起動して画面が出たあとに(最初の画面を待たせないように)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { AutoBackup.schedule(this) }
            runCatching { UpdateNotifier.schedule(this) }
            // ウィジェットを今の版の絵で描き直す(更新直後に絵がずれないように)
            runCatching { MusicWidget.refreshAll(this) }
            // 確認の合言葉(PO トークン)を裏で先に作っておく(最初の曲で待たないように)
            if (Net.streamAllowed(this)) runCatching { NewPipeSource.warmUp() }
        }, 3000)
        StartupTiming.appDone(android.os.SystemClock.uptimeMillis() - t0)
    }
}

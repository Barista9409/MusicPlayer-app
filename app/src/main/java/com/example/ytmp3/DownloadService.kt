package com.example.ytmp3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** ダウンロード中にアプリを閉じても処理を続けるための前面サービス */
class DownloadService : Service() {
    private val scope = MainScope()
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private var doneAtStart = emptySet<Long>()
    private var failedAtStart = emptySet<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ダウンロード", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIF_ID, build("ダウンロード準備中…", "", -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val jobs = DownloadManager.jobs.value
        doneAtStart = jobs.filter { it.status == JobStatus.DONE }.map { it.id }.toSet()
        failedAtStart = jobs.filter { it.status == JobStatus.FAILED }.map { it.id }.toSet()

        scope.launch {
            combine(DownloadManager.jobs, DownloadManager.busy) { j, b -> j to b }.collect { (jobs, busy) ->
                if (!busy) {
                    finish(jobs)
                } else {
                    val active = jobs.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
                    val running = jobs.firstOrNull { it.status == JobStatus.RUNNING }
                    val doneNow = jobs.count { it.status == JobStatus.DONE && it.id !in doneAtStart }
                    val total = doneNow + active.size
                    val title = if (total > 1) "ダウンロード中 (${doneNow + 1}/$total)" else "ダウンロード中"
                    nm.notify(NOTIF_ID, build(title, running?.title.orEmpty(), running?.progress ?: -1))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    private fun finish(jobs: List<DownloadJob>) {
        val done = jobs.count { it.status == JobStatus.DONE && it.id !in doneAtStart }
        val failed = jobs.count { it.status == JobStatus.FAILED && it.id !in failedAtStart }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // 完了の通知は出さない(アプリの中のダウンロード一覧で確認できる)
        stopSelf()
    }

    private fun build(title: String, text: String, progress: Int) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1001
        private const val DONE_ID = 1002
    }
}

package com.example.ytmp3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * アプリの新しい版のお知らせ・自動アップデート。
 * モード: "notify"(通知だけ・既定) / "auto"(Wi-Fi の時に自動で更新) / "off"
 */
object UpdateNotifier {
    private const val CHANNEL = "app_update"
    private const val NOTIF_ID = 90
    private const val WORK = "app_update_check"
    const val EXTRA_SHOW_UPDATE = "show_update"
    /** 「更新しました」の通知から: この版の変更点を開く */
    const val EXTRA_SHOW_CHANGES = "show_changes"

    fun mode(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("update_mode", "notify") ?: "notify"
    fun setMode(c: Context, m: String) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("update_mode", m).apply()
        schedule(c)
    }
    fun modeLabel(c: Context) = when (mode(c)) { "auto" -> "自動で更新(Wi-Fi の時)"; "off" -> "オフ"; else -> "通知する" }

    /** 6 時間ごとの確認を予約(オフなら取り消し) */
    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (mode(c) == "off") { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<Worker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun notify(c: Context, title: String, text: String, tap: PendingIntent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "アプリのアップデート", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
        Inbox.add(c, title, text, NOTIF_ID)
    }

    /** 「更新しました」の通知を消す(アプリで変更点を見た時) */
    fun clearUpdated(c: Context) {
        runCatching { c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    private fun openApp(c: Context) = PendingIntent.getActivity(c, 91,
        Intent(c, MainActivity::class.java).putExtra(EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val c = applicationContext
            val m = mode(c)
            if (m == "off") return Result.success()
            val rel = runCatching { AppUpdater.latest(c) }.getOrNull() ?: return Result.success()
            if (rel.code <= AppUpdater.currentCode(c)) return Result.success()
            val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val unmetered = c.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false
            if (m == "auto" && unmetered) {
                // 裏でダウンロードして更新(確認が要る時は通知が出る)
                runCatching { AppUpdater.downloadAndInstall(c, rel) }
                    .onFailure { notify(c, "😅 うまく更新できませんでした", "失礼しました。タップしていただければ、もう一度お試しします", openApp(c)) }
                return Result.success()
            }
            // 同じ版のお知らせは 1 回だけ
            if (p.getLong("update_notified", 0L) == rel.code) return Result.success()
            p.edit().putLong("update_notified", rel.code).apply()
            val notes = rel.notes.lines().filter { it.isNotBlank() }.take(3).joinToString("\n")
            notify(c, "🎁 新しい版をご用意しました(${rel.name.removePrefix("MusicPlayer ")})",
                (notes.lines().firstOrNull { it.isNotBlank() }?.let { "$it\n" } ?: "") + "よろしければ、タップでお届けします", openApp(c))
            return Result.success()
        }
    }

    /** 更新が終わった時(アプリ自身が新しくなった時)のお知らせ */
    class UpdatedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            // タップで変更点を開く
            val tap = PendingIntent.getActivity(context, 92, Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SHOW_CHANGES, AppUpdater.currentName(context))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notify(context, "✨ v${AppUpdater.currentName(context)} に更新しました", "新しくなったところ、ご案内しますね。タップでどうぞ", tap)
        }
    }
}

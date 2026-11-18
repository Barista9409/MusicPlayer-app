package com.example.ytmp3

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 毎日の自動バックアップ: 選んでもらったファイル(Google ドライブなど)に毎日 1 回上書き保存する。
 * 中身は「バックアップを保存」と同じ zip。
 */
object AutoBackup {
    private const val PREFS = "settings"
    private const val KEY_URI = "auto_backup_uri"
    private const val KEY_LAST = "auto_backup_last"
    private const val KEY_ERROR = "auto_backup_error"
    private const val WORK = "auto_backup"

    fun fileName() = "MusicPlayer-自動バックアップ.${Backup.EXT}"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 保存先(許可が残っている時だけ) */
    fun target(c: Context): Uri? = prefs(c).getString(KEY_URI, null)?.let(Uri::parse)?.takeIf { u ->
        c.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
    }

    fun lastTime(c: Context) = prefs(c).getLong(KEY_LAST, 0L)
    fun lastError(c: Context): String? = prefs(c).getString(KEY_ERROR, null)

    /** 保存先を決めて開始(作ったファイルの許可を持ち続ける) */
    fun enable(c: Context, uri: Uri): Boolean {
        val ok = runCatching {
            c.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.isSuccess
        if (!ok) return false
        prefs(c).edit().putString(KEY_URI, uri.toString()).remove(KEY_ERROR).apply()
        schedule(c)
        runNow(c)
        return true
    }

    fun disable(c: Context) {
        prefs(c).getString(KEY_URI, null)?.let { s ->
            runCatching {
                c.contentResolver.releasePersistableUriPermission(Uri.parse(s),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        prefs(c).edit().remove(KEY_URI).remove(KEY_ERROR).apply()
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
    }

    /** アプリ起動時: オンなら毎日の予定を入れておく */
    fun schedule(c: Context) {
        if (prefs(c).getString(KEY_URI, null) == null) return
        val req = PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun runNow(c: Context) {
        WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<Worker>().build())
    }

    /** 実際の保存(結果は設定画面に表示) */
    fun run(c: Context): Boolean {
        val uri = target(c) ?: run {
            if (prefs(c).getString(KEY_URI, null) != null)
                prefs(c).edit().putString(KEY_ERROR, "保存先に書き込めません。保存先を選び直してください").apply()
            return false
        }
        return runCatching { Backup.export(c, uri) }
            .onSuccess { prefs(c).edit().putLong(KEY_LAST, System.currentTimeMillis()).remove(KEY_ERROR).apply() }
            .onFailure { prefs(c).edit().putString(KEY_ERROR, "保存できませんでした: ${it.message}").apply() }
            .isSuccess
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = when {
            run(applicationContext) -> Result.success()
            target(applicationContext) == null -> Result.failure() // 保存先が無い: 繰り返さない
            else -> Result.retry()
        }
    }
}

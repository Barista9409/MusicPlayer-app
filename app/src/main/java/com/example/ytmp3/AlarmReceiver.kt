package com.example.ytmp3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** AlarmManager から呼ばれ、再生サービスでアラームを鳴らす */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Alarms.ACTION_FIRE) return
        val id = intent.getLongExtra(Alarms.EXTRA_ID, 0)
        ContextCompat.startForegroundService(
            context,
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_ALARM_START)
                .putExtra(Alarms.EXTRA_ID, id)
        )
    }
}

/** 再起動・時刻変更・アプリ更新後にアラームを登録し直す */
class AlarmRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarms.scheduleAll()
    }
}

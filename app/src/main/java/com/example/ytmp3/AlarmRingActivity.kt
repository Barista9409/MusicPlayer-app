package com.example.ytmp3

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** アラームが鳴っている時の全画面(ロック画面の上にも表示) */
class AlarmRingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_alarm_ring)

        val id = PlaybackService.ringing.value
        val alarm = id?.let { Alarms.get(it) }
        findViewById<TextView>(R.id.label).text =
            if (PlaybackService.isTesting) "テスト再生中" else "おはようございます"
        val (name, sub) = alarm?.targetText() ?: ("" to "")
        findViewById<TextView>(R.id.track).text = name
        findViewById<TextView>(R.id.trackSub).text = sub
        Thumbs.bind(findViewById<ImageView>(R.id.art), alarm?.targetArt())

        findViewById<View>(R.id.snooze).setOnClickListener { send(PlaybackService.ACTION_ALARM_SNOOZE) }
        findViewById<View>(R.id.stop).setOnClickListener { send(PlaybackService.ACTION_ALARM_STOP) }

        // 通知などから止められたら閉じる
        lifecycleScope.launch {
            PlaybackService.ringing.collect { if (it == null) finish() }
        }
    }

    private fun send(action: String) {
        startService(Intent(this, PlaybackService::class.java).setAction(action))
        finish()
    }
}

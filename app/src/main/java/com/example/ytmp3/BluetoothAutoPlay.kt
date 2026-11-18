package com.example.ytmp3

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Bluetooth の音声機器(カーナビ・イヤホンなど)につながったら、前回の続きから自動で再生する。
 * 設定でオフにできる(既定: オン)。
 */
class BluetoothAutoPlay : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!isOn(context) || !hasPermission(context)) return
        @Suppress("DEPRECATION")
        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        // 音声機器だけ(スマートウォッチ等では鳴らさない)
        val major = runCatching { dev?.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (major != null && major != BluetoothClass.Device.Major.AUDIO_VIDEO) return
        // 音声の経路がつながるのを少し待ってから
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                ContextCompat.startForegroundService(context,
                    Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_AUTO_PLAY))
            }
            pending.finish()
        }, 3000)
    }

    companion object {
        private const val KEY = "bt_autoplay"

        fun isOn(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY, true)
        fun setOn(c: Context, on: Boolean) =
            c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

        /** Android 12 以降は「付近のデバイス」の許可が必要 */
        fun hasPermission(c: Context) = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}

package com.example.ytmp3

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** アラーム一覧(曜日ごとに時刻を設定し、曲やプレイリストで起きる) */
class AlarmsActivity : AppCompatActivity() {
    private val adapter = Adapter()
    private lateinit var list: RecyclerView

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarms)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        (list.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        findViewById<View>(R.id.fab).setOnClickListener { addAlarm() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Alarms.alarms.collect { adapter.submit(it) } }
                // 「あと○分」を更新
                launch { while (true) { delay(30_000); adapter.notifyItemRangeChanged(0, adapter.itemCount) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyItemChanged(0) // 権限の状態を反映
    }

    // ───────── 追加・編集 ─────────

    private fun addAlarm() {
        ensureNotificationPermission()
        AlarmEditSheet.show(this)
    }

    /** シートで保存された時 */
    fun onAlarmSaved(a: Alarm, isNew: Boolean) {
        val at = a.nextFire() ?: return
        Snackbar.make(list, "${whenText(at)} に鳴ります(${untilText(at)})", Snackbar.LENGTH_LONG).show()
        if (isNew) list.post { list.smoothScrollToPosition(adapter.itemCount - 1) }
    }

    /** 再生するもの: ライブラリ全体 / プレイリスト / 曲 */
    private fun pickTarget(a: Alarm) {
        val playlists = Repository.playlists.value
        val items = listOf("ライブラリ全体") + playlists.map { "プレイリスト:${it.name}" } + listOf("曲を選ぶ…")
        MaterialAlertDialogBuilder(this)
            .setTitle("アラームで再生する音楽")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> Alarms.upsert(a.copy(target = AlarmTarget.LIBRARY, targetId = 0))
                    which <= playlists.size -> Alarms.upsert(a.copy(target = AlarmTarget.PLAYLIST, targetId = playlists[which - 1].id))
                    else -> pickTrack(a)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun pickTrack(a: Alarm) {
        val all = Repository.tracks.value.filterNot { Repository.isMissing(it.id) }.sortedBy { it.title.lowercase() }
        if (all.isEmpty()) { toast("ライブラリに曲がありません"); return }
        // 検索付きの一覧
        val input = EditText(this).apply {
            hint = "曲名・アーティストで絞り込み"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@AlarmsActivity) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
            addView(input)
            addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * resources.displayMetrics.density).toInt()))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("曲を選ぶ")
            .setView(box)
            .setNegativeButton("キャンセル", null)
            .create()
        var shown = all
        val adapterWithClick = TrackAdapter(
            onClick = { pos ->
                shown.getOrNull(pos)?.let { Alarms.upsert(a.copy(target = AlarmTarget.TRACK, targetId = it.id)) }
                dialog.dismiss()
            },
            onMore = { _, pos ->
                shown.getOrNull(pos)?.let { Alarms.upsert(a.copy(target = AlarmTarget.TRACK, targetId = it.id)) }
                dialog.dismiss()
            },
        )
        rv.adapter = adapterWithClick
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val l = s?.toString().orEmpty().trim().lowercase()
                shown = if (l.isEmpty()) all else all.filter { it.title.lowercase().contains(l) || it.artist.lowercase().contains(l) }
                adapterWithClick.submit(shown)
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a2: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        adapterWithClick.submit(shown)
        dialog.show()
    }

    /** 今すぐ本番と同じように鳴らす(予定は変えない) */
    private fun testPlay(a: Alarm) {
        ensureNotificationPermission()
        ContextCompat.startForegroundService(
            this,
            Intent(this, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_ALARM_START)
                .putExtra(Alarms.EXTRA_ID, a.id)
                .putExtra(PlaybackService.EXTRA_TEST, true)
        )
    }

    private fun delete(a: Alarm) {
        Alarms.delete(a.id)
        Snackbar.make(list, "${a.timeText} のアラームを削除しました", Snackbar.LENGTH_LONG)
            .setAction("元に戻す") { Alarms.upsert(a) }
            .show()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** アラームが正しく動くための設定の問題(文言, 設定画面) */
    private fun problem(): Pair<String, Intent>? {
        if (!Alarms.canScheduleExact() && Build.VERSION.SDK_INT >= 31) {
            return "時刻ぴったりに鳴らすには「アラームとリマインダー」の許可が必要です" to
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return "アラームの停止・スヌーズを表示するには通知の許可が必要です" to
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            return "ロック画面にアラーム画面を出すには「全画面通知」の許可が必要です" to
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
        }
        if (!Settings.canDrawOverlays(this)) {
            return "使用中でもアラーム画面を表示するには「他のアプリの上に重ねて表示」の許可が必要です" to
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        }
        return null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ───────── list ─────────

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Alarm> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Alarm>) {
            // 時刻順
            items = list.sortedWith(compareBy({ it.hour }, { it.minute }))
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(if (viewType == 0) R.layout.item_alarm_header else R.layout.item_alarm, parent, false)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) bindHeader(h.itemView) else bindAlarm(h.itemView, items[position - 1])
        }

        private fun bindHeader(v: View) {
            val next = Alarms.next()
            v.findViewById<TextView>(R.id.nextWhen).text = next?.let { whenText(it.second) } ?: "なし"
            v.findViewById<TextView>(R.id.nextUntil).text = next?.let {
                untilText(it.second) + " · " + it.first.targetText().first
            } ?: "オンになっているアラームはありません"
            v.findViewById<View>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            val warn = v.findViewById<View>(R.id.warn)
            val p = problem()
            warn.visibility = if (p != null && items.isNotEmpty()) View.VISIBLE else View.GONE
            if (p != null) {
                v.findViewById<TextView>(R.id.warnText).text = p.first
                v.findViewById<View>(R.id.warnButton).setOnClickListener { runCatching { startActivity(p.second) } }
            }
        }

        private fun bindAlarm(v: View, a: Alarm) {
            val on = a.enabled
            val time = v.findViewById<TextView>(R.id.time)
            time.text = a.timeText
            time.alpha = if (on) 1f else 0.45f
            time.setOnClickListener { AlarmEditSheet.show(this@AlarmsActivity, a.id) }

            val sw = v.findViewById<MaterialSwitch>(R.id.enabled)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = on
            sw.setOnCheckedChangeListener { _, checked ->
                Alarms.upsert(a.copy(enabled = checked, snoozeUntil = 0))
                if (checked) Alarms.get(a.id)?.nextFire()?.let {
                    Snackbar.make(list, "${whenText(it)} に鳴ります(${untilText(it)})", Snackbar.LENGTH_SHORT).show()
                }
            }

            val next = a.nextFire()
            v.findViewById<TextView>(R.id.summary).text = buildList {
                add(a.daysText())
                if (next != null) add(if (a.snoozeUntil > System.currentTimeMillis()) "スヌーズ中 · ${whenText(next)}" else untilText(next))
                else if (!on) add("オフ")
            }.joinToString(" · ")

            // 曜日ボタン
            AlarmUi.bindDays(v.findViewById(R.id.days), a.days) { d ->
                val nd = if (d in a.days) a.days - d else a.days + d
                Alarms.upsert(a.copy(days = nd, enabled = true, snoozeUntil = 0))
            }

            // 再生するもの
            val (name, sub) = a.targetText()
            v.findViewById<TextView>(R.id.targetName).text = name
            v.findViewById<TextView>(R.id.targetSub).text = sub
            Thumbs.bind(v.findViewById(R.id.art), a.targetArt())
            v.findViewById<View>(R.id.target).setOnClickListener { pickTarget(a) }

            // 音量
            val vol = v.findViewById<Slider>(R.id.volume)
            val volText = v.findViewById<TextView>(R.id.volumeText)
            vol.clearOnSliderTouchListeners()
            vol.clearOnChangeListeners()
            vol.value = (a.volume.coerceIn(5, 100) / 5 * 5).toFloat()
            volText.text = "${a.volume}%"
            vol.addOnChangeListener { _, value, _ -> volText.text = "${value.toInt()}%" }
            vol.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    Alarms.get(a.id)?.let { Alarms.upsert(it.copy(volume = slider.value.toInt())) }
                }
            })

            // オプション
            v.findViewById<Chip>(R.id.fadeIn).apply {
                setOnCheckedChangeListener(null)
                isChecked = a.fadeIn
                setOnCheckedChangeListener { _, c -> Alarms.get(a.id)?.let { Alarms.upsert(it.copy(fadeIn = c)) } }
            }
            v.findViewById<View>(R.id.test).setOnClickListener { testPlay(a) }
            v.findViewById<View>(R.id.delete).setOnClickListener { delete(a) }
        }
    }
}

package com.example.ytmp3

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import java.util.Calendar

/**
 * アラームの作成・時刻変更シート。
 * ドラム式の時・分 + よく使う時刻 + 「○分後」 + 曜日 を 1 画面で。
 */
class AlarmEditSheet : BottomSheetDialogFragment() {

    private lateinit var hourPicker: NumberPicker
    private lateinit var minutePicker: NumberPicker
    private lateinit var caption: TextView
    private lateinit var daysBox: LinearLayout
    private var days: Set<Int> = setOf(2, 3, 4, 5, 6)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_alarm_edit, container, false)

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            // ドラムを回す時にシートが閉じないように
            isDraggable = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val id = arguments?.getLong(ARG_ID, 0L) ?: 0L
        val existing = if (id != 0L) Alarms.get(id) else null

        view.findViewById<TextView>(R.id.title).text = if (existing == null) "アラームを追加" else "アラームを編集"
        hourPicker = view.findViewById(R.id.hour)
        minutePicker = view.findViewById(R.id.minute)
        caption = view.findViewById(R.id.caption)
        daysBox = view.findViewById(R.id.days)

        setupPicker(hourPicker, 23)
        setupPicker(minutePicker, 59)
        hourPicker.value = existing?.hour ?: 7
        minutePicker.value = existing?.minute ?: 0
        days = existing?.days ?: setOf(2, 3, 4, 5, 6)
        hourPicker.setOnValueChangedListener { _, _, _ -> refresh() }
        minutePicker.setOnValueChangedListener { _, _, _ -> refresh() }

        // よく使う時刻
        val quick = view.findViewById<ChipGroup>(R.id.quick)
        listOf(5 to 30, 6 to 0, 6 to 30, 7 to 0, 7 to 30, 8 to 0, 9 to 0).forEach { (h, m) ->
            quick.addView(chip(String.format("%d:%02d", h, m)) { set(h, m) })
        }
        // 昼寝など: 今から ○分後
        val relative = view.findViewById<ChipGroup>(R.id.relative)
        listOf(15, 30, 60, 90).forEach { min ->
            relative.addView(chip(if (min < 60) "+${min}分" else if (min == 60) "+1時間" else "+1時間半") {
                val c = Calendar.getInstance().apply { add(Calendar.MINUTE, min) }
                set(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
                days = emptySet() // 一度きり
                refresh()
            })
        }

        // 曜日のプリセット
        view.findViewById<View>(R.id.presetEveryday).setOnClickListener { days = (1..7).toSet(); refresh() }
        view.findViewById<View>(R.id.presetWeekdays).setOnClickListener { days = setOf(2, 3, 4, 5, 6); refresh() }
        view.findViewById<View>(R.id.presetWeekend).setOnClickListener { days = setOf(1, 7); refresh() }
        view.findViewById<View>(R.id.presetOnce).setOnClickListener { days = emptySet(); refresh() }

        view.findViewById<View>(R.id.cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.save).setOnClickListener {
            val a = (existing ?: Alarm(id = Repository.newId(), hour = 7, minute = 0, days = days)).copy(
                hour = hourPicker.value, minute = minutePicker.value, days = days,
                enabled = true, snoozeUntil = 0,
            )
            Alarms.upsert(a)
            (activity as? AlarmsActivity)?.onAlarmSaved(a, existing == null)
            dismiss()
        }
        refresh()
    }

    private fun setupPicker(p: NumberPicker, max: Int) {
        p.minValue = 0
        p.maxValue = max
        p.displayedValues = Array(max + 1) { String.format("%02d", it) }
        p.wrapSelectorWheel = true
        p.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS // キーボードを出さない
        p.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 44f, resources.displayMetrics)
        p.selectionDividerHeight = 0
    }

    private fun set(h: Int, m: Int) {
        hourPicker.value = h
        minutePicker.value = m
        refresh()
    }

    private fun chip(text: String, onClick: () -> Unit) = Chip(requireContext()).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        val preview = Alarm(id = 0, hour = hourPicker.value, minute = minutePicker.value, days = days)
        val next = preview.nextFire()
        caption.text = if (next != null) "${whenText(next)} · ${untilText(next)}" else ""
        AlarmUi.bindDays(daysBox, days) { d ->
            days = if (d in days) days - d else days + d
            refresh()
        }
        view?.findViewById<TextView>(R.id.daysSummary)?.text = preview.daysText()
    }

    companion object {
        private const val ARG_ID = "id"
        fun show(activity: androidx.fragment.app.FragmentActivity, alarmId: Long? = null) {
            if (activity.supportFragmentManager.findFragmentByTag("alarm_edit") != null) return
            AlarmEditSheet().apply { arguments = bundleOf(ARG_ID to (alarmId ?: 0L)) }
                .show(activity.supportFragmentManager, "alarm_edit")
        }
    }
}

/** アラーム画面で共通の部品 */
object AlarmUi {
    /** 日〜土の丸いトグル */
    fun bindDays(container: LinearLayout, days: Set<Int>, onToggle: (Int) -> Unit) {
        container.removeAllViews()
        val ctx = container.context
        val primary = MaterialColors.getColor(container, androidx.appcompat.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnPrimary)
        val normal = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        val size = (40 * ctx.resources.displayMetrics.density).toInt()
        for (d in Alarm.DAY_ORDER) {
            val selected = d in days
            val tv = TextView(ctx).apply {
                text = Alarm.DAY_NAMES[d]
                gravity = Gravity.CENTER
                textSize = 15f
                setBackgroundResource(if (selected) R.drawable.bg_day_on else R.drawable.bg_day_off)
                setTextColor(
                    when {
                        selected -> onPrimary
                        d == 1 -> 0xFFE57373.toInt() // 日曜は赤
                        d == 7 -> 0xFF64B5F6.toInt() // 土曜は青
                        else -> normal
                    }
                )
                contentDescription = Alarm.DAY_NAMES[d] + "曜日" + if (selected) "(オン)" else "(オフ)"
                setOnClickListener { onToggle(d) }
            }
            if (selected) tv.backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
            val cell = FrameLayout(ctx)
            cell.addView(tv, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            container.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }
}

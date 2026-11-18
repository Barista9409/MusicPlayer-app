package com.example.ytmp3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** おやすみタイマーの設定シート */
class SleepTimerSheet : BottomSheetDialogFragment() {

    private data class Option(val label: String, val minutes: Int, val icon: Int) // minutes = -1 は曲の終わり

    private val options = listOf(
        Option("15分", 15, R.drawable.ic_timer),
        Option("30分", 30, R.drawable.ic_timer),
        Option("1時間", 60, R.drawable.ic_timer),
        Option("2時間", 120, R.drawable.ic_timer),
        Option("曲の終わりまで", -1, R.drawable.ic_music),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_sleep_timer, container, false)

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val status = view.findViewById<TextView>(R.id.status)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.progress)
        val box = view.findViewById<LinearLayout>(R.id.options)
        val fade = view.findViewById<MaterialSwitch>(R.id.fade)
        val off = view.findViewById<View>(R.id.off)

        fade.isChecked = SleepTimer.fadeOut
        fade.setOnCheckedChangeListener { _, on -> SleepTimer.fadeOut = on }
        off.setOnClickListener {
            SleepTimer.cancel()
            toast("おやすみタイマーをオフにしました")
            dismiss()
        }

        val rows = options.map { o ->
            val row = layoutInflater.inflate(R.layout.item_timer_option, box, false)
            row.findViewById<TextView>(R.id.text).text = o.label
            row.findViewById<ImageView>(R.id.icon).setImageResource(o.icon)
            row.setOnClickListener {
                if (o.minutes < 0) {
                    SleepTimer.startEndOfTrack()
                    toast("この曲が終わったら停止します")
                } else {
                    SleepTimer.start(o.minutes)
                    toast("${o.label}後に停止します")
                }
                dismiss()
            }
            box.addView(row)
            o to row
        }

        fun refresh() {
            val s = SleepTimer.state.value
            rows.forEach { (o, row) ->
                val selected = when (s) {
                    is SleepTimer.State.At -> o.minutes * 60_000L == s.totalMs
                    SleepTimer.State.EndOfTrack -> o.minutes < 0
                    else -> false
                }
                row.findViewById<View>(R.id.check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
            }
            when (s) {
                is SleepTimer.State.At -> {
                    val left = SleepTimer.remainingMs()
                    status.text = "あと ${formatTime(left)} で停止します"
                    progress.visibility = View.VISIBLE
                    progress.setProgressCompat((left * 1000 / s.totalMs.coerceAtLeast(1)).toInt(), false)
                    off.visibility = View.VISIBLE
                }
                SleepTimer.State.EndOfTrack -> {
                    status.text = "この曲が終わったら停止します"
                    progress.visibility = View.GONE
                    off.visibility = View.VISIBLE
                }
                else -> {
                    status.text = "オフ · 停止するまでの時間を選んでください"
                    progress.visibility = View.GONE
                    off.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refresh()
                delay(1000)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    companion object {
        fun show(activity: androidx.fragment.app.FragmentActivity) {
            if (activity.supportFragmentManager.findFragmentByTag("sleep") == null) {
                SleepTimerSheet().show(activity.supportFragmentManager, "sleep")
            }
        }
    }
}

package com.example.ytmp3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

/** ダウンロードの一覧(画面右上の ⬇ から、どのタブからでも) */
class DownloadsSheet : BottomSheetDialogFragment() {
    companion object {
        fun show(a: FragmentActivity) {
            // 設定タブの画面も "downloads" という名前なので、別の名前にする(同じだと開かなかった)
            val fm = a.supportFragmentManager
            if (fm.isStateSaved) return
            if (fm.findFragmentByTag(TAG) == null) DownloadsSheet().show(fm, TAG)
        }
        private const val TAG = "downloads_sheet"
    }

    private val adapter = Adapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_downloads, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val summary = view.findViewById<TextView>(R.id.summary)
        val empty = view.findViewById<View>(R.id.empty)
        val cancelAll = view.findViewById<View>(R.id.cancelAll)
        val clearDone = view.findViewById<View>(R.id.clearDone)
        val logView = view.findViewById<TextView>(R.id.log)
        val logScroll = view.findViewById<ScrollView>(R.id.logScroll)
        val logToggle = view.findViewById<TextView>(R.id.logToggle)
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@DownloadsSheet.adapter
            itemAnimator = null
        }
        cancelAll.setOnClickListener { DownloadManager.cancelAll() }
        clearDone.setOnClickListener { DownloadManager.clearFinished() }
        logToggle.setOnClickListener {
            val show = logScroll.visibility != View.VISIBLE
            logScroll.visibility = if (show) View.VISIBLE else View.GONE
            logToggle.text = if (show) "▼ 詳細ログを隠す" else "▶ 詳細ログを表示"
            if (show) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    DownloadManager.jobs.collect { jobs ->
                        adapter.items = jobs.reversed()
                        adapter.notifyDataSetChanged()
                        empty.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
                        val active = jobs.count { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
                        val done = jobs.count { it.status == JobStatus.DONE }
                        val failed = jobs.count { it.status == JobStatus.FAILED }
                        val rp = DownloadManager.restoreProgress(jobs)
                        summary.text = buildString {
                            append(if (rp != null) "復元のダウンロード ${rp.first} / ${rp.second}" else "ダウンロード")
                            if (active > 0) append(" · 残り $active")
                            if (done > 0) append(" · 完了 $done")
                            if (failed > 0) append(" · 失敗 $failed")
                        }
                        cancelAll.visibility = if (active > 0) View.VISIBLE else View.GONE
                        clearDone.visibility = if (jobs.size > active) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    DownloadManager.log.collect { lines ->
                        logView.text = lines.joinToString("\n")
                        if (logScroll.visibility == View.VISIBLE) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<DownloadJob> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val title: TextView = v.findViewById(R.id.title)
            val progress: LinearProgressIndicator = v.findViewById(R.id.progress)
            val message: TextView = v.findViewById(R.id.message)
            val action: ImageButton = v.findViewById(R.id.action)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_job, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val j = items[position]
            h.title.text = j.title
            // 行をタップ: このダウンロードの詳細(状態・元の URL・記録)
            h.itemView.setOnClickListener { showDetail(j.id) }
            h.progress.visibility = if (j.status == JobStatus.RUNNING) View.VISIBLE else View.GONE
            if (j.status == JobStatus.RUNNING) {
                h.progress.isIndeterminate = j.progress <= 0
                if (j.progress > 0) h.progress.setProgressCompat(j.progress, false)
            }
            h.message.text = when (j.status) {
                JobStatus.QUEUED -> "待機中"
                JobStatus.RUNNING -> j.message.ifBlank { "処理中…" }
                JobStatus.DONE -> j.message.ifBlank { "完了" }
                JobStatus.FAILED -> "失敗: ${j.message}"
                JobStatus.CANCELED -> "キャンセルしました"
            }
            h.icon.setImageResource(when (j.status) {
                JobStatus.DONE -> R.drawable.ic_check_circle
                JobStatus.FAILED -> R.drawable.ic_error
                JobStatus.CANCELED -> R.drawable.ic_close
                else -> R.drawable.ic_download
            })
            when (j.status) {
                JobStatus.QUEUED, JobStatus.RUNNING -> {
                    h.action.setImageResource(R.drawable.ic_close)
                    h.action.contentDescription = "中止"
                    h.action.setOnClickListener { DownloadManager.cancel(j.id) }
                }
                JobStatus.FAILED, JobStatus.CANCELED -> {
                    h.action.setImageResource(R.drawable.ic_refresh)
                    h.action.contentDescription = "やり直す"
                    h.action.setOnClickListener { DownloadManager.retry(requireContext(), j.id) }
                }
                JobStatus.DONE -> {
                    h.action.setImageResource(R.drawable.ic_play)
                    h.action.contentDescription = "再生"
                    h.action.setOnClickListener {
                        j.trackId?.let { Repository.track(it) }?.let { (activity as? MainActivity)?.player?.play(listOf(it), 0) }
                    }
                }
            }
        }
    }

    /** 1 件の詳細: 今の状態・元の URL・このダウンロードの記録(コピーできる) */
    private fun showDetail(id: Long) {
        val ctx = context ?: return
        val j = DownloadManager.jobs.value.firstOrNull { it.id == id } ?: return
        val d = resources.displayMetrics.density
        val state = when (j.status) {
            JobStatus.QUEUED -> "待機中"
            JobStatus.RUNNING -> "処理中" + (if (j.progress > 0) " ${j.progress}%" else "") + " · " + j.message
            JobStatus.DONE -> "完了 · " + j.message
            JobStatus.FAILED -> "失敗 · " + j.message
            JobStatus.CANCELED -> "キャンセルしました"
        }
        val lines = DownloadManager.logOf(id)
        val text = buildString {
            appendLine(j.title)
            appendLine("状態: $state")
            appendLine("元: ${j.url}")
            if (j.video) appendLine("種類: 🎬 動画")
            appendLine()
            if (lines.isEmpty()) appendLine("(まだ記録はありません)") else lines.forEach { appendLine(it) }
        }
        val tv = TextView(ctx).apply {
            this.text = text
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
        }
        val scroll = ScrollView(ctx).apply { addView(tv) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("ダウンロードの詳細")
            .setView(scroll)
            .setPositiveButton("コピー") { _, _ ->
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ダウンロードの詳細", text))
                android.widget.Toast.makeText(ctx, "コピーしました", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("閉じる", null)
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}

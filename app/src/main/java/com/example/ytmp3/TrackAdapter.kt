package com.example.ytmp3

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

class TrackAdapter(
    draggable: Boolean = false,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit = {},
    private val onMore: (View, Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {},
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    /** ドラッグの取っ手を出すか(プレイリストでは「並び替え」の時だけ) */
    var draggable: Boolean = draggable
        @SuppressLint("NotifyDataSetChanged")
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    val items = mutableListOf<Track>()
    var selected: Set<Long> = emptySet()
        set(value) { field = value; notifyDataSetChanged() }
    var missing: Set<Long> = emptySet()
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }
    var playingId: Long? = null
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Track>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        val t = items.removeAt(from)
        items.add(to, t)
        notifyItemMoved(from, to)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val art: ImageView = v.findViewById(R.id.art)
        val check: ImageView = v.findViewById(R.id.check)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val more: ImageButton = v.findViewById(R.id.more)
        val drag: ImageView = v.findViewById(R.id.drag)
        val defaultColor: ColorStateList = title.textColors
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

    override fun getItemCount() = items.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        h.title.text = t.title
        h.subtitle.text = buildList {
            // 保存の状態: 印なし = 端末にある / ☁✓ 一時保存済み / ☁ オンライン
            if (Repository.isOnline(t.id)) add(if (StreamCache.isCached(h.itemView.context, youtubeVideoId(t.sourceUrl))) "☁✓" else "☁")
            if (t.hasVideo) add("🎬")
            // ダウンロード中なら進み具合
            DownloadManager.progressFor(t)?.let { add(it) }
            if (t.artist.isNotBlank()) add(t.artist)
            if (t.album.isNotBlank()) add(t.album)
            if (t.durationMs > 0) add(formatTime(t.durationMs))
        }.joinToString(" · ")
        Thumbs.bind(h.art, t.thumbPath)
        h.check.visibility = if (t.id in selected) View.VISIBLE else View.GONE
        val gone = t.id in missing && !Net.canPlayNow(t)
        h.itemView.alpha = if (gone) 0.38f else 1f
        if (gone) h.subtitle.text = "ファイルが見つかりません · " + h.subtitle.text
        if (t.id == playingId) {
            h.title.setTextColor(MaterialColors.getColor(h.title, androidx.appcompat.R.attr.colorPrimary))
        } else {
            h.title.setTextColor(h.defaultColor)
        }
        h.itemView.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        h.itemView.setOnLongClickListener {
            h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onLongClick); true
        }
        h.more.setOnClickListener { v -> h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onMore(v, it) } }
        h.drag.visibility = if (draggable) View.VISIBLE else View.GONE
        if (draggable) {
            h.drag.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(h)
                false
            }
        }
    }
}

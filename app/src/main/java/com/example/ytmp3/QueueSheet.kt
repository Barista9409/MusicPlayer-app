package com.example.ytmp3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/**
 * 再生キュー: 次に流れる曲の一覧。タップでその曲へ、✕ で外す、≡ をドラッグで並べ替え。
 * シャッフル中は流れる順に表示(並べ替えはシャッフルを切った時だけ)。
 */
class QueueSheet : BottomSheetDialogFragment(), Player.Listener {
    private var connection: PlayerConnection? = null
    private var controller: MediaController? = null
    private val adapter = Adapter()
    private lateinit var list: RecyclerView
    private lateinit var header: TextView
    private lateinit var note: TextView
    private var scrolledOnce = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), 0)
        }
        header = TextView(ctx).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        }
        top.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 今のキュー(あなたのミックス・ラジオなど)をそのままプレイリストに
        top.addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "保存"
            contentDescription = "再生キューをプレイリストとして保存"
            setOnClickListener { saveAsPlaylist() }
        })
        top.addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "次以降を消す"
            setOnClickListener { clearUpcoming() }
        })
        root.addView(top)
        note = TextView(ctx).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding((20 * d).toInt(), 0, (20 * d).toInt(), (4 * d).toInt())
        }
        root.addView(note)
        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@QueueSheet.adapter
            itemAnimator = null
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.7).toInt()))
        touchHelper.attachToRecyclerView(list)
        return root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = true // 下にスワイプで閉じる(一覧が一番上の時)
        }
        connection = PlayerConnection(requireContext()) { c ->
            controller = c
            c.addListener(this)
            refresh()
        }.also { it.connect() }
    }

    override fun onStop() {
        controller?.removeListener(this)
        controller = null
        connection?.release()
        connection = null
        super.onStop()
    }

    override fun onEvents(player: Player, events: Player.Events) = refresh()

    /** 表示する順(シャッフル中は流れる順)のキュー内の位置 */
    private fun order(c: MediaController): List<Int> {
        val tl = c.currentTimeline
        if (tl.isEmpty) return emptyList()
        val shuffle = c.shuffleModeEnabled
        val out = ArrayList<Int>(tl.windowCount)
        var i = tl.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && out.size < tl.windowCount) {
            out += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        return out
    }

    private fun refresh() {
        val c = controller ?: return
        if (!isAdded || dragFrom >= 0) return
        adapter.items = order(c)
        adapter.current = c.currentMediaItemIndex
        adapter.notifyDataSetChanged()
        val pos = adapter.items.indexOf(c.currentMediaItemIndex)
        val left = if (pos >= 0) adapter.items.size - pos - 1 else adapter.items.size
        header.text = "再生キュー"
        note.text = "${adapter.items.size} 曲 · この後 $left 曲" +
            if (c.shuffleModeEnabled) " · シャッフル中(並べ替えはシャッフルを切ると使えます)" else ""
        if (!scrolledOnce && pos >= 0) {
            scrolledOnce = true
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
        }
    }

    /**
     * 再生キューをプレイリストとして保存。ライブラリにない曲は ☁ オンラインの曲として入れる(ダウンロードはしない)。
     * あなたのミックスは押すたびに入れ替わるので、気に入った並びを残せるように
     */
    private fun saveAsPlaylist() {
        val c = controller ?: return
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        if (items.isEmpty()) return
        val ctx = requireContext()
        val day = java.text.SimpleDateFormat("M/d", java.util.Locale.JAPAN).format(java.util.Date())
        val base = PlayLog.current?.title?.takeIf { it.isNotBlank() } ?: "再生キュー"
        val input = android.widget.EditText(ctx).apply { setText("$base $day"); selectAll() }
        val box = android.widget.FrameLayout(ctx).apply {
            val d = resources.displayMetrics.density
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("${items.size} 曲をプレイリストとして保存")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "$base $day" }
                val ids = items.mapNotNull { item ->
                    item.mediaId.toLongOrNull()?.let { Repository.track(it) }?.id
                        ?: Liked.fromItem(item)?.let { OnlineLibrary.add(it)?.id }
                }.distinct()
                if (ids.isEmpty()) { android.widget.Toast.makeText(ctx, "保存できる曲がありませんでした", android.widget.Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                Repository.createPlaylist(name, ids)
                android.widget.Toast.makeText(ctx, "プレイリスト「$name」を作りました(${ids.size} 曲)", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun clearUpcoming() {
        val c = controller ?: return
        val order = order(c)
        val pos = order.indexOf(c.currentMediaItemIndex)
        // 後ろから消す(位置がずれないように)
        order.drop(pos + 1).sortedDescending().forEach { c.removeMediaItem(it) }
    }

    /** ドラッグ中は一覧を作り直さない(離した時にまとめて動かす) */
    private var dragFrom = -1
    private var dragTo = -1

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = false
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val c = controller ?: return false
            if (c.shuffleModeEnabled) return false
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            if (dragFrom < 0) dragFrom = from
            dragTo = to
            val l = adapter.items.toMutableList()
            l.add(to, l.removeAt(from))
            adapter.items = l
            adapter.notifyItemMoved(from, to)
            return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            // 曲を並べ替え中は、シートが一緒に動かないように
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG)
                (dialog as? BottomSheetDialog)?.behavior?.isDraggable = false
        }
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            (dialog as? BottomSheetDialog)?.behavior?.isDraggable = true
            val from = dragFrom; val to = dragTo
            dragFrom = -1; dragTo = -1
            // シャッフルなしなら表示順 = キューの順
            if (from >= 0 && to >= 0 && from != to) controller?.moveMediaItem(from, to) else refresh()
        }
    })

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Int> = emptyList()
        var current = -1

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object :
            RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)) {}

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val c = controller ?: return
            val index = items[position]
            if (index >= c.mediaItemCount) return
            val item = c.getMediaItemAt(index)
            val v = h.itemView
            val m = item.mediaMetadata
            Thumbs.bindItem(v.findViewById<ImageView>(R.id.art), item.mediaId, m.artworkUri)
            val title = v.findViewById<TextView>(R.id.title)
            title.text = m.title ?: ""
            // 保存の状態: 印なし = 端末にある / ☁ ネットから(試聴・オンラインの曲)
            val lib = item.mediaId.toLongOrNull()?.let { Repository.track(it) }
            val mark = when {
                lib != null && !Repository.isMissing(lib.id) -> ""
                else -> {
                    val vid = Liked.vidOf(item)
                    if (vid != null && StreamCache.isCached(v.context, vid)) "☁✓ " else "☁ "
                }
            }
            v.findViewById<TextView>(R.id.subtitle).text = mark + (m.artist ?: "")
            val playing = index == current
            title.setTextColor(MaterialColors.getColor(title,
                if (playing) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface))
            v.alpha = if (items.indexOf(current) > position) 0.55f else 1f // 再生済みは薄く
            v.setOnClickListener {
                // 試聴曲なら先に再生 URL を取りに行って、すぐ鳴るように
                StreamDownload.videoId(item)?.let { runCatching { Searcher.warm(it) } }
                c.playAt(index)
            }
            v.findViewById<ImageButton>(R.id.remove).apply {
                visibility = if (playing) View.INVISIBLE else View.VISIBLE
                setOnClickListener { c.removeMediaItem(index) }
            }
            v.findViewById<View>(R.id.drag).apply {
                alpha = if (c.shuffleModeEnabled) 0.25f else 1f
                setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN && !c.shuffleModeEnabled) touchHelper.startDrag(h)
                    false
                }
            }
        }
    }

    companion object {
        fun show(activity: FragmentActivity) {
            if (activity.supportFragmentManager.findFragmentByTag("queue") != null) return
            QueueSheet().show(activity.supportFragmentManager, "queue")
        }
    }
}

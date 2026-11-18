package com.example.ytmp3

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import java.util.Calendar

/**
 * 聴いた記録: 期間(今日までの 7 日・30 日・90 日)ごとの聴いた時間・再生回数・曲数、
 * ひとことハイライト・リスナータイプ・連続記録・新しく出会った曲、
 * よく聴いた曲 TOP5(タップで再生)、アーティスト TOP3(タップでラジオ)、聴く時間帯。
 */
class ListeningStats : BottomSheetDialogFragment() {

    data class Summary(
        val plays: Int, val totalMs: Long, val songs: Int,
        val topSongs: List<Pair<Track, Int>>, val topArtists: List<Triple<String, Int, Track?>>,
        /** 朝・昼・夕方・夜 の再生回数 */
        val dayParts: IntArray,
        /** 聴いた時間に推定が混ざっている(記録を始める前の分) */
        val estimated: Boolean = false,
        /** ひとつ前の同じ長さの期間の聴いた時間(記録が足りなければ null) */
        val prevTotalMs: Long? = null,
        /** この期間に初めて聴いた曲(多く聴いた順) */
        val newSongs: List<Track> = emptyList(),
        /** ひとことハイライト(上ほど目立つもの) */
        val highlights: List<String> = emptyList(),
        /** リスナータイプ(名前, 説明) */
        val type: Pair<String, String>? = null,
        /** 何日続けて聴いているか(今日まだなら昨日まで) */
        val streak: Int = 0,
        val playedToday: Boolean = false,
    )

    private enum class Period(val days: Int, val label: String) { D7(7, "7日"), D30(30, "30日"), D90(90, "90日") }

    private var period = Period.D30
    private lateinit var body: LinearLayout
    private val dp get() = resources.displayMetrics.density

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = NestedScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
        }
        col.addView(TextView(ctx).apply {
            text = "聴いた記録"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        })
        // 期間の切り替え
        val chips = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        Period.entries.forEach { p ->
            chips.addView(Chip(ctx, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = p.label
                isCheckable = true
                isChecked = p == period
                setOnClickListener { if (period != p) { period = p; render() } }
            })
        }
        col.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * dp).toInt() })
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        root.addView(col)
        render()
        return root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true }
    }

    private fun from(p: Period): Long = daysFrom(p.days)

    // ───────── 表示 ─────────

    private fun render() {
        val ctx = requireContext()
        body.removeAllViews()
        val s = summarize(from(period), period.days)
        // 数えている日付の範囲
        val fmt = java.text.SimpleDateFormat("M/d", java.util.Locale.JAPAN)
        body.addView(TextView(ctx).apply {
            text = "${fmt.format(java.util.Date(from(period)))}〜${fmt.format(java.util.Date())} の ${period.days} 日間(今日まで)"
            alpha = 0.7f
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        })
        if (s.plays == 0) {
            body.addView(TextView(ctx).apply {
                text = "この ${period.days} 日間はまだ記録がありません\n曲を 30 秒以上聴くと記録されます"
                gravity = Gravity.CENTER
                setPadding(0, (48 * dp).toInt(), 0, (48 * dp).toInt())
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                alpha = 0.7f
            })
            return
        }

        // ひとことハイライト + リスナータイプ + 連続記録
        highlightCard(s)

        // 大きな数字 3 つ
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(statCard((if (s.estimated) "約" else "") + hm(s.totalMs), "聴いた時間" + compare(s, period.days), primary = true), weight(1.4f))
        row.addView(statCard("${s.plays}", "再生回数"), weight(1f))
        row.addView(statCard("${s.songs}", "曲"), weight(0.8f))
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * dp).toInt() })
        if (s.estimated) body.addView(TextView(ctx).apply {
            text = "${fmt.format(java.util.Date(ListenTime.since))} より前の時間は、曲の長さからの推定です(1 回 10 分まで)"
            alpha = 0.6f
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0)
        })

        // よく聴いた曲
        section("よく聴いた曲")
        val max = s.topSongs.firstOrNull()?.second ?: 1
        s.topSongs.forEachIndexed { i, (t, n) ->
            body.addView(rankRow(i + 1, t.thumbPath, t.title, t.artist, n, max) {
                (activity as? MainActivity)?.player?.play(s.topSongs.map { it.first }, i)
            })
        }
        body.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "TOP ${s.topSongs.size} をまとめて再生"
            setIconResource(R.drawable.ic_play)
            setOnClickListener { (activity as? MainActivity)?.player?.play(s.topSongs.map { it.first }, 0); dismiss() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })

        // 新しく出会った曲(90 日は記録の始まりと重なるので出さない)
        if (period != Period.D90 && s.newSongs.isNotEmpty()) {
            section("新しく出会った曲(${s.newSongs.size} 曲)")
            val counts = PlayLog.playsSince(from(period)).groupingBy { it.first }.eachCount()
            val list = s.newSongs.take(5)
            val nmax = list.maxOf { counts[it.id] ?: 1 }
            list.forEachIndexed { i, t ->
                body.addView(rankRow(i + 1, t.thumbPath, t.title, t.artist, counts[t.id] ?: 1, nmax) {
                    (activity as? MainActivity)?.player?.play(s.newSongs, i)
                })
            }
        }

        // よく聴いたアーティスト
        if (s.topArtists.isNotEmpty()) {
            section("よく聴いたアーティスト")
            val amax = s.topArtists.first().second
            s.topArtists.forEachIndexed { i, (name, n, t) ->
                body.addView(rankRow(i + 1, t?.thumbPath, name, "タップでラジオ", n, amax, round = true) {
                    (activity as? MainActivity)?.let { a -> Radio.forArtist(a, a.player, name); dismiss() }
                })
            }
        }

        // 聴く時間帯
        section("聴く時間帯")
        val labels = arrayOf("朝\n5〜11時", "昼\n11〜17時", "夕方\n17〜21時", "夜\n21〜5時")
        val pmax = s.dayParts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val bars = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val primary = MaterialColors.getColor(body, androidx.appcompat.R.attr.colorPrimary)
        val track = MaterialColors.getColor(body, com.google.android.material.R.attr.colorSurfaceVariant)
        s.dayParts.forEachIndexed { i, n ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(TextView(ctx).apply {
                text = "$n"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            })
            val barBox = FrameLayout(ctx)
            barBox.addView(View(ctx).apply { background = rounded(track) }, FrameLayout.LayoutParams(-1, (90 * dp).toInt()))
            val hgt = (90 * dp * n / pmax).toInt().coerceAtLeast(if (n > 0) (4 * dp).toInt() else 0)
            barBox.addView(View(ctx).apply { background = rounded(if (n == pmax) primary else primary.withAlpha(0.55f)) },
                FrameLayout.LayoutParams(-1, hgt, Gravity.BOTTOM))
            col.addView(barBox, LinearLayout.LayoutParams((36 * dp).toInt(), (90 * dp).toInt()).apply { topMargin = (4 * dp).toInt() })
            col.addView(TextView(ctx).apply {
                text = labels[i]; gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setPadding(0, (6 * dp).toInt(), 0, 0)
            })
            bars.addView(col, weight(1f))
        }
        body.addView(bars, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        val best = s.dayParts.indices.maxByOrNull { s.dayParts[it] } ?: 0
        body.addView(TextView(ctx).apply {
            text = "いちばん聴いているのは${arrayOf("朝", "昼", "夕方", "夜")[best]}です"
            gravity = Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, (8 * dp).toInt(), 0, 0)
            alpha = 0.8f
        })
    }

    private fun hm(ms: Long): String {
        val h = ms / 3600_000; val m = (ms / 60_000) % 60
        return if (h > 0) "${h}時間${m}分" else "${m}分"
    }

    /** 前の同じ長さの期間との差(「前の30日より +3時間」) */
    private fun compare(s: Summary, days: Int): String {
        val prev = s.prevTotalMs ?: return ""
        val diff = s.totalMs - prev
        if (kotlin.math.abs(diff) < 60_000) return "\n前の${days}日と同じくらい"
        return "\n前の${days}日より ${if (diff > 0) "+" else "−"}${hm(kotlin.math.abs(diff))}"
    }

    /** いちばん上のカード: ひとこと・タイプ・連続記録 */
    private fun highlightCard(s: Summary) {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            radius = 16 * dp; strokeWidth = 0
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer))
        }
        val on = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSecondaryContainer)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        }
        s.type?.let { (name, desc) ->
            col.addView(TextView(ctx).apply {
                text = "あなたは $name"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge); setTextColor(on)
            })
            col.addView(TextView(ctx).apply {
                text = desc; alpha = 0.85f
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall); setTextColor(on)
            })
        }
        s.highlights.take(3).forEachIndexed { i, h ->
            col.addView(TextView(ctx).apply {
                text = "・$h"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium); setTextColor(on)
                setPadding(0, ((if (i == 0 && s.type != null) 10 else 4) * dp).toInt(), 0, 0)
            })
        }
        if (s.streak >= 2) col.addView(TextView(ctx).apply {
            text = if (s.playedToday) "🔥 ${s.streak} 日連続で聴いています" else "🔥 ${s.streak} 日連続中 · 今日聴けば ${s.streak + 1} 日に"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge); setTextColor(on)
            setPadding(0, (10 * dp).toInt(), 0, 0)
        })
        if (col.childCount == 0) return
        card.addView(col)
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() })
    }

    private fun weight(w: Float) = LinearLayout.LayoutParams(0, -2, w).apply {
        marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt()
    }

    private fun Int.withAlpha(a: Float) = (this and 0x00FFFFFF) or ((255 * a).toInt() shl 24)

    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = 8 * dp
    }

    private fun section(title: String) {
        body.addView(TextView(requireContext()).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding(0, (24 * dp).toInt(), 0, (8 * dp).toInt())
        })
    }

    private fun statCard(value: String, label: String, primary: Boolean = false): View {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            radius = 16 * dp
            strokeWidth = 0
            setCardBackgroundColor(MaterialColors.getColor(this,
                if (primary) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceVariant))
        }
        val on = MaterialColors.getColor(card,
            if (primary) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
        }
        col.addView(TextView(ctx).apply {
            text = value; setTextColor(on); maxLines = 1
            setTextAppearance(if (primary) com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall
                else com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setTextColor(on)
        })
        col.addView(TextView(ctx).apply {
            text = label; setTextColor(on); alpha = 0.8f
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setTextColor(on)
        })
        card.addView(col)
        return card
    }

    private fun rankRow(rank: Int, thumb: String?, title: String, sub: String, n: Int, max: Int,
                        round: Boolean = false, onClick: () -> Unit): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            background = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a ->
                a.getDrawable(0).also { a.recycle() }
            }
            setOnClickListener { onClick() }
        }
        row.addView(TextView(ctx).apply {
            text = "$rank"; gravity = Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            if (rank == 1) setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
        }, LinearLayout.LayoutParams((28 * dp).toInt(), -2))
        val art = ShapeableImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(if (round) 24 * dp else 8 * dp).build()
        }
        Thumbs.bind(art, thumb)
        row.addView(art, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()).apply { marginStart = (6 * dp).toInt() })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = title; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        })
        if (sub.isNotBlank()) col.addView(TextView(ctx).apply {
            text = sub; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; alpha = 0.7f
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        })
        // 回数を横棒でも
        col.addView(LinearProgressIndicator(ctx).apply {
            this.max = max; setProgressCompat(n, false); trackCornerRadius = (3 * dp).toInt(); trackThickness = (5 * dp).toInt()
            trackColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
            setIndicatorColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt() })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * dp).toInt() })
        row.addView(TextView(ctx).apply {
            text = "${n}回"; gravity = Gravity.END
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        }, LinearLayout.LayoutParams((52 * dp).toInt(), -2))
        return row
    }

    companion object {
        fun show(activity: FragmentActivity) {
            if (activity.supportFragmentManager.findFragmentByTag("stats") != null) return
            ListeningStats().show(activity.supportFragmentManager, "stats")
        }

        private const val DAY = 86_400_000L

        /** 今日を含めて days 日さかのぼった日の始まり */
        fun daysFrom(days: Int): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(days - 1))
        }.timeInMillis

        /** 聴いた時間: 記録を始めてからは実際の時間、それより前は曲の長さから推定(1 回 10 分まで) */
        private fun listenedMs(from: Long, to: Long, tracks: Map<Long, Track>, plays: List<Pair<Long, Long>>): Pair<Long, Boolean> {
            val real = ListenTime.sum(maxOf(from, ListenTime.since), to)
            if (from >= ListenTime.since) return real to false
            val est = plays.filter { it.second in from until ListenTime.since }
                .sumOf { (id, _) -> minOf(tracks[id]?.durationMs ?: 0L, 10 * 60_000L) }
            return (real + est) to true
        }

        /** 期間の集計(ホームのカードでも使う)。days は比べる長さ(前の期間との差に使う) */
        fun summarize(from: Long, days: Int = 30): Summary {
            val now = System.currentTimeMillis()
            val all = PlayLog.playsSince(0)
            val plays = all.filter { it.second >= from }
            val tracks = Repository.tracks.value.associateBy { it.id }
            val (totalMs, estimated) = listenedMs(from, now, tracks, all)
            // 前の同じ長さの期間(記録が残っている時だけ)
            val prevFrom = from - days * DAY
            val oldest = all.minOfOrNull { it.second }
            val prevTotal = if (oldest != null && oldest <= prevFrom + DAY) listenedMs(prevFrom, from - 1, tracks, all).first else null
            val counts = plays.groupingBy { it.first }.eachCount()
            val topSongs = counts.entries.sortedByDescending { it.value }
                .mapNotNull { e -> tracks[e.key]?.let { it to e.value } }.take(5)
            val byArtist = plays.mapNotNull { (id, _) -> tracks[id]?.takeIf { it.artist.isNotBlank() } }
                .groupBy { Radio.mainArtist(it.artist) }
            val topArtists = byArtist.entries.sortedByDescending { it.value.size }.take(3)
                .map { (a, ts) -> Triple(a, ts.size, ts.firstOrNull { it.thumbPath != null }) }
            val parts = IntArray(4)
            val cal = Calendar.getInstance()
            plays.forEach { (_, time) ->
                cal.timeInMillis = time
                val h = cal.get(Calendar.HOUR_OF_DAY)
                parts[when (h) { in 5..10 -> 0; in 11..16 -> 1; in 17..20 -> 2; else -> 3 }]++
            }
            // 初めて聴いた曲(この期間より前の記録に無い曲)
            val firstPlay = all.groupBy { it.first }.mapValues { e -> e.value.minOf { it.second } }
            val newSongs = firstPlay.filter { it.value >= from }.keys
                .mapNotNull { tracks[it] }.sortedByDescending { counts[it.id] ?: 0 }
            // 連続記録
            val playDays = all.map { ListenTime.dayOf(it.second) }.toSet()
            val today = ListenTime.dayOf(now)
            val playedToday = today in playDays
            var d = if (playedToday) today else today - 1
            var streak = 0
            while (d in playDays) { streak++; d-- }

            val songs = counts.size
            val highlights = mutableListOf<String>()
            val topArtist = topArtists.firstOrNull()
            if (topArtist != null && plays.size >= 5) {
                val share = topArtist.second * 100 / plays.size
                highlights += if (share >= 40) "${topArtist.first} 一色でした(再生の $share%)"
                    else "いちばん聴いたアーティストは ${topArtist.first}(再生の $share%)"
            }
            // 1 日にいちばん繰り返した曲
            plays.groupingBy { it.first to ListenTime.dayOf(it.second) }.eachCount().maxByOrNull { it.value }
                ?.takeIf { it.value >= 3 }?.let { (key, n) ->
                    tracks[key.first]?.let { t ->
                        val date = java.text.SimpleDateFormat("M/d", java.util.Locale.JAPAN).format(java.util.Date(key.second * DAY))
                        highlights += "「${t.title}」を 1 日に $n 回聴いた日がありました($date)"
                    }
                }
            if (newSongs.isNotEmpty() && days < 90) highlights += "新しく出会った曲が ${newSongs.size} 曲"
            topSongs.firstOrNull()?.let { (t, n) -> if (n >= 5) highlights += "いちばん聴いた曲は「${t.title}」($n 回)" }

            // リスナータイプ(10 回以上聴いた時だけ)
            val type = if (plays.size < 10) null else {
                val night = parts[3] * 100 / plays.size
                val morning = parts[0] * 100 / plays.size
                val share = (topArtist?.second ?: 0) * 100 / plays.size
                val repeat = plays.size.toFloat() / songs.coerceAtLeast(1)
                val fresh = newSongs.size * 100 / songs.coerceAtLeast(1)
                when {
                    share >= 50 -> "💘 一途派" to "${topArtist?.first} への愛が深い。再生の $share% がこの人です"
                    repeat >= 4f -> "🔁 ヘビロテ派" to "気に入った曲をとことん聴くタイプ。1 曲あたり平均 ${"%.1f".format(repeat)} 回"
                    days < 90 && fresh >= 40 -> "🧭 開拓派" to "新しい曲にどんどん出会うタイプ。聴いた曲の $fresh% が初めての曲"
                    night >= 50 -> "🌙 夜ふかし派" to "夜 21 時以降がいちばんの音楽タイム(再生の $night%)"
                    morning >= 40 -> "☀️ 朝活派" to "朝に音楽でスイッチを入れるタイプ(再生の $morning%)"
                    else -> "🎧 バランス派" to "いろいろな時間・いろいろな曲をまんべんなく"
                }
            }
            return Summary(plays.size, totalMs, songs, topSongs, topArtists, parts,
                estimated, prevTotal, newSongs, highlights, type, streak, playedToday)
        }
    }
}

package com.example.ytmp3

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 設定 → ライブラリ → ストレージ: 何にどれだけ容量を使っているか。
 * ダウンロードした曲 / 一時保存 / ジャケット・歌詞 / その他、と端末の空き。減らす操作もここから
 */
object StorageSheet {

    /** 容量の内訳(バイト) */
    data class Usage(
        val downloads: Long, val downloadCount: Int,
        val videos: Long, val videoCount: Int,
        val cache: Long, val art: Long, val other: Long, val free: Long,
    ) {
        val total get() = downloads + videos + cache + art + other
    }

    /** 設定の行に出す短い説明(重い計算はしない) */
    fun summary(c: Context): String {
        val dl = downloaded().sumOf { it.sizeBytes }
        val cache = runCatching { StreamCache.get(c).cacheSpace }.getOrDefault(0)
        return "ダウンロード ${size(dl)} · 一時保存 ${size(cache)}"
    }

    /** 端末にファイルがある曲 */
    private fun downloaded() = Repository.tracks.value.filter { !Repository.isMissing(it.id) && it.uri.isNotBlank() }

    private fun dirSize(f: File): Long =
        if (!f.exists()) 0 else if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0

    fun measure(c: Context): Usage {
        val dl = downloaded()
        val files = c.filesDir
        val cacheDir = c.cacheDir
        val streamDir = File(cacheDir, "stream_cache")
        val coilDir = File(cacheDir, "image_cache")
        val artDirs = listOf(File(files, "thumbs"), File(files, "thumbs_hi"), File(files, "lyrics"), coilDir)
        val art = artDirs.sumOf { dirSize(it) }
        val cache = runCatching { StreamCache.get(c).cacheSpace }.getOrDefault(dirSize(streamDir))
        val other = (dirSize(files) + dirSize(cacheDir) - art - dirSize(streamDir)).coerceAtLeast(0)
        val free = runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0)
        // 動画の音で聴いている曲(音声を消した曲)は動画の方だけ数える
        val vids = Repository.tracks.value.filter { it.hasVideo }
        val audioBytes = dl.filter { it.uri != it.videoUri }.sumOf { it.sizeBytes }
        return Usage(audioBytes, dl.size, vids.sumOf { it.videoBytes }, vids.size, cache, art, other, free)
    }

    fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format("%.0f MB", b / 1048576.0)
        b > 0 -> String.format("%.0f KB", b / 1024.0)
        else -> "0 MB"
    }

    // ───────── 一時保存の上限 ─────────

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** 一時保存の上限(MB。0 = 無制限) */
    fun cacheLimitMb(c: Context) = prefs(c).getInt("stream_cache_mb", 500)
    fun cacheLimitLabel(mb: Int) = if (mb == 0) "無制限" else if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

    fun show(main: MainActivity) {
        val ctx = main
        val d = ctx.resources.displayMetrics.density
        val dialog = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (24 * d).toInt())
        }
        root.addView(TextView(ctx).apply {
            text = "ストレージ"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        })
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body)
        body.addView(TextView(ctx).apply { text = "計算しています…"; setPadding(0, (16 * d).toInt(), 0, 0) })
        dialog.setContentView(android.widget.ScrollView(ctx).apply { addView(root) })
        dialog.show()

        fun render(u: Usage) {
            body.removeAllViews()
            // 種類ごとにはっきり違う色(テーマの色だと似た色ばかりで見分けにくいため)
            val colors = intArrayOf(0xFF4C8DF6.toInt(), 0xFFE5616E.toInt(), 0xFF26B5A0.toInt(), 0xFFF2B33D.toInt(), 0xFF9E9E9E.toInt())
            body.addView(TextView(ctx).apply {
                text = "このアプリで ${size(u.total)} · 端末の空き ${size(u.free)}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                alpha = 0.8f
                setPadding(0, (4 * d).toInt(), 0, (12 * d).toInt())
            })
            // 割合のバー
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                clipToOutline = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 7 * d
                    setColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurfaceVariant))
                }
            }
            val parts = listOf(u.downloads, u.videos, u.cache, u.art, u.other)
            val sum = parts.sum().coerceAtLeast(1)
            parts.forEachIndexed { i, b ->
                if (b > 0) bar.addView(View(ctx).apply { setBackgroundColor(colors[i]) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (b.toFloat() / sum).coerceAtLeast(0.01f)))
            }
            body.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (14 * d).toInt()))

            /** 1 種類分: 色の四角・名前・容量、説明、その下に操作のボタン */
            fun row(color: Int, title: String, value: String, note: String, actions: List<Pair<String, () -> Unit>>) {
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (18 * d).toInt(), 0, (2 * d).toInt())
                }
                val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(View(ctx).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 3 * d; setColor(color) }
                }, LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
                head.addView(TextView(ctx).apply {
                    text = title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                head.addView(TextView(ctx).apply {
                    text = value
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                })
                box.addView(head)
                box.addView(TextView(ctx).apply {
                    text = note
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    alpha = 0.7f
                    setPadding((26 * d).toInt(), (2 * d).toInt(), 0, 0)
                })
                if (actions.isNotEmpty()) {
                    val btns = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding((26 * d).toInt(), (6 * d).toInt(), 0, 0)
                    }
                    actions.forEach { (label, act) ->
                        btns.addView(com.google.android.material.button.MaterialButton(ctx, null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                            text = label
                            isAllCaps = false
                            minHeight = 0; minimumHeight = 0
                            insetTop = 0; insetBottom = 0
                            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
                            setOnClickListener { act() }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
                    }
                    box.addView(btns)
                }
                body.addView(box)
            }

            row(colors[0], "ダウンロードした曲", size(u.downloads),
                "端末に保存している ${u.downloadCount} 曲。☁ に戻すとファイルだけ消えて、曲はライブラリに残ります", listOf(
                    "大きい順に見る" to { dialog.dismiss(); pickTracks(main, oldOnly = false) },
                    "しばらく聴いていない曲" to { dialog.dismiss(); pickTracks(main, oldOnly = true) },
                ))
            row(colors[1], "動画", size(u.videos),
                "保存した動画 ${u.videoCount} 曲。動画だけ消しても、曲と音声は残ります", if (u.videoCount == 0) emptyList() else listOf(
                    "大きい順に見る" to { dialog.dismiss(); pickVideos(main) },
                ))
            row(colors[2], "一時保存", size(u.cache),
                "オンラインで聴いた曲の保存分。上限 ${cacheLimitLabel(cacheLimitMb(ctx))}(超えたら古いものから自動で消えます)", listOf(
                    "上限を変える" to { dialog.dismiss(); cacheLimitMenu(main) },
                    "消す" to {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("一時保存を消す")
                            .setMessage("${size(u.cache)} を消します。次に聴く時はまたネットから読み込みます")
                            .setNegativeButton("キャンセル", null)
                            .setPositiveButton("消す") { _, _ ->
                                main.lifecycleScope.launch {
                                    withContext(Dispatchers.IO) { StreamCache.clear(ctx) }
                                    android.widget.Toast.makeText(ctx, "一時保存を消しました", android.widget.Toast.LENGTH_SHORT).show()
                                    dialog.dismiss(); show(main)
                                }
                            }
                            .show()
                    },
                ))
            row(colors[3], "ジャケット・歌詞", size(u.art),
                "高画質の画像・歌詞・画像の一時保存(曲の小さいジャケットは消しません)", listOf(
                    "消す" to {
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("ジャケット・歌詞の保存分を消す")
                            .setMessage("高画質のジャケット・歌詞・画像の一時保存を消します。必要になったらまた取得します")
                            .setNegativeButton("キャンセル", null)
                            .setPositiveButton("消す") { _, _ ->
                                main.lifecycleScope.launch {
                                    withContext(Dispatchers.IO) {
                                        File(ctx.filesDir, "thumbs_hi").deleteRecursively()
                                        File(ctx.filesDir, "lyrics").deleteRecursively()
                                        File(ctx.cacheDir, "image_cache").deleteRecursively()
                                    }
                                    runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
                                    android.widget.Toast.makeText(ctx, "消しました", android.widget.Toast.LENGTH_SHORT).show()
                                    dialog.dismiss(); show(main)
                                }
                            }
                            .show()
                    },
                ))
            row(colors[4], "その他", size(u.other), "ライブラリの記録・プレイリスト・バックアップ・エラー履歴など", emptyList())
        }

        main.lifecycleScope.launch {
            val u = withContext(Dispatchers.IO) { measure(ctx) }
            if (dialog.isShowing) render(u)
        }
    }

    /** 大きい順(または長く聴いていない順)の一覧から選んで ☁ オンラインに戻す */
    private fun pickTracks(main: MainActivity, oldOnly: Boolean) {
        val played = PlayLog.counts(90).keys
        val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        val list = downloaded()
            .filter { youtubeVideoId(it.sourceUrl) != null }
            .filter { !oldOnly || (it.id !in played && it.savedAt < cutoff) }
            .sortedByDescending { it.sizeBytes }
        if (list.isEmpty()) {
            android.widget.Toast.makeText(main,
                if (oldOnly) "90 日以上聴いていない曲はありません" else "☁ に戻せる曲はありません(自分で入れた曲は対象外)",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val checked = BooleanArray(list.size) { oldOnly }
        val labels = list.map { "${it.title} — ${size(it.sizeBytes)}" }.toTypedArray()
        MaterialAlertDialogBuilder(main)
            .setTitle(if (oldOnly) "しばらく聴いていない曲(${list.size} 曲)" else "ダウンロードした曲(大きい順)")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("☁ オンラインに戻す") { _, _ ->
                val sel = list.filterIndexed { i, _ -> checked[i] }
                if (sel.isEmpty()) return@setPositiveButton
                main.actions.removeDownload(sel)
            }
            .show()
    }

    /** 保存した動画を大きい順に見て、選んだ動画だけ消す */
    private fun pickVideos(main: MainActivity) {
        val list = Repository.tracks.value.filter { it.hasVideo }.sortedByDescending { it.videoBytes }
        if (list.isEmpty()) return
        val checked = BooleanArray(list.size)
        MaterialAlertDialogBuilder(main)
            .setTitle("保存した動画(大きい順)")
            .setMultiChoiceItems(list.map { "${it.title} — ${size(it.videoBytes)}" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("動画を削除") { _, _ ->
                val sel = list.filterIndexed { i, _ -> checked[i] }
                if (sel.isNotEmpty()) main.actions.removeVideos(sel)
            }
            .show()
    }

    /** 一時保存の上限を選ぶ */
    private fun cacheLimitMenu(main: MainActivity) {
        val ctx = main
        val limits = intArrayOf(500, 1024, 2048, 0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("一時保存の上限")
            .setSingleChoiceItems(limits.map { cacheLimitLabel(it) }.toTypedArray(),
                limits.indexOf(cacheLimitMb(ctx)).coerceAtLeast(0)) { d, i ->
                prefs(ctx).edit().putInt("stream_cache_mb", limits[i]).apply()
                StreamCache.trim(ctx)
                android.widget.Toast.makeText(ctx, "上限を ${cacheLimitLabel(limits[i])} にしました", android.widget.Toast.LENGTH_SHORT).show()
                d.dismiss()
                show(main)
            }
            .setNegativeButton("閉じる", null)
            .show()
    }
}

package com.example.ytmp3

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * ホーム画面ウィジェット(YouTube Music 風)。
 * 横長(4x1)と大きいサイズ(2x2 以上)でレイアウトを切り替える。
 */
class MusicWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREV, ACTION_SHUFFLE, ACTION_REPEAT -> control(context, intent.action!!)
            // アプリを更新した直後: ホーム画面に残っている古い絵(アイコンの番号がずれる)を描き直す
            Intent.ACTION_MY_PACKAGE_REPLACED -> runCatching { refreshAll(context) }
            else -> super.onReceive(context, intent)
        }
    }

    /** PlaybackService に接続して操作を送る */
    private fun control(context: Context, action: String) {
        val pending = goAsync()
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                pending.finish(); return@addListener
            }
            try {
                when (action) {
                    ACTION_PLAY_PAUSE -> {
                        if (c.mediaItemCount == 0) {
                            // 再生キューが無い(ボタンはアプリを開くようになっているはず)
                        } else if (c.isPlaying) {
                            c.pause()
                        } else {
                            if (c.playbackState == Player.STATE_IDLE) c.prepare()
                            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                            c.play()
                        }
                    }
                    ACTION_NEXT -> c.seekToNextMediaItem()
                    ACTION_SHUFFLE -> c.shuffleModeEnabled = !c.shuffleModeEnabled
                    // オフ → 全曲リピート → 1曲リピート → オフ
                    ACTION_REPEAT -> c.repeatMode = when (c.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    ACTION_PREV -> c.seekToPrevious()
                }
            } catch (_: Exception) {
            }
            Handler(Looper.getMainLooper()).postDelayed({
                MediaController.releaseFuture(future)
                pending.finish()
            }, 800)
        }, ContextCompat.getMainExecutor(app))
    }

    companion object {
        private const val ACTION_PLAY_PAUSE = "com.example.ytmp3.widget.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.example.ytmp3.widget.NEXT"
        private const val ACTION_PREV = "com.example.ytmp3.widget.PREV"
        private const val ACTION_SHUFFLE = "com.example.ytmp3.widget.SHUFFLE"
        private const val ACTION_REPEAT = "com.example.ytmp3.widget.REPEAT"
        private const val PREFS = "widget"

        /** 再生状態をウィジェットに反映(PlaybackService から呼ぶ) */
        fun push(context: Context, player: Player?) {
            val item = player?.currentMediaItem
            val track = item?.mediaId?.toLongOrNull()?.let { Repository.track(it) }
            // 試聴中(ライブラリに無い曲)はネットのジャケットを保存して使う
            val artUrl = item?.mediaMetadata?.artworkUri?.toString()?.takeIf { it.startsWith("http") }
            // ライブラリの曲は保存済みのジャケット(ファイルが消えていたら使わない)、無ければネットのジャケット
            val thumb = track?.let { HiArt.cachedPath(it) ?: it.thumbPath }?.takeIf { java.io.File(it).exists() }
                ?: artUrl?.let { cachedArt(context, it) }
            if (artUrl != null && thumb == null) fetchArt(context, artUrl)
            // 中身が前と同じなら書き込みも描き直しもしない
            val sig = listOf(item?.mediaId, item?.mediaMetadata?.title, thumb, artUrl, player?.isPlaying, player?.hasNextMediaItem(),
                player?.shuffleModeEnabled, player?.repeatMode).joinToString("|")
            if (sig == lastSig) return
            lastSig = sig
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("title", item?.mediaMetadata?.title?.toString() ?: track?.title)
                .putString("artist", item?.mediaMetadata?.artist?.toString() ?: track?.artist)
                .putString("thumb", thumb)
                .putString("artUrl", artUrl)
                .putBoolean("playing", player?.isPlaying == true)
                .putBoolean("hasNext", player?.hasNextMediaItem() == true)
                .putBoolean("hasItem", item != null)
                .putBoolean("shuffle", player?.shuffleModeEnabled == true)
                .putInt("repeat", player?.repeatMode ?: Player.REPEAT_MODE_OFF)
                .apply()
            scheduleRefresh(context)
        }

        @Volatile private var lastSig: String? = null

        private val main = Handler(Looper.getMainLooper())
        private var pendingRefresh: Runnable? = null

        /** 再生の変化はまとめて描き直す(短い間に何度も送らないように) */
        private fun scheduleRefresh(context: Context) {
            val app = context.applicationContext
            pendingRefresh?.let { main.removeCallbacks(it) }
            val r = Runnable { pendingRefresh = null; runCatching { refreshAll(app) } }
            pendingRefresh = r
            main.postDelayed(r, 150)
        }

        private fun artFile(context: Context, url: String) =
            java.io.File(context.cacheDir, "widget_art_${url.hashCode().toUInt().toString(16)}.jpg")

        private fun cachedArt(context: Context, url: String): String? =
            artFile(context, url).takeIf { it.exists() && it.length() > 0 }?.absolutePath

        private val fetching = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /** ネットのジャケットを取ってきて保存し、ウィジェットを描き直す(裏で) */
        private fun fetchArt(context: Context, url: String) {
            if (!fetching.add(url)) return
            val app = context.applicationContext
            Thread {
                try {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 8000
                    val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) } ?: return@Thread
                    c.disconnect()
                    // 正方形に切り抜いて小さく保存
                    val side = minOf(bmp.width, bmp.height)
                    val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                    val small = Bitmap.createScaledBitmap(sq, 480, 480, true)
                    val f = artFile(app, url)
                    java.io.FileOutputStream(f).use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    // 今も同じ曲を表示中なら差し替え
                    val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    if (p.getString("artUrl", null) == url) {
                        p.edit().putString("thumb", f.absolutePath).apply()
                        android.os.Handler(android.os.Looper.getMainLooper()).post { refreshAll(app) }
                    }
                } catch (_: Exception) {
                } finally {
                    fetching.remove(url)
                }
            }.start()
        }

        /** 再生が止まった(サービス終了)ことを反映 */
        fun stopped(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("playing", false).apply()
            refreshAll(context)
        }

        fun refreshAll(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, MusicWidget::class.java))
            ids.forEach { render(context, m, it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val opts = manager.getAppWidgetOptions(id)
            val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val large = minH >= 110
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val hasItem = p.getBoolean("hasItem", false)
            val playing = p.getBoolean("playing", false)

            val rv = RemoteViews(context.packageName, if (large) R.layout.widget_large else R.layout.widget_small)
            val title = if (hasItem) p.getString("title", "").orEmpty() else context.getString(R.string.app_name)
            val artist = if (hasItem) p.getString("artist", "").orEmpty() else "タップして曲を選ぶ"
            if (large) {
                rv.setTextViewText(R.id.w_title, title)
                rv.setTextViewText(R.id.w_artist, artist)
            } else {
                // YouTube Music 風に「曲名 – アーティスト」を 1 行で(アーティストは薄く)
                val sp = android.text.SpannableStringBuilder(title)
                if (artist.isNotEmpty()) {
                    val start = sp.length
                    sp.append("  – ").append(artist)
                    sp.setSpan(android.text.style.ForegroundColorSpan(0xB3FFFFFF.toInt()), start, sp.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                rv.setTextViewText(R.id.w_title, sp)
            }
            // ボタンの絵は「画像」として渡す。絵の番号(リソース ID)で渡すと、アプリを更新した後に
            // ホーム画面が古い版の番号表で読んでしまい、別のボタンの絵が出ることがある
            rv.setImageViewBitmap(R.id.w_prev, icon(context, R.drawable.wic_prev))
            rv.setImageViewBitmap(R.id.w_next, icon(context, R.drawable.wic_next))
            rv.setImageViewBitmap(R.id.w_shuffle, icon(context, R.drawable.wic_shuffle))
            rv.setImageViewBitmap(R.id.w_play, icon(context, if (playing) R.drawable.wic_pause else R.drawable.wic_play))
            rv.setInt(R.id.w_next, "setImageAlpha", if (!hasItem || p.getBoolean("hasNext", false)) 255 else 100)

            // ウィジェットへ送れる量には上限があるので、画像は控えめな大きさに
            val art = loadArt(context, if (hasItem) p.getString("thumb", null) else null, if (large) 320 else 200, large)
            rv.setImageViewBitmap(R.id.w_art, art)

            val open = PendingIntent.getActivity(
                context, 10,
                Intent(context, if (hasItem) NowPlayingActivity::class.java else MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.w_root, open)
            rv.setOnClickPendingIntent(R.id.w_art, open)
            rv.setOnClickPendingIntent(R.id.w_text, open)
            // 何も再生していなければ再生ボタンはアプリを開く
            rv.setOnClickPendingIntent(R.id.w_play, if (hasItem) action(context, ACTION_PLAY_PAUSE, 1) else open)
            rv.setOnClickPendingIntent(R.id.w_next, action(context, ACTION_NEXT, 2))
            rv.setOnClickPendingIntent(R.id.w_prev, action(context, ACTION_PREV, 3))
            rv.setOnClickPendingIntent(R.id.w_shuffle, action(context, ACTION_SHUFFLE, 4))
            rv.setOnClickPendingIntent(R.id.w_repeat, action(context, ACTION_REPEAT, 5))
            // オンの時は白、オフの時は薄く
            val shuffle = p.getBoolean("shuffle", false)
            val repeat = p.getInt("repeat", Player.REPEAT_MODE_OFF)
            rv.setInt(R.id.w_shuffle, "setImageAlpha", if (shuffle) 255 else 110)
            rv.setImageViewBitmap(R.id.w_repeat, icon(context, if (repeat == Player.REPEAT_MODE_ONE) R.drawable.wic_repeat_one else R.drawable.wic_repeat))
            rv.setInt(R.id.w_repeat, "setImageAlpha", if (repeat != Player.REPEAT_MODE_OFF) 255 else 110)
            rv.setContentDescription(R.id.w_shuffle, if (shuffle) "シャッフル: オン" else "シャッフル: オフ")
            rv.setContentDescription(R.id.w_repeat, when (repeat) {
                Player.REPEAT_MODE_ALL -> "リピート: 全曲"
                Player.REPEAT_MODE_ONE -> "リピート: 1曲"
                else -> "リピート: オフ"
            })
            try {
                manager.updateAppWidget(id, rv)
            } catch (e: Exception) {
                // 大きすぎて送れなかった等: ジャケット無しでもう一度(ボタンだけは必ず使えるように)
                android.util.Log.w("MusicWidget", "update failed", e)
                runCatching {
                    rv.setImageViewBitmap(R.id.w_art, loadArt(context, null, 96, large))
                    manager.updateAppWidget(id, rv)
                }
            }
        }

        private fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            context, code,
            Intent(context, MusicWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** ジャケット画像を角丸にして読み込む(無ければアプリのアートワーク) */
        private val icons = HashMap<Int, Bitmap>()

        /** ウィジェットのボタンの絵(画像にして使い回す) */
        private fun icon(context: Context, res: Int): Bitmap = synchronized(icons) {
            // 小さめ(最大 96px)にしてウィジェットへ送る量を抑える
            icons.getOrPut(res) {
                val d = ContextCompat.getDrawable(context, res)!!
                val w = d.intrinsicWidth.coerceIn(1, 96)
                val h = d.intrinsicHeight.coerceIn(1, 96)
                d.toBitmap(w, h)
            }
        }

        private fun loadArt(context: Context, path: String?, size: Int, large: Boolean): Bitmap {
            val src = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
                ?: ContextCompat.getDrawable(context, R.drawable.art_placeholder)!!.toBitmap(size, size)
            val scaled = Bitmap.createScaledBitmap(src, size, size, true)
            // Android 12 以降はウィジェットの角丸で切り抜かれるので画像は角丸にしない
            val radius = if (large || android.os.Build.VERSION.SDK_INT >= 31) 0f else size * 0.14f
            if (radius <= 0f) return scaled
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            c.drawBitmap(scaled, 0f, 0f, paint)
            return out
        }
    }
}

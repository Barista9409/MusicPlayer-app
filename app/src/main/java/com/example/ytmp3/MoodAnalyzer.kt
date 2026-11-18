package com.example.ytmp3

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 曲調の分析: 曲の中ほど 30 秒の音を読んで、数値にする(端末の中だけで計算する)。
 *  - loud  : 音の強さ(dB。にぎやかな曲ほど大きい)
 *  - bright: 音の明るさ(波が 0 をまたぐ回数。高い音・シャカシャカした音が多いほど大きい)
 *  - onset : 音の立ち上がりの多さ(1 秒あたり。リズムが細かい・忙しい曲ほど大きい)
 *  - bpm   : テンポ(倍や半分に間違えやすいので、分け方では控えめに使う)
 */
object MoodAnalyzer {
    data class Feat(val loud: Double, val bright: Double, val onset: Double, val bpm: Double)

    private const val SECONDS = 30

    /** ファイル(content:// や file://)の中ほどを分析 */
    fun analyze(c: Context, uri: Uri): Feat? = runCatching {
        val ex = MediaExtractor()
        try { ex.setDataSource(c, uri, null); run(ex, startUs = null) } finally { ex.release() }
    }.getOrNull()

    /** 途中まで取ってきたファイルを分析(startSec 秒の所から) */
    fun analyzePartial(path: String, startSec: Int): Feat? = runCatching {
        val ex = MediaExtractor()
        // 後ろが欠けたファイルは「その位置へ飛ぶ」が当てにならないので、頭から読んで手前を捨てる
        try { ex.setDataSource(path); run(ex, startUs = null, skipUs = startSec * 1_000_000L) } finally { ex.release() }
    }.getOrNull()

    private fun run(ex: MediaExtractor, startUs: Long?, skipUs: Long = 0L): Feat? {
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: return null
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
        val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        // イントロを避けて、曲の 40% あたりから(短い曲・長さ不明は頭から)
        val from = if (skipUs > 0) 0L else startUs ?: if (dur > 60_000_000L) (dur * 0.4).toLong() else 0L
        if (from > 0) ex.seekTo(from, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val dec = MediaCodec.createDecoderByType(mime)
        val mono = ShortArrayBuilder()
        var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        var floatPcm = false
        try {
            dec.configure(fmt, null, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 8_000) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = dec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val out = dec.getOutputBuffer(o)!!
                    // 手前を捨てる分(後ろが欠けたファイル)
                    if (info.presentationTimeUs < skipUs && (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                        dec.releaseOutputBuffer(o, false); continue
                    }
                    out.position(info.offset); out.limit(info.offset + info.size)
                    // 左右をまぜて 1 本に
                    if (floatPcm) {
                        val fb = out.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                        while (fb.remaining() >= ch) { var s = 0f; repeat(ch) { s += fb.get() }; mono.add((s / ch * 32767).toInt().coerceIn(-32768, 32767).toShort()) }
                    } else {
                        val sb = out.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        while (sb.remaining() >= ch) { var s = 0; repeat(ch) { s += sb.get() }; mono.add((s / ch).toShort()) }
                    }
                    dec.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    if (mono.size >= rate * SECONDS) break
                }
            }
        } finally {
            runCatching { dec.stop() }; dec.release()
        }
        if (mono.size < rate * 8) return null // 8 秒に満たなければ分析しない
        return features(mono.toArray(), rate)
    }

    /** 数値にする(1 フレーム = 約 23 ミリ秒) */
    internal fun features(pcm: ShortArray, rate: Int): Feat {
        val frame = (rate / 43).coerceAtLeast(256)
        val n = pcm.size / frame
        val rms = DoubleArray(n); var zc = 0L
        for (f in 0 until n) {
            var sum = 0.0
            val o = f * frame
            for (i in o until o + frame) {
                val v = pcm[i] / 32768.0; sum += v * v
                if (i > o && (pcm[i] >= 0) != (pcm[i - 1] >= 0)) zc++
            }
            rms[f] = sqrt(sum / frame)
        }
        val loud = rms.map { 20 * log10(it.coerceAtLeast(1e-5)) }.average()
        val bright = zc.toDouble() / pcm.size * rate // 1 秒あたり 0 をまたぐ回数
        // 立ち上がり: 音の強さが前のフレームより急に増えた所
        val flux = DoubleArray(n) { if (it == 0) 0.0 else (rms[it] - rms[it - 1]).coerceAtLeast(0.0) }
        val mean = flux.average(); val sd = sqrt(flux.map { (it - mean) * (it - mean) }.average())
        val th = mean + 0.5 * sd
        var peaks = 0
        for (i in 1 until n - 1) if (flux[i] > th && flux[i] >= flux[i - 1] && flux[i] >= flux[i + 1]) peaks++
        val secs = pcm.size.toDouble() / rate
        val onset = peaks / secs
        // テンポ: 立ち上がりの並びが何フレームごとにくり返すか(60〜180 BPM の範囲)
        // フレームの区切りと拍の間隔がずれるので、となりの間隔(±1)もまとめて数える。
        // ほぼ同じ強さなら短い間隔(速いテンポ)を選ぶ(半分のテンポに間違えないように)
        val hop = frame.toDouble() / rate
        val minLag = (60.0 / 180 / hop).toInt().coerceAtLeast(1); val maxLag = (60.0 / 60 / hop).toInt().coerceAtMost(n - 2)
        fun raw(lag: Int): Double { if (lag < 1 || lag >= n) return 0.0; var s = 0.0; for (i in lag until n) s += flux[i] * flux[i - lag]; return s }
        val ac = (minLag..maxLag.coerceAtLeast(minLag)).associateWith { raw(it - 1) + raw(it) + raw(it + 1) }
        val best = ac.values.maxOrNull() ?: 0.0
        val bestLag = ac.entries.filter { it.value >= best * 0.9 }.minOfOrNull { it.key } ?: minLag
        val bpm = 60.0 / (bestLag * hop)
        return Feat(loud, bright, onset, bpm)
    }

    /** 増やしながら貯める short の並び */
    private class ShortArrayBuilder {
        private var a = ShortArray(1 shl 16)
        var size = 0
            private set
        fun add(v: Short) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }
}

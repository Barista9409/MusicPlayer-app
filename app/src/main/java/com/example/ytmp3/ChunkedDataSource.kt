package com.example.ytmp3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * YouTube の配信サーバー(googlevideo)は 1 回の接続で丸ごと取ると途中で切ったり極端に遅くしたりするので、
 * yt-dlp と同じく 10MB ずつの範囲指定で順番に取る。
 * URL の clen=(ファイルサイズ)が分からない時はそのまま通す。
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ChunkedDataSource(private val upstream: DataSource, private val chunkSize: Long = 10L shl 20) : DataSource {
    private var spec: DataSpec? = null
    private var passthrough = false
    private var pos = 0L
    private var end = 0L
    private var chunkLeft = 0L
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri.toString()
        val total = if (uri.contains("googlevideo.com"))
            Regex("[?&]clen=(\\d+)").find(uri)?.groupValues?.get(1)?.toLongOrNull() else null
        if (total == null) {
            passthrough = true
            return upstream.open(dataSpec)
        }
        passthrough = false
        spec = dataSpec
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) pos + dataSpec.length else total
        openChunk()
        return end - dataSpec.position
    }

    private fun openChunk() {
        val len = minOf(chunkSize, end - pos)
        upstream.open(spec!!.buildUpon().setPosition(pos).setLength(len).build())
        upstreamOpen = true
        chunkLeft = len
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        while (true) {
            if (pos >= end) return C.RESULT_END_OF_INPUT
            if (chunkLeft <= 0 || !upstreamOpen) {
                if (upstreamOpen) { upstream.close(); upstreamOpen = false }
                openChunk()
            }
            val n = upstream.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
            if (n == C.RESULT_END_OF_INPUT) {
                // この区切りが早く終わったら次の区切りへ
                upstream.close(); upstreamOpen = false; chunkLeft = 0
                continue
            }
            pos += n
            chunkLeft -= n
            return n
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (upstreamOpen || passthrough) upstream.close()
        upstreamOpen = false
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource())
    }
}

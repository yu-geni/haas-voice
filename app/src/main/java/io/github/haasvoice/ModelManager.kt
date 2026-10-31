package io.github.haasvoice

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Haas-ja-v1.0 の ONNX 量子化版（sherpa-onnx 用）をダウンロード・管理する。 */
object ModelManager {
    private const val BASE_URL =
        "https://huggingface.co/oboroge0/Haas-ja-v1.0-onnx-quantized/resolve/main/"

    const val ENCODER = "encoder.quantized.onnx"
    const val DECODER = "decoder.onnx"
    const val JOINER = "joiner.onnx"
    const val TOKENS = "tokens.txt"

    // encoder.data は encoder.quantized.onnx の外部重みファイル。同じフォルダに置く必要がある
    private val FILES = listOf(TOKENS, DECODER, JOINER, ENCODER, "encoder.data")

    fun dir(context: Context): File = File(context.filesDir, "haas-ja-v1.0")

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun isReady(context: Context): Boolean = FILES.all { file(context, it).length() > 0 }

    private val downloading = AtomicBoolean(false)
    val isDownloading: Boolean get() = downloading.get()

    /** ブロッキング。バックグラウンドスレッドから呼ぶこと。 */
    fun download(context: Context, onProgress: (String) -> Unit) {
        if (!downloading.compareAndSet(false, true)) throw IllegalStateException("すでにダウンロード中です")
        try {
            downloadFiles(context, onProgress)
        } finally {
            downloading.set(false)
        }
    }

    private fun downloadFiles(context: Context, onProgress: (String) -> Unit) {
        val dir = dir(context).apply { mkdirs() }
        FILES.forEachIndexed { index, name ->
            val target = File(dir, name)
            if (target.length() > 0) return@forEachIndexed
            val part = File(dir, "$name.part")
            val conn = openFollowingRedirects(BASE_URL + name)
            var total = -1L
            try {
                total = conn.contentLengthLong
                var done = 0L
                var lastReport = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 2_000_000 || done == total) {
                                lastReport = done
                                val pct = if (total > 0) " ${done * 100 / total}%" else ""
                                onProgress("(${index + 1}/${FILES.size}) $name ${done / 1_000_000}MB$pct")
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            // 途中で切れたファイルを完成品として扱わない
            if (total > 0 && part.length() != total) {
                part.delete()
                throw IllegalStateException("$name のダウンロードが途中で切れました。もう一度お試しください")
            }
            if (!part.renameTo(target)) throw IllegalStateException("$name を保存できませんでした")
        }
        onProgress("ダウンロード完了")
    }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            when (val code = conn.responseCode) {
                in 200..299 -> return conn
                in 300..399 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    current = URL(current, location)
                }
                else -> {
                    conn.disconnect()
                    throw IllegalStateException("HTTP $code: $current")
                }
            }
        }
        throw IllegalStateException("リダイレクトが多すぎます: $url")
    }
}

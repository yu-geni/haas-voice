package io.github.haasvoice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** sherpa-onnx で Haas-ja-v1.0 を端末内で実行する。 */
object Transcriber {
    const val SAMPLE_RATE = 16_000

    // モデルは約30秒までのクリップでテストされている。区切りを短くするほど認識中のピークメモリが下がる
    private const val CHUNK_SAMPLES = SAMPLE_RATE * 15
    private const val TAIL_PADDING_SAMPLES = SAMPLE_RATE * 3 / 10

    // 使い終わってからモデルをメモリから外すまでの時間
    private const val IDLE_RELEASE_SECONDS = 60L

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private val releaser = Executors.newSingleThreadScheduledExecutor()
    private var pendingRelease: ScheduledFuture<*>? = null

    fun isLoaded(): Boolean = recognizer != null

    fun load(context: Context): OfflineRecognizer = synchronized(lock) {
        cancelRelease()
        recognizer ?: create(context.applicationContext).also { recognizer = it }
    }

    /** 設定で「常に読み込んでおく」になっていなければ、しばらく使われなかったときにモデルを解放する。 */
    fun scheduleIdleRelease(context: Context) = synchronized(lock) {
        cancelRelease()
        if (Prefs.keepModelLoaded(context)) return
        pendingRelease = releaser.schedule({ release() }, IDLE_RELEASE_SECONDS, TimeUnit.SECONDS)
    }

    fun release() = synchronized(lock) {
        cancelRelease()
        recognizer?.release()
        recognizer = null
    }

    private fun cancelRelease() {
        pendingRelease?.cancel(false)
        pendingRelease = null
    }

    private fun create(context: Context): OfflineRecognizer {
        fun path(name: String) = ModelManager.file(context, name).absolutePath
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path(ModelManager.ENCODER),
                    decoder = path(ModelManager.DECODER),
                    joiner = path(ModelManager.JOINER),
                ),
                tokens = path(ModelManager.TOKENS),
                modelType = "nemo_transducer",
                numThreads = 4,
                provider = "cpu",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }

    fun transcribe(context: Context, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val out = StringBuilder()
        try {
            synchronized(lock) {
                val r = load(context)
                var start = 0
                while (start < samples.size) {
                    val end = min(start + CHUNK_SAMPLES, samples.size)
                    val chunk = samples.copyOfRange(start, end).copyOf(end - start + TAIL_PADDING_SAMPLES)
                    val stream = r.createStream()
                    try {
                        stream.acceptWaveform(chunk, SAMPLE_RATE)
                        r.decode(stream)
                        out.append(r.getResult(stream).text.trim())
                    } finally {
                        stream.release()
                    }
                    start = end
                }
            }
        } finally {
            // 失敗したときもモデルが残り続けないようにする
            scheduleIdleRelease(context)
        }
        return out.toString()
    }
}

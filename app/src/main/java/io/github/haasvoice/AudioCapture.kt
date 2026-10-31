package io.github.haasvoice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/** 16kHz モノラルで録音し、止めたときに float の波形を返す。 */
class AudioCapture(private val onLimit: () -> Unit = {}) {
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    @Volatile private var running = false
    private var data = ShortArray(Transcriber.SAMPLE_RATE * 10)
    private var size = 0

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            Transcriber.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            Transcriber.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, Transcriber.SAMPLE_RATE),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("マイクを開けませんでした")
        }
        record = rec
        size = 0
        running = true
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            record = null
            running = false
            throw IllegalStateException("マイクが使用中です")
        }
        reader = thread(name = "audio-capture") {
            val buf = ShortArray(1600)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break // マイクのエラー。空回りしないよう抜ける
                if (n > 0) append(buf, n)
                if (size >= MAX_SAMPLES) {
                    onLimit()
                    break
                }
            }
        }
    }

    @Synchronized
    private fun append(buf: ShortArray, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        System.arraycopy(buf, 0, data, size, n)
        size += n
    }

    fun stop(): FloatArray {
        running = false
        reader?.join(1000)
        record?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        record = null
        reader = null
        synchronized(this) {
            return FloatArray(size) { data[it] / 32768f }
        }
    }

    companion object {
        // 録音しっぱなし防止（2分）
        const val MAX_SAMPLES = Transcriber.SAMPLE_RATE * 120
    }
}

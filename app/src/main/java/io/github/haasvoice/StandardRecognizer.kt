package io.github.haasvoice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Android 標準の音声認識（SpeechRecognizer）を、キーを押している間だけ使う。
 * 標準の認識は無音で自動的に区切られるので、押している間は区切りごとに確定して聞き直す。
 * メインスレッドから使うこと。
 */
class StandardRecognizer(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onSegment: (String) -> Unit,
    private val onDone: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context, preferredService(context))
    private var held = true
    private var finished = false
    private var lastPartial = ""

    // stopListening 後に結果が返ってこない場合の保険
    private val stopTimeout = Runnable { flushPartialAndFinish() }

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
    }

    fun start() {
        recognizer.setRecognitionListener(listener)
        listen()
    }

    /** キーが離されたとき。最後の結果を確定してから onDone を呼ぶ。 */
    fun stop() {
        if (!held) return
        held = false
        recognizer.stopListening()
        main.postDelayed(stopTimeout, 3_000)
    }

    fun cancel() {
        held = false
        finish()
    }

    private fun listen() {
        lastPartial = ""
        recognizer.startListening(intent)
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNotBlank()) {
                lastPartial = text
                onPartial(text)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results).ifBlank { lastPartial }
            lastPartial = ""
            if (text.isNotBlank()) onSegment(text)
            if (held) listen() else finish()
        }

        override fun onError(error: Int) {
            if (finished) return
            val noSpeech = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            when {
                held && noSpeech -> listen()
                !held -> flushPartialAndFinish()
                else -> {
                    onError(describe(error))
                    held = false
                    flushPartialAndFinish()
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun flushPartialAndFinish() {
        if (finished) return
        if (lastPartial.isNotBlank()) onSegment(lastPartial)
        lastPartial = ""
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        main.removeCallbacks(stopTimeout)
        runCatching { recognizer.destroy() }
        onDone()
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークエラー"
        SpeechRecognizer.ERROR_AUDIO -> "マイクのエラー"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイクの権限がありません"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "音声認識が使用中です"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "日本語が使えません（言語データを確認してください）"
        else -> "エラー $error"
    }

    companion object {
        // Galaxy の既定は Samsung の認識なので、Google の認識サービスがあればそちらを使う
        private val PREFERRED_PACKAGES = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts",
            "com.google.android.as",
        )

        fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)

        /** 使う認識サービス。null なら端末の既定。 */
        fun preferredService(context: Context): ComponentName? {
            val services = context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            return PREFERRED_PACKAGES.firstNotNullOfOrNull { pkg -> services.firstOrNull { it.packageName == pkg } }
                ?: services.firstOrNull { it.packageName.startsWith("com.google.") }
        }

        fun serviceLabel(context: Context): String {
            val service = preferredService(context) ?: return "端末の既定"
            val pm = context.packageManager
            return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(service.packageName, 0)).toString() }
                .getOrDefault(service.packageName)
        }
    }
}

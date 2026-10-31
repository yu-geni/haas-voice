package io.github.haasvoice

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * 音量下キーを長押ししている間だけ録音し、離したら文字起こししてカーソル位置に入力する。
 * 短押しは通常どおり音量を下げる。
 */
class VoiceKeyService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var capture: AudioCapture? = null
    private var standard: StandardRecognizer? = null
    private var keyHeld = false
    // この押下を音声入力用に横取りしているか（ロック中・通話中は横取りしない）
    private var intercepting = false
    // 長押しとして扱ったか（扱ったら、離したときに音量を下げない）
    private var longPressed = false
    private val overlay by lazy { StatusOverlay(this) }

    private val startRecording = Runnable { beginRecording() }

    // 押している途中で画面が消えると離したイベントが届かないので、そこで区切る
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            keyHeld = false
            main.removeCallbacks(startRecording)
            if (capture != null) finishRecording()
            if (standard != null) finishStandard()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Prefs.keepModelLoaded(this) && ModelManager.isReady(this)) preloadModel()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != TRIGGER_KEY) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && !keyHeld) {
                    intercepting = !shouldPassThrough()
                    if (!intercepting) return false
                    keyHeld = true
                    longPressed = false
                    main.postDelayed(startRecording, LONG_PRESS_MS)
                }
                return intercepting
            }
            KeyEvent.ACTION_UP -> {
                if (!intercepting) return false
                intercepting = false
                keyHeld = false
                main.removeCallbacks(startRecording)
                when {
                    capture != null -> finishRecording()
                    standard != null -> finishStandard()
                    !longPressed -> lowerVolume()
                }
            }
        }
        return true
    }

    /** ロック画面や通話中は、音量キーをいつも通り動かす。 */
    private fun shouldPassThrough(): Boolean {
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return true
        val mode = getSystemService(AudioManager::class.java)?.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    private fun beginRecording() {
        longPressed = true
        if (Prefs.engine(this) == Engine.STANDARD) {
            beginStandard()
            return
        }
        if (!ModelManager.isReady(this)) {
            toast("先にアプリを開いてモデルをダウンロードしてください")
            return
        }
        try {
            startMicForeground()
            capture = AudioCapture(onLimit = { main.post { if (capture != null) finishRecording() } })
                .also { it.start() }
            // 話している間にモデルを読み込んでおき、キーを離した後の待ち時間を減らす
            preloadModel()
            vibrate(longArrayOf(0, 40))
            overlay.showRecording()
        } catch (e: Exception) {
            Log.e(TAG, "start recording", e)
            capture = null
            stopMicForeground()
            toast("音声入力を開始できませんでした: ${e.message}")
        }
    }

    private fun finishRecording() {
        val samples = capture?.stop() ?: FloatArray(0)
        capture = null
        stopMicForeground()
        vibrate(longArrayOf(0, 25, 60, 25))
        if (samples.size < Transcriber.SAMPLE_RATE / 4) {
            overlay.hide()
            return
        }
        overlay.showTranscribing()
        // キーを離した時点の入力欄の画面を覚えておき、文字起こし中に別の画面へ移ったら入力しない
        val targetWindowId = TextInserter.findFocusedEditable(this)?.windowId
        worker.execute {
            val text = try {
                Transcriber.transcribe(this, samples)
            } catch (e: Exception) {
                Log.e(TAG, "transcribe", e)
                main.post {
                    overlay.hide()
                    toast("文字起こしに失敗しました: ${e.message}")
                }
                return@execute
            }
            main.post {
                // 次の録音がもう始まっていたら表示を消さない
                if (capture == null) overlay.hide()
                if (text.isBlank()) return@post
                insertOrCopy(text, targetWindowId)
            }
        }
    }

    private fun beginStandard() {
        if (standard != null) return
        if (!StandardRecognizer.isAvailable(this)) {
            toast("この端末では標準の音声認識が使えません")
            return
        }
        val recognizer = StandardRecognizer(
            this,
            onPartial = { overlay.showPartial(it) },
            onSegment = { text -> insertOrCopy(text, null) },
            onDone = {
                standard = null
                if (capture == null) overlay.hide()
            },
            onError = { toast("音声認識に失敗しました: $it") },
        )
        standard = recognizer
        vibrate(longArrayOf(0, 40))
        overlay.showRecording()
        recognizer.start()
    }

    private fun finishStandard() {
        vibrate(longArrayOf(0, 25, 60, 25))
        overlay.showTranscribing()
        standard?.stop()
    }

    private fun insertOrCopy(text: String, targetWindowId: Int?) {
        when (TextInserter.insert(this, text, targetWindowId)) {
            TextInserter.Result.INSERTED -> {}
            TextInserter.Result.WINDOW_CHANGED -> toast("画面が切り替わったので入力せずにコピーしました")
            TextInserter.Result.NO_INPUT -> toast("入力欄が見つからないのでコピーしました（${rootInActiveWindow?.packageName ?: "不明なアプリ"}）")
        }
    }

    private fun preloadModel() {
        worker.execute {
            runCatching {
                Transcriber.load(this)
                // 短すぎて文字起こししなかったときも、使われなければ解放されるようにする
                Transcriber.scheduleIdleRelease(this)
            }.onFailure { Log.e(TAG, "load", it) }
        }
    }

    private fun lowerVolume() {
        getSystemService(AudioManager::class.java).adjustSuggestedStreamVolume(
            AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI
        )
    }

    private fun startMicForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "音声入力中", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("音声入力中…")
            .setOngoing(true)
            .build()
        // バックグラウンドでマイクを使うため、録音中だけフォアグラウンドにする
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed; trying to record anyway", e)
        }
    }

    private fun stopMicForeground() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun vibrate(pattern: LongArray) {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        TextInserter.remember(event?.source)
    }

    override fun onInterrupt() {
        capture?.stop()
        capture = null
        standard?.cancel()
        standard = null
        overlay.hide()
        stopMicForeground()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        onInterrupt()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoiceKeyService"
        private const val TRIGGER_KEY = KeyEvent.KEYCODE_VOLUME_DOWN
        private const val LONG_PRESS_MS = 300L
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
    }
}

package io.github.haasvoice

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** 録音中・文字起こし中であることを、どのアプリの上でも画面上部に表示する。 */
class StatusOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var recordingSince = 0L
    private var partial = ""

    private val tick = object : Runnable {
        override fun run() {
            val sec = (SystemClock.elapsedRealtime() - recordingSince) / 1000
            val head = "🎤 音声入力中  %d:%02d".format(sec / 60, sec % 60)
            view?.text = if (partial.isEmpty()) head else "$head\n$partial"
            main.postDelayed(this, 500)
        }
    }

    fun showRecording() {
        recordingSince = SystemClock.elapsedRealtime()
        partial = ""
        show(RECORDING_COLOR)
        tick.run()
    }

    /** 録音中に途中経過の文字を表示する（標準認識のとき）。 */
    fun showPartial(text: String) {
        partial = text.takeLast(40)
        main.removeCallbacks(tick)
        tick.run()
    }

    fun showTranscribing() {
        main.removeCallbacks(tick)
        show(TRANSCRIBING_COLOR)
        view?.text = "⏳ 文字起こし中…"
    }

    fun hide() {
        main.removeCallbacks(tick)
        partial = ""
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun show(color: Int) {
        val v = view ?: createView().also { view = it }
        (v.background as GradientDrawable).setColor(color)
    }

    private fun createView(): TextView {
        val density = service.resources.displayMetrics.density
        val v = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxWidth = (320 * density).toInt()
            setPadding((18 * density).toInt(), (8 * density).toInt(), (18 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply { cornerRadius = 100 * density }
            elevation = 6 * density
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // タップを奪わず、入力欄のフォーカスも動かさない
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt()
        }
        wm.addView(v, params)
        return v
    }

    companion object {
        private val RECORDING_COLOR = Color.parseColor("#E53935")
        private val TRANSCRIBING_COLOR = Color.parseColor("#455A64")
    }
}

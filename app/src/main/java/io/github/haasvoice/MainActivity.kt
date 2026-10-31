package io.github.haasvoice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** 初期設定（権限・モデル・ユーザー補助）と動作テスト用の画面。 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var downloadButton: Button
    private lateinit var testField: EditText
    private var testCapture: AudioCapture? = null
    private var testStandard: StandardRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(s: String, size: Float = 15f) = TextView(this).apply {
            this.text = s
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
        }.also { root.addView(it) }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }.also { root.addView(it) }

        text("Haas音声入力", 22f)
        text("音量下キーを長押ししている間だけ音声を受け付け（画面上部に「音声入力中」と表示されます）、離すとカーソル位置に文字起こしを入力します。Haas-jaを選ぶと、音声認識はすべて端末内で行います。")
        status = text("")

        text("認識エンジン", 17f)
        val engines = RadioGroup(this)
        Engine.entries.forEach { engine ->
            engines.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = engine.label
                isChecked = Prefs.engine(this@MainActivity) == engine
                setOnClickListener {
                    Prefs.setEngine(this@MainActivity, engine)
                    refreshStatus()
                }
            })
        }
        root.addView(engines)
        text("標準を選ぶとモデルのダウンロードは不要で、話している途中の文字も表示されます。Googleの認識エンジンがあれば優先して使います。", 13f)

        text("① マイクと通知を許可", 17f)
        button("許可する") { requestPermissions() }

        text("② 音声認識モデルをダウンロード（Haas-jaを使う場合。約460MB・初回のみ。Wi-Fi推奨）", 17f)
        downloadButton = button("ダウンロード") { startDownload() }
        root.addView(CheckBox(this).apply {
            text = "モデルを常にメモリに読み込んでおく（認識が少し速くなりますが、約0.5〜1GB使い続けます）"
            isChecked = Prefs.keepModelLoaded(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setKeepModelLoaded(this@MainActivity, checked)
                if (!checked) Transcriber.scheduleIdleRelease(this@MainActivity)
                refreshStatus()
            }
        })
        text("オフのときは、使い終わって1分たつとモデルをメモリから外します。", 13f)

        text("③ ユーザー補助で「Haas音声入力（音量キー）」をオンにする", 17f)
        text("設定 → ユーザー補助 → インストール済みのアプリ。オンにできない場合は、アプリ情報の右上「︙」→「制限付き設定を許可」を先に押してください。", 13f)
        button("ユーザー補助の設定を開く") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("アプリ情報を開く") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
            )
        }

        text("④ テスト", 17f)
        testField = EditText(this).apply { hint = "ここをタップしてから音量下キーを長押し" }
        root.addView(testField)
        val holdButton = Button(this).apply { text = "このボタンを押している間も音声入力できます" }
        holdButton.setOnTouchListener(::onHoldTouch)
        root.addView(holdButton)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus(extra: String? = null) {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val model = ModelManager.isReady(this)
        val engine = Prefs.engine(this)
        val service = isServiceEnabled()
        fun mark(ok: Boolean) = if (ok) "✅" else "⬜"
        status.text = buildString {
            append("${mark(mic)} マイク  ")
            if (engine == Engine.HAAS) append("${mark(model)} モデル  ")
            append("${mark(service)} ユーザー補助\n使用中：${engine.label}")
            if (engine == Engine.STANDARD) append("（${StandardRecognizer.serviceLabel(this@MainActivity)}）")
            append("\n使用メモリ：${usedMemoryMb()}MB（モデル${if (Transcriber.isLoaded()) "読み込み中" else "未読み込み"}）")
            if (extra != null) append("\n").append(extra)
        }
        downloadButton.isEnabled = !model && !ModelManager.isDownloading
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, VoiceKeyService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun startDownload() {
        if (ModelManager.isDownloading) return
        refreshStatus("ダウンロード開始…")
        val app = applicationContext
        thread(name = "model-download") {
            val result = runCatching {
                // 画面が作り直されても二重に始まらないよう、ダウンロード中かどうかは ModelManager が持つ
                ModelManager.download(app) { p -> runOnUiThread { refreshStatus(p) } }
                Transcriber.load(app)
                Transcriber.scheduleIdleRelease(app)
            }
            runOnUiThread {
                refreshStatus(result.exceptionOrNull()?.let { "失敗しました: ${it.message}" } ?: "準備完了")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onHoldTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (Prefs.engine(this) == Engine.STANDARD) {
                    startStandardTest()
                    return true
                }
                if (!ModelManager.isReady(this)) {
                    refreshStatus("先にモデルをダウンロードしてください")
                    return true
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions()
                    return true
                }
                testCapture = runCatching { AudioCapture().also { it.start() } }
                    .onFailure { refreshStatus("失敗しました: ${it.message}") }
                    .getOrNull() ?: return true
                refreshStatus("音声入力中…")
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                testStandard?.let {
                    refreshStatus("認識を確定中…")
                    it.stop()
                    return true
                }
                val samples = testCapture?.stop() ?: return true
                testCapture = null
                refreshStatus("文字起こし中…")
                thread(name = "test-transcribe") {
                    val started = System.currentTimeMillis()
                    val result = runCatching { Transcriber.transcribe(this, samples) }
                    val ms = System.currentTimeMillis() - started
                    runOnUiThread {
                        result.onSuccess {
                            testField.text.insert(testField.selectionStart.coerceAtLeast(0), it)
                            refreshStatus("認識時間 ${ms}ms（音声 ${samples.size / Transcriber.SAMPLE_RATE}秒）")
                        }.onFailure { refreshStatus("失敗しました: ${it.message}") }
                    }
                }
            }
        }
        return true
    }

    private fun startStandardTest() {
        if (testStandard != null) return
        if (!StandardRecognizer.isAvailable(this)) {
            refreshStatus("この端末では標準の音声認識が使えません")
            return
        }
        refreshStatus("音声入力中…")
        testStandard = StandardRecognizer(
            this,
            onPartial = { refreshStatus("音声入力中… $it") },
            onSegment = { testField.text.insert(testField.selectionStart.coerceAtLeast(0), it) },
            onDone = {
                testStandard = null
                refreshStatus("完了")
            },
            onError = { refreshStatus("失敗しました: $it") },
        ).also { it.start() }
    }

    private fun usedMemoryMb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024
}

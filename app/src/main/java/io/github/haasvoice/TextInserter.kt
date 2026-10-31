package io.github.haasvoice

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** フォーカス中の入力欄のカーソル位置に文字を入れる。 */
object TextInserter {
    // アクセシビリティイベントで最後に見えた入力欄（findFocus で見つからないアプリ向けの保険）
    private var lastInput: AccessibilityNodeInfo? = null

    fun remember(node: AccessibilityNodeInfo?) {
        if (node != null && isTextInput(node)) lastInput = node
    }

    enum class Result { INSERTED, NO_INPUT, WINDOW_CHANGED }

    /**
     * カーソル位置に入力する。入力できなければクリップボードにコピーする。
     * expectedWindowId を渡すと、入力欄がその画面にないときは入力しない（別の相手や別アプリへの誤入力を防ぐ）。
     */
    fun insert(service: AccessibilityService, text: String, expectedWindowId: Int? = null): Result {
        val node = findFocusedEditable(service)
        if (node == null) {
            copyToClipboard(service, text)
            return Result.NO_INPUT
        }
        if (expectedWindowId != null && node.windowId != expectedWindowId) {
            copyToClipboard(service, text)
            return Result.WINDOW_CHANGED
        }
        if (setTextAtCursor(node, text)) return Result.INSERTED
        // 貼り付けで入れる。ユーザーがコピーしていた内容は少し後に戻す
        val cm = service.getSystemService(ClipboardManager::class.java)
        val previous = runCatching { cm.primaryClip }.getOrNull()
        copyToClipboard(service, text)
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            if (previous != null) {
                Handler(Looper.getMainLooper()).postDelayed({ runCatching { cm.setPrimaryClip(previous) } }, 500)
            }
            return Result.INSERTED
        }
        return Result.NO_INPUT
    }

    fun findFocusedEditable(service: AccessibilityService): AccessibilityNodeInfo? {
        val roots = buildList {
            service.rootInActiveWindow?.let { add(it) }
            // アプリの画面を優先し、キーボードやオーバーレイは後回し
            service.windows.sortedBy { if (it.type == AccessibilityWindowInfo.TYPE_APPLICATION) 0 else 1 }
                .mapNotNullTo(this) { it.root }
        }
        for (root in roots) {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf(::isTextInput)?.let { return it }
        }
        // findFocus が入力欄を返さないアプリ（WebView など）は、フォーカス中の入力欄をツリーから探す
        for (root in roots) {
            searchFocusedInput(root, 0)?.let { return it }
        }
        val activeWindowId = service.rootInActiveWindow?.windowId
        lastInput?.takeIf { it.refresh() && isTextInput(it) && (it.isFocused || it.windowId == activeWindowId) }
            ?.let { return it }
        return null
    }

    private fun searchFocusedInput(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 60) return null
        if (node.isFocused && isTextInput(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchFocusedInput(child, depth + 1)?.let { return it }
        }
        return null
    }

    // パスワード欄には入れない
    private fun isTextInput(node: AccessibilityNodeInfo): Boolean =
        !node.isPassword && (
            node.isEditable ||
                node.className?.contains("EditText") == true ||
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            )

    private fun setTextAtCursor(node: AccessibilityNodeInfo, text: String): Boolean {
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) {
            start = current.length
            end = current.length
        }
        val from = minOf(start, end)
        val to = maxOf(start, end)
        val updated = current.substring(0, from) + text + current.substring(to)

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false

        val cursor = from + text.length
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        return true
    }

    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("Haas音声入力", text)
        // 音声の内容がクリップボードのプレビューに出ないようにする
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
    }
}

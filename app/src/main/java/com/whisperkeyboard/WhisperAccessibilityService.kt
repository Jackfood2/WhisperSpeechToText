package com.whisperkeyboard

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Enabled by the user in Settings > Accessibility. Lets transcribed text be
 * pasted into ANY focused field even when another keyboard is in use.
 */
class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: WhisperAccessibilityService? = null
        fun isReady(): Boolean = instance != null

        /** Paste text into the currently focused editable node. Returns false if unavailable. */
        fun paste(text: String): Boolean {
            val svc = instance ?: return false
            return try {
                val root = svc.rootInActiveWindow ?: return false
                val node = findEditable(root) ?: return false
                val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("whisper", text))
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (e: Exception) {
                AppLog.w("A11y", "paste failed: ${e.message}")
                false
            }
        }

        private fun findEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
            if (node == null || depth > 30) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val found = findEditable(node.getChild(i), depth + 1)
                if (found != null) return found
            }
            return null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.i("A11y", "accessibility service connected - cross-keyboard typing enabled")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        AppLog.i("A11y", "accessibility service disconnected")
        super.onDestroy()
    }
}

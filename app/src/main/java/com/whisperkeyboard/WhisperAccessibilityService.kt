package com.whisperkeyboard

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: WhisperAccessibilityService? = null
        fun isReady(): Boolean = instance != null

        fun paste(text: String): Boolean {
            val svc = instance ?: return false
            return try {
                val node = findTargetNode(svc) ?: return false
                val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val previousClip =
                    runCatching {
                        cm.primaryClip
                    }.getOrNull()

                val focused = node.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
                )

                if (!focused && !node.isFocused) {
                    return false
                }

                cm.setPrimaryClip(
                    ClipData.newPlainText(
                        "whisper",
                        text
                    )
                )

                val pasted = node.performAction(
                    AccessibilityNodeInfo.ACTION_PASTE
                )

                if (previousClip != null) {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        svc.mainExecutor.execute {
                            runCatching {
                                cm.setPrimaryClip(previousClip)
                            }
                        }
                    } else {
                        runCatching {
                            cm.setPrimaryClip(previousClip)
                        }
                    }
                }

                pasted
            } catch (e: Exception) {
                AppLog.w("A11y", "paste failed: ${e.message}")
                false
            }
        }

        private fun findTargetNode(
            service: WhisperAccessibilityService
        ): AccessibilityNodeInfo? {
            val root =
                service.rootInActiveWindow
                    ?: return findFromWindows(service)

            val focused = root.findFocus(
                AccessibilityNodeInfo.FOCUS_INPUT
            )

            if (
                focused?.isEditable == true &&
                focused.isEnabled
            ) {
                return focused
            }

            return findEditable(root)
        }

        private fun findFromWindows(svc: WhisperAccessibilityService): AccessibilityNodeInfo? {
            return try {
                for (w in svc.windows) {
                    val r = w.root ?: continue
                    val found = findEditable(r)
                    if (found != null) return found
                }
                null
            } catch (_: Exception) { null }
        }

        private fun findEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
            if (node == null || depth > 30) return null
            if (node.isEditable && node.isEnabled) return node
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                val found = findEditable(child, depth + 1)
                if (found != null) return found
            }
            return null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        synchronized(Companion) {
            instance = this
        }
        AppLog.i("A11y", "accessibility service connected - cross-keyboard typing enabled")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        synchronized(Companion) {
            if (instance === this) {
                instance = null
            }
        }
        AppLog.i("A11y", "accessibility service disconnected")
        super.onDestroy()
    }
}

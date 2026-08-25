package com.whisperkeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes transcribed text into the focused field:
 *  1. Whisper IME input connection (fastest, when our keyboard is active)
 *  2. Accessibility paste (works even when Samsung/Gboard owns the field)
 * Retries up to 30s while the processing notification stays visible.
 */
object TextRouter {

    private val handler = Handler(Looper.getMainLooper())
    private val pendingTyping = AtomicInteger(0)
    private const val MAX_ATTEMPTS = 60          // 60 x 500ms = 30s
    private const val TAG = "TextRouter"

    fun pendingTypingCount(): Int = pendingTyping.get()

    /** Route text; returns immediately, retries in background until typed or timeout. */
    fun route(text: String) {
        if (text.isEmpty()) return
        pendingTyping.incrementAndGet()
        ProcessingService.notifyActivity()
        tryRoute(text, 0)
    }
    private fun finish() {
        val left = pendingTyping.decrementAndGet()
        if (left <= 0) ProcessingService.notifyIdle()
    }

    private fun tryRoute(text: String, attempt: Int) {
        // lock screen can never accept typing - park it for the resume flow immediately
        val pm = WhisperApp.holder?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val locked = pm?.isInteractive == false
        if ((locked && attempt >= 4) || attempt >= MAX_ATTEMPTS) {
            val ctx = WhisperApp.holder
            if (ctx != null) {
                OutstandingStore.add(ctx, text)
                toastIt("Held (${OutstandingStore.count(ctx)} waiting) - refocus a field to type")
            }
            finish()
            return
        }
        val capped = WhisperKeyboardService.capsFn?.invoke(text) ?: text
        // 1) direct input connection (whisper keyboard active)
        val ic = WhisperKeyboardService.activeIC
        if (ic != null && pm?.isInteractive != false) {
            ic.commitText("$capped ", 1)
            AppLog.i(TAG, "typed via IME: ${capped.take(50)}")
            toastIt("✓ ${capped.take(40)}")
            finish()
            return
        }
        // 2) accessibility paste - works with ANY keyboard in use (not on lock screen)
        if (!locked && attempt >= 2 && WhisperAccessibilityService.paste("$capped ")) {
            AppLog.i(TAG, "pasted via a11y: ${capped.take(50)}")
            toastIt("✓ pasted ${capped.take(40)}")
            finish()
            return
        }
        // 3) keep retrying (screen may unlock; keyboard may bind shortly)
        handler.postDelayed({ tryRoute(text, attempt + 1) }, 500)
    }

    private fun toastIt(msg: String) {
        try {
            val c = WhisperApp.holder ?: return
            android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}

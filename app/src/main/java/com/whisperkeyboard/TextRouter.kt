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
        // blank/silence chunk: discard silently, but watch for "forgot to stop recording"
        if (AudioUtils.isNoSpeechText(text)) {
            val streak = blankStreak.incrementAndGet()
            AppLog.i(TAG, "blank chunk discarded (streak=$streak)")
            if (streak >= 3) {
                blankStreak.set(0)
                WhisperKeyboardService.stopHook?.invoke()
                toastIt("No speech in 3 chunks - recording auto-stopped. Tap mic to resume.")
                AppLog.w(TAG, "auto-stop: 3 consecutive blank chunks")
            }
            return
        }
        blankStreak.set(0)
        pendingTyping.incrementAndGet()
        ProcessingService.notifyActivity()
        tryRoute(text, 0)
    }

    private val blankStreak = AtomicInteger(0)
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
                toastIt("Held (${OutstandingStore.count(ctx)}) + copied to clipboard - refocus a field to type")
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
            toastIt("Typed: ${capped.take(40)}")
            finish()
            return
        }
        // 2) accessibility paste - works with ANY keyboard in use (not on lock screen)
        if (!locked && attempt >= 2) {
            val ok = WhisperAccessibilityService.paste("$capped ")
            if (ok) {
                AppLog.i(TAG, "pasted via a11y: ${capped.take(50)}")
                toastIt("Typed: ${capped.take(40)}")
                finish()
                return
            }
            // one-time guidance when the bridge is off and another keyboard owns the field
            if (attempt == 8 && !WhisperAccessibilityService.isReady()) {
                toastIt("Enable 'Whisper Typing Bridge' in Accessibility to type here")
            }
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

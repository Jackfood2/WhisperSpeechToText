package com.whisperkeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

object TextRouter {

    private val handler = Handler(Looper.getMainLooper())
    private val pendingTyping = AtomicInteger(0)
    private const val MAX_ATTEMPTS = 60
    private const val TAG = "TextRouter"

    fun pendingTypingCount(): Int = pendingTyping.get()

    fun route(text: String) {
        if (text.isEmpty()) return

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
        val updated = pendingTyping.updateAndGet {
            if (it > 0) it - 1 else 0
        }

        if (updated == 0) {
            ProcessingService.notifyIdle()
        }
    }

    private fun tryRoute(text: String, attempt: Int) {
        val context = WhisperApp.holder

        val powerManager =
            context?.getSystemService(Context.POWER_SERVICE)
                as? android.os.PowerManager

        val locked = powerManager?.isInteractive == false

        if ((locked && attempt >= 4) || attempt >= MAX_ATTEMPTS) {
            if (context != null) {
                OutstandingStore.add(context, text)

                val clipOn = context
                    .getSharedPreferences(
                        "whisper",
                        Context.MODE_PRIVATE
                    )
                    .getBoolean("out_clipboard", true)

                toastIt(
                    if (clipOn) {
                        "Held (${OutstandingStore.count(context)}) " +
                            "and copied to clipboard"
                    } else {
                        "Held (${OutstandingStore.count(context)})"
                    }
                )
            }

            finish()
            return
        }

        val capped = runCatching {
            WhisperKeyboardService.capsFn?.invoke(text) ?: text
        }.getOrElse {
            AppLog.w(TAG, "Capitalization failed: ${it.message}")
            text
        }

        val inputConnection = WhisperKeyboardService.activeIC

        if (
            inputConnection != null &&
            powerManager?.isInteractive != false
        ) {
            val delivered = runCatching {
                inputConnection.commitText("$capped ", 1)
            }.getOrDefault(false)

            if (delivered) {
                AppLog.i(TAG, "Typed via IME: ${capped.take(50)}")
                toastIt("Delivered")
                finish()
                return
            }
        }

        if (!locked && attempt >= 2) {
            val pasted = runCatching {
                WhisperAccessibilityService.paste("$capped ")
            }.getOrDefault(false)

            if (pasted) {
                AppLog.i(TAG, "Pasted via accessibility service")
                toastIt("Delivered")
                finish()
                return
            }
        }

        handler.postDelayed(
            { tryRoute(text, attempt + 1) },
            500L
        )
    }

    private fun toastIt(msg: String) {
        try {
            val c = WhisperApp.holder ?: return
            android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}

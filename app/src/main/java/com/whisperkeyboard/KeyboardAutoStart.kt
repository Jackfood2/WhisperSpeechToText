package com.whisperkeyboard

import java.util.concurrent.atomic.AtomicBoolean

object KeyboardAutoStart {

    private val consumed = AtomicBoolean(false)

    fun requestFirstEntryAutoStart() {
        WhisperKeyboardService.autoStartRequested = true
    }

    fun consumeIfAllowed(): Boolean {
        if (consumed.compareAndSet(false, true)) {
            return true
        }
        return false
    }

    fun reset() {
        consumed.set(false)
        WhisperKeyboardService.autoStartRequested = false
    }

    fun isConsumed(): Boolean = consumed.get()
}

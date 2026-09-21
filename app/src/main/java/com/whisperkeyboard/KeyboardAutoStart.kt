package com.whisperkeyboard

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controls the one-time automatic recording when entering
 * the Speech to Text keyboard.
 *
 * The flag belongs to the currently loaded model session.
 * When the model is unloaded, reset() is called.
 */
object KeyboardAutoStart {

    private val consumed = AtomicBoolean(false)

    /**
     * Called when normal keyboard -> Speech keyboard transition occurs.
     */
    fun requestFirstEntryAutoStart() {
        WhisperKeyboardService.autoStartRequested = true
    }

    /**
     * Returns true only once for the current loaded-model session.
     */
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

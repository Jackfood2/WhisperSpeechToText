package com.whisperkeyboard

import java.util.concurrent.atomic.AtomicReference

enum class MicOwner {
    NONE,
    KEYBOARD,
    BUBBLE,
    MEETING,
    RECOGNITION_SERVICE
}

object MicSessionManager {

    private val owner =
        AtomicReference(MicOwner.NONE)

    fun tryAcquire(
        requested: MicOwner
    ): Boolean {
        return owner.compareAndSet(
            MicOwner.NONE,
            requested
        )
    }

    fun release(
        requested: MicOwner
    ) {
        owner.compareAndSet(
            requested,
            MicOwner.NONE
        )
    }

    fun current(): MicOwner =
        owner.get()
}

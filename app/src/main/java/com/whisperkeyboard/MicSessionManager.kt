package com.whisperkeyboard

import java.util.concurrent.atomic.AtomicLong
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

    private val ownedSince =
        AtomicLong(0L)

    // A recorder that dies without calling release() used to wedge the mic for
    // the rest of the process lifetime. Any ownership older than this is
    // considered abandoned.
    private const val STALE_OWNER_MS = 4L * 60L * 60L * 1000L

    fun tryAcquire(
        requested: MicOwner
    ): Boolean {
        reclaimIfStale()
        if (owner.compareAndSet(
                MicOwner.NONE,
                requested
            )
        ) {
            ownedSince.set(
                android.os.SystemClock.elapsedRealtime()
            )
            return true
        }
        return false
    }

    fun release(
        requested: MicOwner
    ) {
        if (owner.compareAndSet(
                requested,
                MicOwner.NONE
            )
        ) {
            ownedSince.set(0L)
        }
    }

    /** Unconditional release, for recovery paths (e.g. crash restart). */
    fun forceRelease() {
        owner.set(MicOwner.NONE)
        ownedSince.set(0L)
    }

    fun current(): MicOwner =
        owner.get()

    private fun reclaimIfStale() {
        val since = ownedSince.get()
        if (since <= 0L) return
        if (android.os.SystemClock.elapsedRealtime() - since > STALE_OWNER_MS) {
            val stale = owner.get()
            if (owner.compareAndSet(stale, MicOwner.NONE)) {
                ownedSince.set(0L)
                AppLog.w("MicSession", "reclaimed stale mic owner: $stale")
            }
        }
    }
}

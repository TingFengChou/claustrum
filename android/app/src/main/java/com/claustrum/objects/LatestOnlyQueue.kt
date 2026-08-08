package com.claustrum.objects

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class LatestOffer(
    val startWorker: Boolean,
    val replacedPending: Boolean,
)

/**
 * One current consumer plus one latest pending value. Producers never hand values
 * directly to workers, so a handoff race cannot put an older value over a newer one.
 */
internal class LatestOnlyQueue<T : Any>(private val onDiscard: (T) -> Unit) {
    private val workerActive = AtomicBoolean(false)
    private val pending = AtomicReference<T?>(null)

    fun offer(value: T): LatestOffer {
        val replaced = pending.getAndSet(value)
        if (replaced != null) onDiscard(replaced)
        return LatestOffer(
            startWorker = workerActive.compareAndSet(false, true),
            replacedPending = replaced != null,
        )
    }

    /** Called only by a worker. Null means this worker released ownership safely. */
    fun takeOrReleaseWorker(): T? {
        while (true) {
            pending.getAndSet(null)?.let { return it }
            workerActive.set(false)
            if (pending.get() == null || !workerActive.compareAndSet(false, true)) return null
            // A producer offered after our empty read but did not win ownership.
            // We reclaimed the worker flag and loop to drain that newest value.
        }
    }

    fun clear() {
        pending.getAndSet(null)?.let(onDiscard)
    }
}

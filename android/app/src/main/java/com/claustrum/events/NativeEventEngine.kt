package com.claustrum.events

import com.claustrum.core.NativeCore

/** Injectable JNI boundary so lifecycle and mapping stay host-testable. */
internal interface NativeEventBridge {
    fun create(sourceId: String): Long
    fun process(handle: Long, observation: FastPathObservation): Array<String>?
    fun destroy(handle: Long)
}

private object JniNativeEventBridge : NativeEventBridge {
    override fun create(sourceId: String): Long = NativeCore.createEventEngine(sourceId)

    override fun process(handle: Long, observation: FastPathObservation): Array<String>? =
        NativeCore.processEventObservation(
            handle = handle,
            atMs = observation.atMs,
            actant = observation.actant,
            secondaryActant = observation.secondaryActant ?: -1,
            pose = observation.pose.wireValue,
            rapidDescentScore = observation.rapidDescentScore,
            impactScore = observation.impactScore,
            motionScore = observation.motionScore,
            closeContactScore = observation.closeContactScore,
            strikeScore = observation.strikeScore,
            visiblePeople = observation.visiblePeople,
            zoneExit = observation.zoneExit,
        )

    override fun destroy(handle: Long) = NativeCore.destroyEventEngine(handle)
}

/**
 * Owns one Rust L2 engine session for a logical camera source.
 *
 * Calls are synchronized so lifecycle shutdown cannot race an observation. No pixels
 * enter this object; returned strings are individual `event.schema.json` documents.
 */
class NativeEventEngine internal constructor(
    sourceId: String,
    private val bridge: NativeEventBridge,
) : AutoCloseable {
    private var handle: Long = bridge.create(sourceId).also {
        check(it > 0) { "Rust L2 event engine initialization failed" }
    }

    constructor(sourceId: String) : this(sourceId, JniNativeEventBridge)

    @Synchronized
    fun process(observation: FastPathObservation): List<String> {
        if (handle == CLOSED_HANDLE) return emptyList()
        return checkNotNull(bridge.process(handle, observation)) {
            "Rust L2 event bridge rejected the observation"
        }.toList()
    }

    @Synchronized
    override fun close() {
        if (handle == CLOSED_HANDLE) return
        val closing = handle
        handle = CLOSED_HANDLE
        bridge.destroy(closing)
    }

    private companion object {
        const val CLOSED_HANDLE = 0L
    }
}

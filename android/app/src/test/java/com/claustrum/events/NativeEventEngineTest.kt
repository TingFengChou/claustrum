package com.claustrum.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NativeEventEngineTest {

    private class FakeBridge(
        private val createResult: Long = 7L,
        private val processResult: Array<String>? = arrayOf("{\"type\":\"fall\"}"),
    ) : NativeEventBridge {
        val sources = mutableListOf<String>()
        val calls = mutableListOf<Pair<Long, FastPathObservation>>()
        val destroyed = mutableListOf<Long>()

        override fun create(sourceId: String): Long {
            sources += sourceId
            return createResult
        }

        override fun process(handle: Long, observation: FastPathObservation): Array<String>? {
            calls += handle to observation
            return processResult
        }

        override fun destroy(handle: Long) {
            destroyed += handle
        }
    }

    @Test
    fun `maps an anonymous observation and returns individual event documents`() {
        val bridge = FakeBridge(processResult = arrayOf("event-1", "event-2"))
        val engine = NativeEventEngine("camera_back", bridge)
        val observation = FastPathObservation(
            atMs = 1234,
            actant = 2,
            secondaryActant = 5,
            pose = FastPathPose.PRONE,
            rapidDescentScore = 0.9f,
            visiblePeople = 2,
        )

        assertEquals(listOf("event-1", "event-2"), engine.process(observation))
        assertEquals(listOf("camera_back"), bridge.sources)
        assertEquals(listOf(7L to observation), bridge.calls)
    }

    @Test
    fun `close is idempotent and later observations are ignored`() {
        val bridge = FakeBridge()
        val engine = NativeEventEngine("camera_back", bridge)
        engine.close()
        engine.close()

        assertEquals(emptyList<String>(), engine.process(FastPathObservation(1, 1)))
        assertEquals(listOf(7L), bridge.destroyed)
        assertEquals(emptyList<Pair<Long, FastPathObservation>>(), bridge.calls)
    }

    @Test
    fun `native initialization failure is explicit`() {
        assertThrows(IllegalStateException::class.java) {
            NativeEventEngine("camera_back", FakeBridge(createResult = 0))
        }
    }

    @Test
    fun `native payload rejection is explicit`() {
        val engine = NativeEventEngine("camera_back", FakeBridge(processResult = null))
        assertThrows(IllegalStateException::class.java) {
            engine.process(FastPathObservation(1, 1))
        }
    }

    @Test
    fun `observation rejects values that cannot cross JNI losslessly`() {
        assertThrows(IllegalArgumentException::class.java) {
            FastPathObservation(atMs = -1, actant = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FastPathObservation(atMs = 1, actant = UShort.MAX_VALUE.toInt() + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FastPathObservation(atMs = 1, actant = 1, visiblePeople = 256)
        }
    }
}

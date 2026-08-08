package com.claustrum.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianSessionTest {

    @Test
    fun `permission failure returns to standby and permits retry`() {
        val session = GuardianSession()

        assertTrue(session.beginActivation())
        assertFalse(session.beginActivation())
        session.activationFailed("需要相機權限")

        val failed = session.snapshot()
        assertFalse(failed.active)
        assertFalse(failed.guarding)
        assertEquals("需要相機權限", failed.error)
        assertTrue(session.beginActivation())
        assertNull(session.snapshot().error)
    }

    @Test
    fun `bind failure is retryable`() {
        val session = GuardianSession()
        session.beginActivation()
        session.activationFailed("相機綁定失敗")

        assertTrue(session.beginActivation())
        assertTrue(session.snapshot().active)
    }

    @Test
    fun `guarding starts only after the first processed frame`() {
        val session = GuardianSession()
        session.beginActivation()
        session.cameraBound()

        assertTrue(session.snapshot().active)
        assertFalse(session.snapshot().guarding)

        session.frameProcessed()
        assertTrue(session.snapshot().guarding)
    }

    @Test
    fun `repeated analyzer failures degrade then a good frame recovers`() {
        val session = GuardianSession(analysisFailureThreshold = 2)
        session.beginActivation()
        session.cameraBound()
        session.frameProcessed()

        session.frameFailed("影格分析失敗")
        assertTrue(session.snapshot().guarding)
        session.frameFailed("影格分析失敗")

        val degraded = session.snapshot()
        assertTrue(degraded.active)
        assertFalse(degraded.guarding)
        assertEquals("影格分析失敗", degraded.error)

        session.frameProcessed()
        assertTrue(session.snapshot().guarding)
        assertNull(session.snapshot().error)
    }
}

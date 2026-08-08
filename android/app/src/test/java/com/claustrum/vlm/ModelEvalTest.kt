package com.claustrum.vlm

import com.claustrum.vlm.ModelEval.Case
import com.claustrum.vlm.ModelEval.CaseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host JVM tests for the model-eval scoring — no Android, no model. */
class ModelEvalTest {

    @Test fun scorePassesWhenCaptionContainsAnyKeyword() {
        val c = Case("fall", listOf("倒臥", "跌倒", "倒地"))
        assertTrue(ModelEval.score("畫面中可見一人倒臥在馬路上。", c))
        assertFalse(ModelEval.score("一位長者站在路邊。", c))
    }

    @Test fun scoreFailsWithNoKeywords() {
        assertFalse(ModelEval.score("任何描述", Case("x", emptyList())))
    }

    @Test fun caseFromFileNameParsesLabelAndKeywords() {
        val c = ModelEval.caseFromFileName("fall__倒臥,跌倒,倒地.jpg")
        assertEquals("fall", c.label)
        assertEquals(listOf("倒臥", "跌倒", "倒地"), c.anyOf)
    }

    @Test fun caseFromFileNameHandlesFullwidthCommaAndNoKeywords() {
        assertEquals(listOf("行走", "站立"), ModelEval.caseFromFileName("walk__行走，站立.png").anyOf)
        assertEquals(emptyList<String>(), ModelEval.caseFromFileName("plain.jpg").anyOf)
        assertEquals("plain", ModelEval.caseFromFileName("plain.jpg").label)
    }

    @Test fun summarizeComputesPassRateAndLatency() {
        val results = listOf(
            CaseResult("a", "倒臥", 6000, true),
            CaseResult("b", "站立", 4000, false),
            CaseResult("c", "倒地", 8000, true),
        )
        val s = ModelEval.summarize(results)
        assertEquals(3, s.total)
        assertEquals(2, s.passed)
        assertEquals(66, s.passRate.toInt())
        assertEquals(6000, s.avgLatencyMs)   // (6000+4000+8000)/3
        assertEquals(6000, s.p50LatencyMs)   // median of sorted [4000,6000,8000]
    }

    @Test fun summarizeIgnoresNegativeLatencies() {
        val s = ModelEval.summarize(listOf(CaseResult("a", "x", -1, false)))
        assertEquals(0, s.avgLatencyMs)
        assertEquals(1, s.total)
    }
}

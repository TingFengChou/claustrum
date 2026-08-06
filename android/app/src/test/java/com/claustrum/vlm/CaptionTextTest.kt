package com.claustrum.vlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host JVM tests for L1 caption post-processing — no Android, no model. */
class CaptionTextTest {

    @Test fun keepsFirstChineseSentence() {
        assertEquals("畫面中有一人倒臥在馬路上。",
            CaptionText.clean("畫面中有一人倒臥在馬路上。旁邊還有車輛經過,並且..."))
    }

    @Test fun stripsEmojiButKeepsChinese() {
        // Emoji interspersed in a Chinese sentence are removed; sentence kept.
        assertEquals("一位長者站在路邊。",
            CaptionText.clean("一位長者🧓站在路邊👀。"))
    }

    @Test fun pureEmojiOrSymbolsRejectedToEmpty() {
        assertEquals("", CaptionText.clean("👀👀👀🚗🚗"))
        assertEquals("", CaptionText.clean("..."))       // ellipsis, no Han
        assertEquals("", CaptionText.clean("★☆➤ ✦"))
    }

    @Test fun pureEnglishRejected() {
        assertEquals("", CaptionText.clean("A person lying on the road."))
    }

    @Test fun tinyHanFragmentRejected() {
        // The model sometimes emits a 1-2 char fragment on a frame it can't parse.
        assertEquals("", CaptionText.clean("程程"))
        assertEquals("", CaptionText.clean("車"))
        // A real (>= MIN_HAN) short sentence is kept.
        assertEquals("有人倒地。", CaptionText.clean("有人倒地。"))
    }

    @Test fun stripSymbolsRemovesPictographs() {
        assertEquals("車子", CaptionText.stripSymbols("車🚗子"))
        assertFalse(CaptionText.stripSymbols("🚗").isNotEmpty())
    }

    @Test fun hasHanDetectsChinese() {
        assertTrue(CaptionText.hasHan("有人"))
        assertFalse(CaptionText.hasHan("abc 123 !?"))
    }

    @Test fun reachedEndAtSentenceTerminatorPastSoftMin() {
        assertFalse(CaptionText.reachedEnd("短。", softMin = 16, max = 140))   // under softMin
        assertTrue(CaptionText.reachedEnd("這是一段夠長的描述句子內容。", softMin = 8, max = 140))
    }

    @Test fun reachedEndAtHardCap() {
        val long = "字".repeat(140)
        assertTrue(CaptionText.reachedEnd(long, softMin = 16, max = 140))
    }

    @Test fun reachedEndFalseWhileStreamingNoTerminator() {
        assertFalse(CaptionText.reachedEnd("一位長者站在路邊走著", softMin = 8, max = 140))
    }
}

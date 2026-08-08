package com.claustrum.events

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FallVideoEvalManifestTest {
    @Test
    fun parsesPositiveAndAnonymousNegativeCases() {
        val cases = FallVideoEvalManifest.parse(
            """
            {"version":1,"cases":[
              {"video":"fall.mp4","label":"fall-smoke","expected":"fall",
               "eventStartMs":1000,"eventEndMs":5000},
              {"video":"walk.webm","label":"walk-negative","expected":"none",
               "eventStartMs":null,"eventEndMs":null}
            ]}
            """.trimIndent(),
        )

        assertThat(cases).hasSize(2)
        assertThat(cases.first().expected).isEqualTo(FallVideoExpected.FALL)
        assertThat(cases.last().eventStartMs).isNull()
    }

    @Test
    fun rejectsIdentityUnknownOrMissingFields() {
        val identity = assertThrows(IllegalArgumentException::class.java) {
            FallVideoEvalManifest.parse(
                """{"version":1,"cases":[{"video":"fall.mp4","label":"fall",
                  "expected":"fall","eventStartMs":1,"eventEndMs":2,"person_id":"x"}]}""",
            )
        }
        assertThat(identity).hasMessageThat().contains("unknown fields")

        assertThrows(IllegalArgumentException::class.java) {
            FallVideoEvalManifest.parse(
                """{"version":1,"cases":[{"video":"fall.mp4","label":"fall",
                  "expected":"fall","eventStartMs":1}]}""",
            )
        }
    }

    @Test
    fun rejectsTraversalDuplicateAndInvalidWindows() {
        assertThrows(IllegalArgumentException::class.java) {
            FallVideoEvalManifest.parse(
                """{"version":1,"cases":[{"video":"../fall.mp4","label":"x",
                  "expected":"fall","eventStartMs":1,"eventEndMs":2}]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FallVideoEvalManifest.parse(
                """{"version":1,"cases":[
                  {"video":"fall.mp4","label":"x","expected":"fall","eventStartMs":1,"eventEndMs":2},
                  {"video":"fall.mp4","label":"y","expected":"none","eventStartMs":null,"eventEndMs":null}
                ]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FallVideoEvalManifest.parse(
                """{"version":1,"cases":[{"video":"fall.mp4","label":"x",
                  "expected":"fall","eventStartMs":5,"eventEndMs":5}]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FallVideoEvalManifest.parse(
                """{"version":1,"cases":[{"video":"walk.mp4","label":"x",
                  "expected":"none","eventStartMs":0,"eventEndMs":1}]}""",
            )
        }
    }
}

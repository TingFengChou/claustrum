package com.claustrum.objects

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ObjectEvalManifestTest {
    private val allowed = setOf("person", "bottle")

    @Test
    fun parsesNormalizedAnonymousAnnotationsAndHardNegative() {
        val cases = ObjectEvalManifest.parse(
            """
            {
              "version": 1,
              "cases": [
                {
                  "image": "day_person.jpg",
                  "label": "day-person",
                  "objects": [
                    {"category":"person","left":0.1,"top":0.2,"right":0.4,"bottom":0.9}
                  ]
                },
                {"image":"rain_empty.png","label":"rain-hard-negative","objects":[]}
              ]
            }
            """.trimIndent(),
            allowed,
        )

        assertThat(cases).hasSize(2)
        assertThat(cases.first().objects.single().category).isEqualTo("person")
        assertThat(cases.last().objects).isEmpty()
    }

    @Test
    fun rejectsIdentityOrUnknownFields() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ObjectEvalManifest.parse(
                """
                {"version":1,"cases":[{
                  "image":"person.jpg","label":"person","objects":[{
                    "category":"person","left":0.1,"top":0.1,"right":0.2,"bottom":0.5,
                    "person_id":"someone"
                  }]
                }]}
                """.trimIndent(),
                allowed,
            )
        }

        assertThat(error).hasMessageThat().contains("unknown fields")
    }

    @Test
    fun rejectsTraversalDuplicateAndCategoryOutsideAllowlist() {
        assertThrows(IllegalArgumentException::class.java) {
            ObjectEvalManifest.parse(
                """{"version":1,"cases":[{"image":"../x.jpg","label":"x","objects":[]}]}""",
                allowed,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ObjectEvalManifest.parse(
                """{"version":1,"cases":[
                  {"image":"x.jpg","label":"x","objects":[]},
                  {"image":"x.jpg","label":"y","objects":[]}
                ]}""",
                allowed,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ObjectEvalManifest.parse(
                """{"version":1,"cases":[{"image":"x.jpg","label":"x","objects":[
                  {"category":"car","left":0.1,"top":0.1,"right":0.2,"bottom":0.2}
                ]}]}""",
                allowed,
            )
        }
    }
}

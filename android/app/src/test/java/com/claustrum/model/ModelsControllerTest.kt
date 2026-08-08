package com.claustrum.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ModelsControllerTest {
    @Test
    fun smallModelsUseMegabytes() {
        assertEquals("2.3 / 4.6 MB", downloadSize(2_300_000L, 4_600_000L))
    }

    @Test
    fun largeModelsUseGigabytes() {
        assertEquals("1.2 / 3.7 GB", downloadSize(1_200_000_000L, 3_700_000_000L))
    }

    @Test
    fun progressPercentIsLiteralAndDoesNotEnterStringFormatter() {
        assertEquals(
            "下載中 50% · 2.3 / 4.6 MB · 1.2 MB/s",
            downloadProgressLine(50L, 2_300_000L, 4_600_000L, 1_200_000f),
        )
    }

    @Test
    fun technicalDownloadTelemetryDoesNotDependOnDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("2.3 / 4.6 MB", downloadSize(2_300_000L, 4_600_000L))
            assertEquals(
                "下載中 50% · 2.3 / 4.6 MB · 1.2 MB/s",
                downloadProgressLine(50L, 2_300_000L, 4_600_000L, 1_200_000f),
            )
        } finally {
            Locale.setDefault(original)
        }
    }
}

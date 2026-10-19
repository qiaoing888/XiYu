package com.keyscafe.unlock

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun comparesNumericSegments() {
        assertEquals(1, compareVersions("2.2", "2.1"))
        assertEquals(-1, compareVersions("v2.1", "2.2"))
        assertEquals(0, compareVersions("v2.1", "2.1.0"))
        assertEquals(1, compareVersions("2.10", "2.9"))
        assertEquals(0, compareVersions("V2.1", "2.1"))
    }

    @Test
    fun handlesMissingSegments() {
        assertEquals(1, compareVersions("2.1.1", "2.1"))
        assertEquals(-1, compareVersions("2", "2.0.1"))
    }
}

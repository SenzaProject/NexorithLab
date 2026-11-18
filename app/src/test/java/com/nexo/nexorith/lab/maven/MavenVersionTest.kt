package com.nexo.nexorith.lab.maven

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenVersionTest {
    @Test
    fun comparesNumbers() {
        assertTrue(MavenVersion.compare("1.10", "1.9") > 0)
        assertEquals(0, MavenVersion.compare("1.0", "1.0.0"))
        assertTrue(MavenVersion.compare("2.0.0-beta01", "1.9.0") > 0)
    }

    @Test
    fun ordersQualifiers() {
        assertTrue(MavenVersion.compare("1.0-rc1", "1.0") < 0)
        assertTrue(MavenVersion.compare("1.0-alpha1", "1.0-beta1") < 0)
        assertTrue(MavenVersion.compare("1.0-sp1", "1.0") > 0)
        assertTrue(MavenVersion.compare("1.0-1", "1.0") > 0)
    }
}

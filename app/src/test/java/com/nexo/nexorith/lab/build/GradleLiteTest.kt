package com.nexo.nexorith.lab.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleLiteTest {

    @Test
    fun parsesBasicConfig() {
        val text = """
            android {
                applicationId "com.example.app" // komentar
                minSdk 24
                versionCode = 3
                versionName "1.2"
            }
            dependencies {
                implementation 'androidx.appcompat:appcompat:1.6.1'
                api("com.google.android.material:material:1.9.0")
                // implementation 'x:y:1'
            }
        """.trimIndent()
        val c = GradleLite.parse(text)
        assertEquals("com.example.app", c.applicationId)
        assertEquals(24, c.minSdk)
        assertEquals(3, c.versionCode)
        assertEquals("1.2", c.versionName)
        assertEquals(2, c.dependencies.size)
        assertEquals("androidx.appcompat:appcompat:1.6.1", c.dependencies[0].coordinate)
        assertEquals("api", c.dependencies[1].configuration)
        assertTrue(GradleLite.validate(c).isEmpty())
    }

    @Test
    fun rejectsInvalidApplicationId() {
        val c = GradleLite.parse("applicationId \"bad\"\nminSdk 24")
        assertFalse(GradleLite.validate(c).isEmpty())
    }

    @Test
    fun reportsMissingValues() {
        val errors = GradleLite.validate(GradleLite.parse(""))
        assertEquals(2, errors.size)
    }
}

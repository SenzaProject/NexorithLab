package com.nexo.nexorith.lab.build

import com.nexo.nexorith.lab.project.AssetImporter
import com.nexo.nexorith.lab.project.AssetImporter.Category
import com.nexo.nexorith.lab.project.IconGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectToolsTest {

    @Test
    fun sanitizesResourceNames() {
        assertEquals("my_photo_1", AssetImporter.sanitizeBase("My Photo-1"))
        assertEquals("f_123abc", AssetImporter.sanitizeBase("123abc"))
        assertEquals("file", AssetImporter.sanitizeBase("---"))
    }

    @Test
    fun buildsUniqueTargetNames() {
        assertEquals("foto_kucing.png", AssetImporter.targetFileName(Category.IMAGE, "Foto Kucing.PNG") { false })
        val taken = setOf("foto_kucing.png")
        assertEquals("foto_kucing_2.png", AssetImporter.targetFileName(Category.IMAGE, "Foto Kucing.png") { it in taken })
    }

    @Test
    fun rejectsWrongExtension() {
        try {
            AssetImporter.targetFileName(Category.FONT, "lagu.mp3") { false }
            fail("seharusnya menolak ekstensi")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mp3"))
        }
    }

    @Test
    fun keepsRawAssetName() {
        assertEquals("Data File.json", AssetImporter.targetFileName(Category.ASSET, "Data File.json") { false })
    }

    @Test
    fun iconSizes() {
        assertEquals(108, IconGenerator.foregroundSize(48))
        assertEquals(432, IconGenerator.foregroundSize(192))
        val box = IconGenerator.fit(100, 50, 48f)
        assertEquals(48f, box.width, 0.001f)
        assertEquals(24f, box.height, 0.001f)
        assertEquals(12f, box.top, 0.001f)
    }

    @Test
    fun validatesColor() {
        assertTrue(IconGenerator.isValidColor("#06060A"))
        assertFalse(IconGenerator.isValidColor("06060A"))
        assertFalse(IconGenerator.isValidColor("#FFF"))
    }
}

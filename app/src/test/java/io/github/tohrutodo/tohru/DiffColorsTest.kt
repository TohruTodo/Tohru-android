package io.github.tohrutodo.tohru

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.tohrutodo.tohru.ui.DiffColors
import io.github.tohrutodo.tohru.ui.diffColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffColorsTest {
    private val darkSurfaces = listOf(Color(0xFF14161F), Color.Black, Color(0xFF1C1B1F))
    private val lightSurfaces = listOf(Color(0xFFFBF8F0), Color(0xFFFFFBFE), Color.White)
    private val onSurface = Color(0xFFECE9DF)
    private val onSurfaceVariant = Color(0xFFB9BCCB)
    private val accent = Color(0xFFE5D985)

    private fun contrast(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun palette(surface: Color): DiffColors =
        diffColors(surface, onSurface, onSurfaceVariant, accent)

    @Test fun diffTextIsReadableOnBandsAndSurfaces() {
        (darkSurfaces + lightSurfaces).forEach { surface ->
            val colors = palette(surface)
            assertTrue("added on band over $surface", contrast(colors.added, colors.addedBand) >= 4.5f)
            assertTrue("removed on band over $surface", contrast(colors.removed, colors.removedBand) >= 4.5f)
            assertTrue("added on $surface", contrast(colors.added, surface) >= 4.5f)
            assertTrue("removed on $surface", contrast(colors.removed, surface) >= 4.5f)
        }
    }

    @Test fun bandsAreVisibleAgainstSurface() {
        (darkSurfaces + lightSurfaces).forEach { surface ->
            val colors = palette(surface)
            assertTrue("added band over $surface", contrast(colors.addedBand, surface) >= 1.1f)
            assertTrue("removed band over $surface", contrast(colors.removedBand, surface) >= 1.1f)
        }
    }

    @Test fun addedAndRemovedColorsDiffer() {
        (darkSurfaces + lightSurfaces).forEach { surface ->
            val colors = palette(surface)
            assertNotEquals(colors.added, colors.removed)
            assertNotEquals(colors.addedBand, colors.removedBand)
        }
    }

    @Test fun paletteFollowsSurfaceLuminance() {
        val dark = palette(Color(0xFF14161F))
        val light = palette(Color(0xFFFBF8F0))
        assertTrue(dark.added.luminance() > dark.addedBand.luminance())
        assertTrue(light.added.luminance() < light.addedBand.luminance())
        assertTrue(dark.added.luminance() > light.added.luminance())
    }

    @Test fun themeRolesAreKept() {
        val colors = palette(Color(0xFF14161F))
        assertEquals(accent, colors.hunk)
        assertEquals(onSurfaceVariant, colors.header)
        assertEquals(onSurface, colors.context)
    }
}

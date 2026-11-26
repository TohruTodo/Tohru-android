package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tohrutodo.tohru.ui.ChipSquash

class ChipSquashTest {
    @Test fun openRowIsFullyVisible() {
        assertEquals(1f, ChipSquash.fraction(firstVisibleIndex = 0, scrollOffset = 0, size = 48), 0f)
    }

    @Test fun partialScrollSquashesProportionally() {
        assertEquals(0.5f, ChipSquash.fraction(firstVisibleIndex = 0, scrollOffset = 24, size = 48), 0f)
        assertEquals(0.25f, ChipSquash.fraction(firstVisibleIndex = 0, scrollOffset = 36, size = 48), 0f)
    }

    @Test fun fullyScrolledRowIsHidden() {
        assertEquals(0f, ChipSquash.fraction(firstVisibleIndex = 0, scrollOffset = 48, size = 48), 0f)
        assertEquals(0f, ChipSquash.fraction(firstVisibleIndex = 1, scrollOffset = 0, size = 48), 0f)
    }

    @Test fun unmeasuredRowStaysVisible() {
        assertEquals(1f, ChipSquash.fraction(firstVisibleIndex = 0, scrollOffset = 0, size = 0), 0f)
    }

    @Test fun overscrollDoesNotExceedBounds() {
        assertEquals(1f, ChipSquash.fraction(firstVisibleIndex = 0, scrollOffset = -12, size = 48), 0f)
    }
}

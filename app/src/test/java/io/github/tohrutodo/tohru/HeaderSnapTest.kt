package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.tohrutodo.tohru.ui.HeaderSnap

class HeaderSnapTest {
    private val size = 100
    private val padding = 4

    @Test fun fullyVisibleHeaderStaysPut() {
        assertNull(HeaderSnap.snapDelta(offset = 4, size = size, beforePadding = padding))
        assertNull(HeaderSnap.snapDelta(offset = 40, size = size, beforePadding = padding))
    }

    @Test fun mostlyVisibleHeaderScrollsBack() {
        // 60 px of 100 visible.
        assertEquals(-40f, HeaderSnap.snapDelta(-36, size, padding)!!, 0.001f)
        // Exactly half still counts as visible.
        assertEquals(-50f, HeaderSnap.snapDelta(-46, size, padding)!!, 0.001f)
    }

    @Test fun mostlyHiddenHeaderScrollsAway() {
        // 40 px of 100 visible.
        assertEquals(44f, HeaderSnap.snapDelta(-56, size, padding)!!, 0.001f)
        // One pixel of the header left.
        assertEquals(5f, HeaderSnap.snapDelta(-95, size, padding)!!, 0.001f)
    }

    @Test fun hiddenOrEmptyHeaderIsIgnored() {
        assertNull(HeaderSnap.snapDelta(offset = -100, size = size, beforePadding = padding))
        assertNull(HeaderSnap.snapDelta(offset = -300, size = size, beforePadding = padding))
        assertNull(HeaderSnap.snapDelta(offset = -30, size = 0, beforePadding = padding))
    }
}

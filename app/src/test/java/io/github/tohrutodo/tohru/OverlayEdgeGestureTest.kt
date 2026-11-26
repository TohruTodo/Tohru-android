package io.github.tohrutodo.tohru

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.overlay.EdgeGesture
import io.github.tohrutodo.tohru.overlay.consumeEdge
import io.github.tohrutodo.tohru.overlay.edgeResult
import io.github.tohrutodo.tohru.overlay.edgeStart

class OverlayEdgeGestureTest {
    private val width = 1080f
    private val edge = 24f
    private val threshold = 64f

    @Test fun leftEdgeSwipeIsBack() {
        val start = edgeStart(Offset(4f, 1200f), width, edge)
        assertEquals(EdgeGesture.Back, edgeResult(start, dx = 100f, dy = 4f, threshold = threshold))
    }

    @Test fun rightEdgeSwipeIsBack() {
        val start = edgeStart(Offset(1070f, 1200f), width, edge)
        assertEquals(EdgeGesture.Back, edgeResult(start, dx = -100f, dy = 4f, threshold = threshold))
    }

    @Test fun shortDragDoesNothing() {
        val start = edgeStart(Offset(4f, 1200f), width, edge)
        assertEquals(EdgeGesture.None, edgeResult(start, dx = 20f, dy = 2f, threshold = threshold))
    }

    @Test fun verticalDragAtSideEdgeIsNotBack() {
        val start = edgeStart(Offset(4f, 1200f), width, edge)
        assertEquals(EdgeGesture.None, edgeResult(start, dx = 20f, dy = -200f, threshold = threshold))
    }

    @Test fun middleDragIsNotAnEdgeGesture() {
        val start = edgeStart(Offset(500f, 1200f), width, edge)
        assertFalse(start.horizontal)
        assertEquals(EdgeGesture.None, edgeResult(start, dx = 200f, dy = 2f, threshold = threshold))
    }

    @Test fun dragBeyondThresholdIsConsumed() {
        val start = edgeStart(Offset(4f, 1200f), width, edge)
        assertTrue(consumeEdge(start, dx = 80f, dy = 2f, threshold = threshold))
        assertFalse(consumeEdge(start, dx = 20f, dy = 2f, threshold = threshold))
    }
}

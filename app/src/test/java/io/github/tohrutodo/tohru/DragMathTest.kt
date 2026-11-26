package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tohrutodo.tohru.task.DragMath

class DragMathTest {
    @Test fun staysUntilHalfTheNeighbour() {
        assertEquals(0, DragMath.direction(40f, 120))
        assertEquals(1, DragMath.direction(61f, 120))
        assertEquals(-1, DragMath.direction(-61f, 120))
    }

    @Test fun residualKeepsTheFingerPosition() {
        assertEquals(10f, DragMath.residual(130f, 120, 1), 0.001f)
        assertEquals(-10f, DragMath.residual(-130f, 120, -1), 0.001f)
    }

    @Test fun sameRowSizeKeepsOffsetContinuous() {
        val offset = 175f
        val afterOneSwap = DragMath.residual(offset, 120, 1)
        assertEquals(55f, afterOneSwap, 0.001f)
        assertEquals(0, DragMath.direction(afterOneSwap, 120))
        // A long drag crosses several rows without losing the touch position.
        assertEquals(180f, DragMath.residual(300f, 120, 1), 0.001f)
        assertEquals(1, DragMath.direction(180f, 120))
    }

    @Test fun rowTranslationFollowsTheFingerAcrossLayoutMoves() {
        // No layout movement: the row sits at the finger travel.
        assertEquals(120f, DragMath.rowTranslation(200f, 120f, 200f), 0.001f)
        // The list scrolled up by 300: the row needs 300 more translation.
        assertEquals(420f, DragMath.rowTranslation(200f, 120f, -100f), 0.001f)
        // Recomputing with the new layout gives the same visual top.
        assertEquals(
            320f,
            DragMath.rowTranslation(200f, 120f, -100f) + (-100f),
            0.001f,
        )
    }

    @Test fun dropIndexCountsVisibleCentersAboveTheDraggedRow() {
        // Level [A, B, C]; A is dragged down over B.
        assertEquals(1, DragMath.dropIndex(listOf(null, 150f, 250f), 0, 230f))
        // Dragged past both neighbours.
        assertEquals(2, DragMath.dropIndex(listOf(null, 150f, 250f), 0, 400f))
        // C is dragged up above B.
        assertEquals(1, DragMath.dropIndex(listOf(50f, 150f, null), 2, 100f))
        // No movement while the center stays inside its own slot.
        assertEquals(0, DragMath.dropIndex(listOf(null, 150f, 250f), 0, 40f))
    }

    @Test fun dropIndexTreatsOffscreenRowsByTheMeasuredEdge() {
        // B is off screen above, C is visible below.
        assertEquals(1, DragMath.dropIndex(listOf(null, null, 250f), 0, 100f))
        // B is off screen below, A is visible above.
        assertEquals(1, DragMath.dropIndex(listOf(50f, null, null), 2, 120f))
        // Nothing measured: keep the current slot.
        assertEquals(2, DragMath.dropIndex(listOf(null, null, null), 2, 120f))
    }

    @Test fun edgeRowsStayInsideTheirLevel() {
        // The first row dragged well above the top.
        assertEquals(0, DragMath.dropIndex(listOf(null, 150f, 250f), 0, -400f))
        // The last row dragged well below the end.
        assertEquals(2, DragMath.dropIndex(listOf(50f, 150f, null), 2, 900f))
    }

    @Test fun singleRowLevelNeverMoves() {
        assertEquals(0, DragMath.dropIndex(listOf(null), 0, 800f))
        assertEquals(0, DragMath.dropIndex(listOf(null), 0, -800f))
    }

    @Test fun allSiblingsOffscreenKeepTheSlot() {
        assertEquals(1, DragMath.dropIndex(listOf(null, null, null), 1, 120f))
        assertEquals(1, DragMath.dropIndex(listOf(null, null, null), 1, -120f))
    }

    @Test fun clampBlockKeepsTheDoneBoundary() {
        // Open row in a block 0..3 whose done part starts at 3.
        assertEquals(2, DragMath.clampBlock(3, blockStart = 0, blockEnd = 3, doneStart = 3, draggedDone = false))
        assertEquals(2, DragMath.clampBlock(9, blockStart = 0, blockEnd = 3, doneStart = 3, draggedDone = false))
        assertEquals(0, DragMath.clampBlock(-2, blockStart = 0, blockEnd = 3, doneStart = 3, draggedDone = false))
        // Done row cannot move above the done block.
        assertEquals(3, DragMath.clampBlock(1, blockStart = 0, blockEnd = 3, doneStart = 3, draggedDone = true))
        assertEquals(3, DragMath.clampBlock(3, blockStart = 0, blockEnd = 3, doneStart = 3, draggedDone = true))
        // No done rows: the whole block is open.
        assertEquals(3, DragMath.clampBlock(3, blockStart = 0, blockEnd = 3, doneStart = 4, draggedDone = false))
    }

    @Test fun clampBlockStaysInsideItsOwnBlock() {
        // Topic block 4..6 with no done rows.
        assertEquals(4, DragMath.clampBlock(0, blockStart = 4, blockEnd = 6, doneStart = 7, draggedDone = false))
        assertEquals(6, DragMath.clampBlock(9, blockStart = 4, blockEnd = 6, doneStart = 7, draggedDone = false))
        // Plain block 0..2; a drop at 5 clamps to the block end.
        assertEquals(2, DragMath.clampBlock(5, blockStart = 0, blockEnd = 2, doneStart = 3, draggedDone = false))
    }
}

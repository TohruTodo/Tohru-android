package io.github.tohrutodo.tohru.task

// Keeps a dragged row under the finger: it swaps when the accumulated offset
// passes half of the neighbour's height and keeps the remainder, so the row
// never jumps back to the touch point.
object DragMath {
    fun direction(offset: Float, neighborSize: Int): Int {
        val threshold = neighborSize / 2f
        return when {
            offset > threshold -> 1
            offset < -threshold -> -1
            else -> 0
        }
    }

    fun residual(offset: Float, neighborSize: Int, direction: Int): Float =
        offset - direction * neighborSize

    // Drop position for a dragged sibling, taken from the eye: the number of
    // siblings whose measured center sits above the dragged row's center.
    //
    // A null center means the row is outside the viewport; it counts as above
    // only when it precedes the first measured sibling, so a long drag past the
    // screen edge keeps extending the range instead of collapsing it.
    fun dropIndex(centers: List<Float?>, draggedIndex: Int, draggedCenter: Float): Int {
        val firstMeasured = centers.indices.firstOrNull { it != draggedIndex && centers[it] != null }
            ?: return draggedIndex.coerceAtLeast(0)
        var above = 0
        for (index in centers.indices) {
            if (index == draggedIndex) continue
            val center = centers[index]
            val isAbove = if (center != null) center < draggedCenter else index < firstMeasured
            if (isAbove) above++
        }
        return above
    }

    // Translation that keeps a dragged row under the finger: its starting slot
    // plus the finger travel, minus where the layout has moved it since. The
    // formula is absolute, so recomputing after a scroll or a collapsing
    // neighbour can never double-count.
    fun rowTranslation(startTop: Float, fingerDelta: Float, currentTop: Float): Float =
        startTop + fingerDelta - currentTop

    // Done rows keep the bottom block. A drag can reorder inside its own block
    // (plain tasks or topics) but never crosses into the other one.
    fun clampBlock(
        dropIndex: Int,
        blockStart: Int,
        blockEnd: Int,
        doneStart: Int,
        draggedDone: Boolean,
    ): Int {
        if (blockEnd < blockStart) return blockStart
        val low = if (draggedDone) doneStart else blockStart
        val high = if (draggedDone) blockEnd else doneStart - 1
        return dropIndex.coerceIn(low, maxOf(low, high))
    }
}

package io.github.tohrutodo.tohru.ui

// Squash progress for the detail chip row, which is always the first item.
// Using the scroll offset instead of the item offset keeps the row fully
// visible on open even when a short document is arranged without content
// padding.
object ChipSquash {
    fun fraction(firstVisibleIndex: Int, scrollOffset: Int, size: Int): Float {
        if (size <= 0) return 1f
        if (firstVisibleIndex > 0) return 0f
        return ((size - scrollOffset).toFloat() / size).coerceIn(0f, 1f)
    }
}

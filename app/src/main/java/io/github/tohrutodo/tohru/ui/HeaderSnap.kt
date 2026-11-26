package io.github.tohrutodo.tohru.ui

// Key of the search header item shared by both boards.
internal const val SEARCH_HEADER_KEY = "search"

// A resting header is either fully visible or fully hidden: when a scroll
// leaves it partly cut, the list scrolls the rest of the way. The 50% rule
// picks the nearer state.
object HeaderSnap {
    fun snapDelta(offset: Int, size: Int, beforePadding: Int): Float? {
        if (size <= 0) return null
        if (offset >= beforePadding) return null
        val visible = size + offset - beforePadding
        if (visible <= 0) return null
        val delta = if (visible * 2 >= size) offset - beforePadding else size + offset
        return if (delta == 0) null else delta.toFloat()
    }
}

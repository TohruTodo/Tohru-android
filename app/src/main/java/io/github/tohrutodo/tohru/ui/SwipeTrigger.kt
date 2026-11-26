package io.github.tohrutodo.tohru.ui

// Decides whether a released swipe runs the card to the end and opens the
// action. The release is projected forward by its velocity, so a fast flick
// needs a shorter drag; the visible slide always runs to the row's end.
object SwipeTrigger {
    const val PROJECTION_SECONDS = 0.2f

    fun triggers(
        travel: Float,
        velocity: Float,
        width: Float,
        projectionSeconds: Float = PROJECTION_SECONDS,
    ): Boolean {
        if (width <= 0f) return false
        return travel + velocity * projectionSeconds >= width
    }
}

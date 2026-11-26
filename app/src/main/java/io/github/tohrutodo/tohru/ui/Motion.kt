package io.github.tohrutodo.tohru.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

// Short, responsive motion. Long ease-ins make the board feel sluggish.
object Motion {
    const val FAST = 110
    const val NORMAL = 170

    fun enter() = tween<androidx.compose.ui.unit.IntSize>(durationMillis = NORMAL)
    fun exit() = tween<androidx.compose.ui.unit.IntSize>(durationMillis = FAST)
    fun fadeIn() = tween<Float>(durationMillis = FAST, easing = FastOutSlowInEasing)
    fun fadeOut() = tween<Float>(durationMillis = FAST)
}

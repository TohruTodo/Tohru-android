package io.github.tohrutodo.tohru.overlay

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal enum class EdgeGesture { None, Back }

internal data class EdgeStart(val horizontal: Boolean)

internal fun edgeStart(position: Offset, width: Float, edge: Float): EdgeStart =
    EdgeStart(horizontal = position.x <= edge || position.x >= width - edge)

internal fun edgeResult(start: EdgeStart, dx: Float, dy: Float, threshold: Float): EdgeGesture =
    if (start.horizontal && abs(dx) >= threshold && abs(dx) > abs(dy)) {
        EdgeGesture.Back
    } else {
        EdgeGesture.None
    }

internal fun consumeEdge(start: EdgeStart, dx: Float, dy: Float, threshold: Float): Boolean =
    start.horizontal && abs(dx) > abs(dy) && abs(dx) > threshold

// Side swipes the system would otherwise apply to the app behind the overlay.
// The strip stays narrow, and the Initial pass lets back win before a list row
// starts its own horizontal swipe; vertical drags are left to the scroll.
@Composable
fun Modifier.overlayEdgeGestures(onBack: () -> Unit): Modifier {
    val density = LocalDensity.current
    val edge = with(density) { EdgeWidth.toPx() }
    val threshold = with(density) { Threshold.toPx() }
    return pointerInput(onBack, edge, threshold) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            val start = edgeStart(
                position = down.position,
                width = size.width.toFloat(),
                edge = edge,
            )
            if (!start.horizontal) return@awaitEachGesture

            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                dx += change.position.x - change.previousPosition.x
                dy += change.position.y - change.previousPosition.y
                if (consumeEdge(start, dx, dy, threshold)) change.consume()
                if (!change.pressed) break
            }
            if (edgeResult(start, dx, dy, threshold) == EdgeGesture.Back) onBack()
        }
    }
}

private val EdgeWidth = 24.dp
private val Threshold = 64.dp

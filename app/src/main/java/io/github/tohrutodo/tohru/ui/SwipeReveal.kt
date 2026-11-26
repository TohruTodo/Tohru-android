package io.github.tohrutodo.tohru.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Which edge the row slides toward to reveal its actions.
enum class SwipeReveal { Start, End }

private val RevealWidth = 48.dp

private const val OverscrollResistance = 0.4f

// Swipe a row sideways to reveal its action behind it. The parent owns which
// row is open so only one stays revealed at a time.
@Composable
fun SwipeRevealRow(
    reveal: SwipeReveal,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    revealColor: Color,
    onFullSwipe: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(18.dp),
    action: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { RevealWidth.toPx() }
    val direction = if (reveal == SwipeReveal.Start) 1f else -1f
    val scope = rememberCoroutineScope()
    val openNow by rememberUpdatedState(open)
    val changeOpen by rememberUpdatedState(onOpenChange)
    val fullSwipe by rememberUpdatedState(onFullSwipe)
    val hapticsNow by rememberUpdatedState(LocalHapticFeedback.current)

    var offset by remember { mutableFloatStateOf(0f) }
    var rowWidth by remember { mutableFloatStateOf(0f) }
    var glideJob by remember { mutableStateOf<Job?>(null) }
    // The action layer also fills the corners the card leaves behind, so the
    // layer and the action have to stay invisible while the row is closed.
    val covered = remember { derivedStateOf { offset > 0f } }

    fun glide(target: Float) {
        glideJob?.cancel()
        glideJob = scope.launch {
            Animatable(offset).animateTo(target, tween(Motion.FAST)) { offset = value }
        }
    }

    // The parent closes the row when another one opens, the list scrolls or
    // the board switches.
    LaunchedEffect(open, revealPx) {
        val target = if (open) revealPx else 0f
        if (offset != target) glide(target)
    }

    Box(modifier = modifier.clip(shape)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (covered.value) 1f else 0f }
                .then(
                    // Hidden actions stay out of the accessibility tree until
                    // the row is revealed.
                    if (covered.value) Modifier else Modifier.clearAndSetSemantics {},
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(revealColor),
            )
            Box(
                modifier = Modifier
                    .align(
                        if (reveal == SwipeReveal.Start) Alignment.CenterStart else Alignment.CenterEnd,
                    )
                    .fillMaxHeight()
                    .width(RevealWidth),
            ) {
                action()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowWidth = it.width.toFloat() }
                .graphicsLayer { translationX = direction * offset }
                .pointerInput(enabled, reveal, revealPx, rowWidth) {
                    if (!enabled) return@pointerInput
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val tracker = VelocityTracker()
                        val down = awaitFirstDown(requireUnconsumed = false)
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var start = 0f
                        var totalX = 0f
                        var totalY = 0f
                        var claimed = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (change.isConsumed || !change.pressed) break
                            totalX += change.positionChange().x
                            totalY += change.positionChange().y
                            if (!claimed) {
                                // Vertical intent belongs to the list, and a
                                // horizontal drag away from the reveal side
                                // belongs to the pager: leave both untouched.
                                if (abs(totalY) > slop && abs(totalY) > abs(totalX)) break
                                if (abs(totalX) <= slop || abs(totalX) <= abs(totalY)) continue
                                val towards = if (reveal == SwipeReveal.Start) totalX > 0f else totalX < 0f
                                // A revealed row also takes the reverse drag,
                                // so it can be pushed shut again.
                                if (!towards && !openNow) break
                                claimed = true
                                glideJob?.cancel()
                                start = offset
                            }
                            change.consume()
                            // Past the action width the card keeps following the
                            // finger with resistance, so a full pull stays visible.
                            val raw = start + direction * totalX
                            offset = if (raw <= revealPx) {
                                raw.coerceIn(0f, revealPx)
                            } else {
                                revealPx + (raw - revealPx) * OverscrollResistance
                            }
                        }
                        if (claimed) {
                            val travel = direction * totalX
                            val velocity = tracker.calculateVelocity().x * direction
                            if (SwipeTrigger.triggers(travel, velocity, rowWidth)) {
                                hapticsNow.performHapticFeedback(HapticFeedbackType.Confirm)
                                // Run the card to the end, faster with a faster
                                // release, then open the action.
                                val remaining = (rowWidth - offset).coerceAtLeast(0f)
                                val durationMs = (remaining / velocity.coerceAtLeast(400f) * 1000f)
                                    .coerceIn(90f, 240f)
                                    .toInt()
                                glideJob?.cancel()
                                glideJob = scope.launch {
                                    Animatable(offset).animateTo(
                                        targetValue = rowWidth,
                                        animationSpec = tween(durationMs),
                                    ) { offset = value }
                                    fullSwipe()
                                }
                            } else {
                                val shouldOpen = offset > revealPx / 2f
                                if (shouldOpen && !openNow) {
                                    hapticsNow.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                glide(if (shouldOpen) revealPx else 0f)
                                if (shouldOpen != openNow) changeOpen(shouldOpen)
                            }
                        }
                    }
                },
        ) {
            content()
        }
    }
}

@Composable
fun SwipeAction(
    @DrawableRes icon: Int,
    description: String,
    container: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(22.dp),
            tint = contentColor,
        )
    }
}

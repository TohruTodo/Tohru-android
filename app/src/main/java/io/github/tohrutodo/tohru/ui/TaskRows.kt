package io.github.tohrutodo.tohru.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.task.Task

@Composable
fun GroupRow(
    prefix: String,
    count: Int,
    collapsed: Boolean,
    state: ToggleableState,
    editMode: Boolean,
    showDragHandle: Boolean,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit = {},
    onToggleCollapse: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val angle by animateFloatAsState(
        targetValue = if (collapsed) 0f else 90f,
        animationSpec = androidx.compose.animation.core.tween(Motion.FAST),
        label = "chevron-group",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (editMode) onToggleSelect() else onToggleCollapse() },
                onLongClick = if (editMode) null else onLongPress ?: onToggleCollapse,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (editMode) {
            RoundCheckbox(state = state, onToggle = onToggleSelect)
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = if (collapsed) stringResource(R.string.app_expand) else stringResource(R.string.app_collapse),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(angle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = prefix,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (editMode && showDragHandle) dragHandle()
    }
}

@Composable
fun TopicRow(
    topic: String,
    count: Int,
    collapsed: Boolean,
    state: ToggleableState,
    editMode: Boolean,
    showDragHandle: Boolean,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit = {},
    onToggleCollapse: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val angle by animateFloatAsState(
        targetValue = if (collapsed) 0f else 90f,
        animationSpec = androidx.compose.animation.core.tween(Motion.FAST),
        label = "chevron-topic",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (editMode) onToggleSelect() else onToggleCollapse() },
                onLongClick = if (editMode) null else onLongPress ?: onToggleCollapse,
            )
            .padding(start = 22.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (editMode) {
            RoundCheckbox(state = state, onToggle = onToggleSelect)
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = if (collapsed) stringResource(R.string.app_expand) else stringResource(R.string.app_collapse),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(angle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = topic,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (editMode && showDragHandle) dragHandle()
    }
}

@Composable
fun UntaggedRow(
    count: Int,
    collapsed: Boolean,
    state: ToggleableState,
    editMode: Boolean,
    onToggleCollapse: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val angle by animateFloatAsState(
        targetValue = if (collapsed) 0f else 90f,
        animationSpec = androidx.compose.animation.core.tween(Motion.FAST),
        label = "chevron-untagged",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (editMode) onToggleSelect() else onToggleCollapse() },
                onLongClick = if (editMode) null else onLongPress ?: onToggleCollapse,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (editMode) {
            RoundCheckbox(state = state, onToggle = onToggleSelect)
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = if (collapsed) stringResource(R.string.app_expand) else stringResource(R.string.app_collapse),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(angle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.app_untagged),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DoneRow(
    count: Int,
    depth: Int,
    collapsed: Boolean,
    state: ToggleableState,
    editMode: Boolean,
    onToggleCollapse: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    val angle by animateFloatAsState(
        targetValue = if (collapsed) 0f else 90f,
        animationSpec = androidx.compose.animation.core.tween(Motion.FAST),
        label = "chevron-done",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (editMode) onToggleSelect() else onToggleCollapse() },
                onLongClick = if (editMode) null else onLongPress ?: onToggleCollapse,
            )
            .padding(start = (16 + depth * 6).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (editMode) {
            RoundCheckbox(state = state, onToggle = onToggleSelect)
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = if (collapsed) stringResource(R.string.app_expand) else stringResource(R.string.app_collapse),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(angle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.app_done_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TaskCardRow(
    task: Task,
    selected: Boolean,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    checkboxEnabled: Boolean = true,
    showDragHandle: Boolean = true,
    dragHandle: @Composable () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onToggleDone: () -> Unit,
) {
    val dim by animateFloatAsState(
        targetValue = if (task.done && !editMode) 0.5f else 1f,
        animationSpec = androidx.compose.animation.core.tween(Motion.FAST),
        label = "task-alpha",
    )
    val parsed = task.parsed
    val haptics = LocalHaptics.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected && editMode) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                .alpha(dim),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editMode) {
                RoundCheckbox(
                    state = if (selected) ToggleableState.On else ToggleableState.Off,
                    enabled = checkboxEnabled,
                    onToggle = onClick,
                )
            } else {
                Checkbox(
                    checked = task.done,
                    enabled = checkboxEnabled,
                    onCheckedChange = {
                        if (task.done) {
                            haptics?.performHapticFeedback(HapticFeedbackType.ToggleOff)
                        } else {
                            haptics?.celebrate()
                        }
                        onToggleDone()
                    },
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = parsed.body.ifEmpty { task.title },
                    style = MaterialTheme.typography.titleMedium,
                )
                AnimatedVisibility(
                    visible = editMode && selected,
                    enter = androidx.compose.animation.fadeIn(Motion.fadeIn()) +
                        androidx.compose.animation.expandVertically(Motion.enter()),
                    exit = androidx.compose.animation.fadeOut(Motion.fadeOut()) +
                        androidx.compose.animation.shrinkVertically(Motion.exit()),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TaskBadges(task)
                        Text(
                            text = activityLabel(task, LocalNowMillis.current, LocalResources.current),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (task.body.isNotBlank()) {
                Icon(
                    painter = painterResource(R.drawable.ic_body),
                    contentDescription = stringResource(R.string.ui_has_body),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editMode && showDragHandle) dragHandle()
        }
    }
}

@Composable
internal fun DragHandle(modifier: Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_drag),
        contentDescription = stringResource(R.string.app_drag_desc),
        modifier = modifier
            .size(48.dp)
            .padding(12.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

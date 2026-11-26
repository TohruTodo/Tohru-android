package io.github.tohrutodo.tohru.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskSelection
import io.github.tohrutodo.tohru.task.TaskSorting

// Alphabetical board: open items first, then done ones, no manual order.
// Edit mode selects cards here the same way the tree selects rows.
@Composable
fun ManagePage(
    tasks: List<Task>,
    notePrefixes: Set<String>,
    gridState: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState,
    header: @Composable (collapse: () -> Float) -> Unit,
    gitPanel: @Composable () -> Unit,
    readmeNodes: List<ReadmeNode>,
    readmeQuery: String,
    onOpenReadme: (List<String>) -> Unit,
    emptyTitle: String,
    editMode: Boolean,
    selection: Set<String>,
    openRowKey: String?,
    onOpenRow: (String?) -> Unit,
    onToggleNode: (String) -> Unit,
    onEnterEdit: (String) -> Unit,
    onEditTask: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = TaskSorting.alphabetical(tasks)
    val noteTasks = ordered.filter { it.parsed.prefix in notePrefixes }
    val bodyTasks = ordered.filterNot { it.parsed.prefix in notePrefixes }

    @Composable
    fun TaskItem(task: Task) {
        val rowKey = TaskSelection.nodeKeyForTask(task)
        val openDetail = {
            onOpenRow(null)
            onOpenDetail(task.id)
        }
        SwipeRevealRow(
            reveal = SwipeReveal.Start,
            open = openRowKey == rowKey,
            onOpenChange = { onOpenRow(if (it) rowKey else null) },
            revealColor = MaterialTheme.colorScheme.secondaryContainer,
            onFullSwipe = openDetail,
            enabled = !editMode,
            shape = RoundedCornerShape(20.dp),
            action = {
                SwipeAction(
                    icon = R.drawable.ic_edit,
                    description = stringResource(R.string.ui_edit_detail),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = openDetail,
                )
            },
        ) {
            ManageCard(
                task = task,
                selected = task.id in selection,
                editMode = editMode,
                onClick = {
                    when {
                        openRowKey != null -> onOpenRow(null)
                        editMode -> onToggleNode(rowKey)
                        else -> onEditTask(task.id)
                    }
                },
                onLongClick = if (editMode) null else { { onEnterEdit(rowKey) } },
                onToggle = { onToggleNode(rowKey) },
            )
        }
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        item(
            key = "git-panel",
            contentType = "git-panel",
            span = StaggeredGridItemSpan.FullLine,
        ) {
            gitPanel()
        }
        item(
            key = SEARCH_HEADER_KEY,
            contentType = "search",
            span = StaggeredGridItemSpan.FullLine,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                header { 1f }
            }
        }
        item(
            key = "readme-section",
            contentType = "readme",
            span = StaggeredGridItemSpan.FullLine,
        ) {
            ReadmeSection(
                nodes = readmeNodes,
                query = readmeQuery,
                onOpen = onOpenReadme,
            )
        }
        if (ordered.isEmpty()) {
            item(
                key = "empty",
                contentType = "empty",
                span = StaggeredGridItemSpan.FullLine,
            ) {
                EmptyState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    title = emptyTitle,
                )
            }
        }
        if (noteTasks.isNotEmpty()) {
            items(noteTasks, key = { it.id }, contentType = { "note-card" }) { task ->
                TaskItem(task)
            }
            if (bodyTasks.isNotEmpty()) {
                item(
                    key = "note-divider",
                    contentType = "divider",
                    span = StaggeredGridItemSpan.FullLine,
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
        items(bodyTasks, key = { it.id }, contentType = { "card" }) { task ->
            TaskItem(task)
        }
    }
}

@Composable
private fun ManageCard(
    task: Task,
    selected: Boolean,
    editMode: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onToggle: () -> Unit,
) {
    val dim by animateFloatAsState(
        targetValue = if (task.done && !editMode) 0.5f else 1f,
        animationSpec = androidx.compose.animation.core.tween(Motion.FAST),
        label = "manage-alpha",
    )
    val parsed = task.parsed
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (editMode && selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .alpha(dim),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = parsed.body.ifEmpty { task.title },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = task.body.ifBlank { stringResource(R.string.ui_no_text) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = badgeTime(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = if (editMode) 40.dp else 0.dp),
                )
            }
            if (editMode) {
                RoundCheckbox(
                    state = if (selected) ToggleableState.On else ToggleableState.Off,
                    onToggle = onToggle,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                )
            }
        }
    }
}

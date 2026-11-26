package io.github.tohrutodo.tohru.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskOrder
import io.github.tohrutodo.tohru.task.TaskSelection
import io.github.tohrutodo.tohru.task.TaskTree
import io.github.tohrutodo.tohru.task.TreeRow

// Archive mirrors the task tree. Tap views an item, long-press enters edit
// mode with that item selected; dragging is not offered here.
@Composable
fun ArchiveScreen(
    tasks: List<Task>,
    collapsed: Set<String>,
    onToggleCollapse: (String) -> Unit,
    onRestore: (Set<String>) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    var editMode by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    BackHandler { if (editMode) editMode = false else onBack() }
    var viewing by remember { mutableStateOf<Task?>(null) }
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(editMode) {
        if (!editMode) selection = emptySet()
    }

    val archived = remember(tasks) { tasks.filter { it.archived } }
    val rows = remember(archived, collapsed) {
        TaskTree.rows(archived, TaskOrder(), collapsed = collapsed, archivedOnly = true)
    }

    fun selectionState(nodeKey: String): ToggleableState {
        val ids = TaskSelection.idsForNode(nodeKey, archived)
        if (ids.isEmpty()) return ToggleableState.Off
        val picked = ids.count { it in selection }
        return when (picked) {
            0 -> ToggleableState.Off
            ids.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
    }

    fun toggleNode(nodeKey: String) {
        val ids = TaskSelection.idsForNode(nodeKey, archived)
        selection = if (ids.isNotEmpty() && selection.containsAll(ids)) {
            selection - ids
        } else {
            selection + ids
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (editMode) {
                SelectionTopBar(
                    count = selection.size,
                    onClose = { editMode = false },
                    allSelected = TaskSelection.allSelected(selection, archived),
                    onToggleSelectAll = if (archived.isEmpty()) {
                        null
                    } else {
                        {
                            selection = if (TaskSelection.allSelected(selection, archived)) {
                                emptySet()
                            } else {
                                TaskSelection.idsOf(archived)
                            }
                        }
                    },
                    menuActions = { dismiss ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.archive_restore)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_unarchive),
                                    contentDescription = null,
                                )
                            },
                            enabled = selection.isNotEmpty(),
                            onClick = {
                                dismiss()
                                if (selection.isNotEmpty()) {
                                    onRestore(selection)
                                    editMode = false
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.archive_delete_forever)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                )
                            },
                            enabled = selection.isNotEmpty(),
                            onClick = {
                                dismiss()
                                if (selection.isNotEmpty()) pendingDelete = selection
                            },
                        )
                    },
                )
            } else {
                TohruTopBar(
                    title = stringResource(R.string.archive_title),
                    onBack = onBack,
                    actions = {},
                )
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                title = stringResource(R.string.archive_empty_title),
                hint = stringResource(R.string.archive_empty_hint),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows, key = { it.rowKey }, contentType = { row ->
                    when (row) {
                        is TreeRow.GroupHeader -> "group"
                        is TreeRow.UntaggedHeader -> "untagged"
                        is TreeRow.TopicHeader -> "topic"
                        is TreeRow.DoneHeader -> "done"
                        is TreeRow.Divider -> "divider"
                        is TreeRow.Item -> "task"
                    }
                }) { row ->
                    when (row) {
                        is TreeRow.GroupHeader -> GroupRow(
                            prefix = row.prefix,
                            count = row.count,
                            collapsed = row.collapsed,
                            state = selectionState(row.rowKey),
                            editMode = editMode,
                            showDragHandle = false,
                            onToggleCollapse = { onToggleCollapse(row.rowKey) },
                            onToggleSelect = { toggleNode(row.rowKey) },
                            onLongPress = {
                                editMode = true
                                selection = selection + TaskSelection.idsForNode(row.rowKey, archived)
                            },
                        )
                        is TreeRow.UntaggedHeader -> UntaggedRow(
                            count = row.count,
                            collapsed = row.collapsed,
                            state = selectionState(row.rowKey),
                            editMode = editMode,
                            onToggleCollapse = { onToggleCollapse(row.rowKey) },
                            onToggleSelect = { toggleNode(row.rowKey) },
                            onLongPress = {
                                editMode = true
                                selection = selection + TaskSelection.idsForNode(row.rowKey, archived)
                            },
                        )
                        is TreeRow.TopicHeader -> TopicRow(
                            topic = row.topic,
                            count = row.count,
                            collapsed = row.collapsed,
                            state = selectionState(row.rowKey),
                            editMode = editMode,
                            showDragHandle = false,
                            onToggleCollapse = { onToggleCollapse(row.rowKey) },
                            onToggleSelect = { toggleNode(row.rowKey) },
                            onLongPress = {
                                editMode = true
                                selection = selection + TaskSelection.idsForNode(row.rowKey, archived)
                            },
                        )
                        is TreeRow.DoneHeader -> DoneRow(
                            count = row.count,
                            depth = row.depth,
                            collapsed = row.collapsed,
                            state = selectionState(row.rowKey),
                            editMode = editMode,
                            onToggleCollapse = { onToggleCollapse(row.rowKey) },
                            onToggleSelect = { toggleNode(row.rowKey) },
                            onLongPress = {
                                editMode = true
                                selection = selection + TaskSelection.idsForNode(row.rowKey, archived)
                            },
                        )
                        is TreeRow.Divider -> HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        is TreeRow.Item -> TaskCardRow(
                            task = row.task,
                            selected = row.task.id in selection,
                            editMode = editMode,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            checkboxEnabled = editMode,
                            showDragHandle = false,
                            onClick = {
                                if (editMode) {
                                    toggleNode(row.rowKey)
                                } else {
                                    viewing = row.task
                                }
                            },
                            onLongClick = {
                                if (editMode) {
                                    toggleNode(row.rowKey)
                                } else {
                                    editMode = true
                                    selection = selection + row.task.id
                                }
                            },
                            onToggleDone = {},
                        )
                    }
                }
            }
        }
    }

    viewing?.let { task ->
        TaskPreviewSheet(task = task, onDismiss = { viewing = null })
    }

    pendingDelete?.let { ids ->
        ConfirmDialog(
            title = stringResource(R.string.archive_delete_title),
            text = stringResource(R.string.archive_delete_message, ids.size),
            confirmLabel = stringResource(R.string.archive_delete_confirm),
            onConfirm = {
                onDelete(ids)
                pendingDelete = null
                editMode = false
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
internal fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    allSelected: Boolean = false,
    onToggleSelectAll: (() -> Unit)? = null,
    canMarkDone: Boolean = false,
    canMarkUndone: Boolean = false,
    allSelectedDone: Boolean = false,
    onMarkDone: (() -> Unit)? = null,
    onMarkUndone: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    menuActions: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }

    TohruTopBar(
        title = stringResource(R.string.archive_selected_title, count),
        onBack = onClose,
        navIcon = R.drawable.ic_close,
        actions = {
            if (count > 0) {
                val undo = allSelectedDone || (!canMarkDone && canMarkUndone)
                if (undo && onMarkUndone != null) {
                    TohruIconButton(
                        icon = R.drawable.ic_undo,
                        description = stringResource(R.string.archive_mark_undone),
                        onClick = onMarkUndone,
                    )
                } else if (canMarkDone && onMarkDone != null) {
                    TohruIconButton(
                        icon = R.drawable.ic_check,
                        description = stringResource(R.string.archive_mark_done),
                        onClick = onMarkDone,
                    )
                }
                if (onCopy != null) {
                    TohruIconButton(
                        icon = R.drawable.ic_copy,
                        description = stringResource(R.string.archive_copy_selected),
                        onClick = onCopy,
                    )
                }
            }
            Box {
                TohruIconButton(
                    icon = R.drawable.ic_more_vert,
                    description = stringResource(R.string.archive_more_actions),
                    onClick = { menuOpen = true },
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    if (onToggleSelectAll != null) {
                        DropdownMenuItem(
                            text = {
                                Text(if (allSelected) stringResource(R.string.archive_clear_selection) else stringResource(R.string.archive_select_all))
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(
                                        if (allSelected) R.drawable.ic_remove_done
                                        else R.drawable.ic_done_all,
                                    ),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleSelectAll()
                            },
                        )
                    }
                    if (canMarkDone && canMarkUndone && onMarkUndone != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.archive_mark_undone)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_undo),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onMarkUndone()
                            },
                        )
                    }
                    menuActions { menuOpen = false }
                }
            }
        },
    )
}

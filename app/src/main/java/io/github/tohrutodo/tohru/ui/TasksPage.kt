package io.github.tohrutodo.tohru.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.tohrutodo.tohru.task.DragMath
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskOrder
import io.github.tohrutodo.tohru.task.SelectionScope
import io.github.tohrutodo.tohru.task.TaskSelection
import io.github.tohrutodo.tohru.task.TreeCollapse
import io.github.tohrutodo.tohru.task.TreeRow
import io.github.tohrutodo.tohru.R

// Reordering stays inside one sibling list. The dragged row follows the
// finger while the siblings it passes slide into the space it leaves; the move
// is committed once, on release.
@Composable
fun TasksPage(
    rows: List<TreeRow>,
    tasks: List<Task>,
    selection: Set<String>,
    editMode: Boolean,
    listState: LazyListState,
    openRowKey: String?,
    onOpenRow: (String?) -> Unit,
    onToggleCollapse: (String) -> Unit,
    onToggleNode: (String) -> Unit,
    onToggleDone: (String) -> Unit,
    onEnterEdit: (String) -> Unit,
    onEditTask: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onMove: (siblingKey: String, movedId: String, beforeId: String?) -> Unit,
    header: @Composable (collapse: () -> Float) -> Unit,
    emptyTitle: String,
    modifier: Modifier = Modifier,
    showDragHandle: Boolean = true,
    dragEnabled: Boolean = true,
) {
    val density = LocalDensity.current
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragSibling by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableFloatStateOf(0f) }
    var dragStartTop by remember { mutableFloatStateOf(0f) }
    var dragFingerDelta by remember { mutableFloatStateOf(0f) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTo by remember { mutableIntStateOf(-1) }
    var dropping by remember { mutableStateOf(false) }

    val latestMove by rememberUpdatedState(onMove)
    val hapticsNow by rememberUpdatedState(LocalHapticFeedback.current)
    val geometry = remember(rows) { LevelGeometry(rows) }
    val geometryNow = rememberUpdatedState(geometry)

    // While a header is dragged, every header on screen hides its children.
    // The set grows lazily with the visible window, so nothing off screen is
    // rebuilt.
    var dragCollapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val rowsByKey = remember(rows) { rows.associateBy { it.rowKey } }
    val visibleRows = remember(rows, dragCollapsed) {
        TreeCollapse.visible(rows, dragCollapsed)
    }

    fun selectionState(nodeKey: String): ToggleableState {
        val ids = TaskSelection.idsForNode(nodeKey, tasks, SelectionScope.OPEN)
        if (ids.isEmpty()) return ToggleableState.Off
        val picked = ids.count { it in selection }
        return when (picked) {
            0 -> ToggleableState.Off
            ids.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
    }

    fun resetDrag() {
        dragKey = null
        dragSibling = null
        dragOffset = 0f
        dragHeight = 0f
        dragStartTop = 0f
        dragFingerDelta = 0f
        dragCollapsed = emptySet()
        dragFrom = -1
        dragTo = -1
    }

    // Collects the headers currently on screen into the transient collapse set.
    fun collectVisibleHeaders() {
        val dragged = dragKey?.let { rowsByKey[it] } ?: return
        if (!TreeCollapse.isHeader(dragged)) return
        val onScreen = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val key = info.key as? String ?: return@mapNotNull null
            val row = rowsByKey[key] ?: return@mapNotNull null
            // Never collapse an ancestor of the dragged row: it would hide the
            // row being moved.
            key.takeIf {
                TreeCollapse.isHeader(row) && !TreeCollapse.isDescendant(dragged, key)
            }
        }
        if (onScreen.isEmpty()) return
        val merged = dragCollapsed + onScreen
        if (merged.size != dragCollapsed.size) dragCollapsed = merged
    }

    fun beginDrag(row: TreeRow) {
        if (dragKey != null) return
        val sibling = row.dragSiblingKey ?: return
        val levels = geometryNow.value
        hapticsNow.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == row.rowKey }
        dragKey = row.rowKey
        dragSibling = sibling
        dragOffset = 0f
        dragHeight = item?.size?.toFloat() ?: 0f
        dragStartTop = item?.offset?.toFloat() ?: 0f
        dragFingerDelta = 0f
        dragFrom = levels.indexOf[row.rowKey] ?: -1
        dragTo = dragFrom
        // Every header in the dragged level collapses together, on screen or
        // not: a level is a small set, and the alternative was stale expanded
        // siblings around the dragged row.
        if (TreeCollapse.isHeader(row)) {
            dragCollapsed = levels.keysBySibling[sibling].orEmpty()
                .filter { key -> rowsByKey[key]?.let { TreeCollapse.isHeader(it) } == true }
                .toSet()
        }
        collectVisibleHeaders()
    }

    // Absolute anchor: the row's starting slot plus the finger travel, minus
    // where the layout has since moved it. Recomputing is safe, so a collapsing
    // neighbour or an auto-scroll cannot double-count the translation.
    fun anchoredOffset(): Float {
        val key = dragKey ?: return dragOffset
        val top = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
        return if (top == null) dragOffset else DragMath.rowTranslation(dragStartTop, dragFingerDelta, top.toFloat())
    }

    // The drop slot is read from what the eye sees: how many siblings sit
    // above the dragged row's center right now. Rows outside the viewport
    // count by their position relative to the measured ones.
    fun updateDropTarget() {
        val key = dragKey ?: return
        val sibling = dragSibling ?: return
        val levels = geometryNow.value
        val level = levels.keysBySibling[sibling] ?: return
        if (dragFrom < 0) return
        val visible = listState.layoutInfo.visibleItemsInfo.associateBy { it.key }
        val draggedCenter = dragStartTop + dragFingerDelta + dragHeight / 2f
        val centers = level.map { rowKey ->
            if (rowKey == key) {
                null
            } else {
                visible[rowKey]?.let { it.offset + it.size / 2f }
            }
        }
        val drop = DragMath.dropIndex(centers, dragFrom, draggedCenter)
        // A prefix level holds two blocks: plain tasks first, then topics. A
        // drag stays inside the block it started in.
        val draggedPlain = key.startsWith("k:")
        val blockStart: Int
        val blockEnd: Int
        if (sibling.startsWith(TaskOrder.PREFIX)) {
            val plainCount = level.count { it.startsWith("k:") }
            if (draggedPlain) {
                blockStart = 0
                blockEnd = plainCount - 1
            } else {
                blockStart = plainCount
                blockEnd = level.lastIndex
            }
        } else {
            blockStart = 0
            blockEnd = level.lastIndex
        }
        val doneStart = if (sibling == TaskOrder.GROUPS) {
            blockEnd + 1
        } else {
            (blockStart..blockEnd).firstOrNull { levels.done[level[it]] == true } ?: (blockEnd + 1)
        }
        dragTo = DragMath.clampBlock(
            dropIndex = drop,
            blockStart = blockStart,
            blockEnd = blockEnd,
            doneStart = doneStart,
            draggedDone = levels.done[key] == true,
        )
    }

    fun finishDrag() {
        val key = dragKey ?: return
        val sibling = dragSibling ?: return
        val level = geometryNow.value.keysBySibling[sibling]
        val from = dragFrom
        val to = dragTo
        if (level == null || from < 0 || to < 0 || to == from) {
            resetDrag()
            return
        }
        val reordered = level.toMutableList().apply { add(to, removeAt(from)) }
        hapticsNow.performHapticFeedback(HapticFeedbackType.Confirm)
        dropping = true
        latestMove(sibling, key, reordered.getOrNull(to + 1))
    }

    // Glide the dropped row from the finger position into its new slot while
    // the list settles into the committed order.
    LaunchedEffect(dropping) {
        if (!dropping) return@LaunchedEffect
        Animatable(dragOffset).animateTo(0f, tween(Motion.NORMAL)) { dragOffset = value }
        dropping = false
        resetDrag()
    }

    // One scroll loop for the whole gesture. The anchored top is where the row
    // sits on screen, so the edge test does not depend on stale layout offsets.
    LaunchedEffect(dragKey, dropping) {
        if (dragKey == null || dropping) return@LaunchedEffect
        val edge = with(density) { 120.dp.toPx() }
        val step = with(density) { 8.dp.toPx() }
        while (true) {
            collectVisibleHeaders()
            val key = dragKey ?: break
            val info = listState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.key == key } ?: break
            val visualTop = dragStartTop + dragFingerDelta
            val visualBottom = visualTop + row.size
            val delta = when {
                visualTop < info.viewportStartOffset + edge -> -step
                visualBottom > info.viewportEndOffset - edge -> step
                else -> 0f
            }
            if (delta != 0f) {
                val consumed = listState.scrollBy(delta)
                if (consumed != 0f) updateDropTarget()
            }
            withFrameNanos { }
        }
    }

    // The layout moves on its own too: a collapsing neighbour or the
    // auto-scroll. Recompute the anchor from the formula, never accumulate, and
    // leave the drop target alone so collapse changes cannot feed back into it.
    LaunchedEffect(dragKey, dropping) {
        if (dragKey == null || dropping) return@LaunchedEffect
        val key = dragKey ?: return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
        }.collect { offset ->
            if (offset != null) {
                dragOffset = DragMath.rowTranslation(dragStartTop, dragFingerDelta, offset.toFloat())
            }
        }
    }

    fun siblingShiftTarget(rowKey: String): Float {
        val level = dragSibling ?: return 0f
        if (rowKey == dragKey) return 0f
        val levels = geometryNow.value
        // Move a whole topic or group block, children included, so a plain
        // task can never slide between a topic header and its tasks.
        val root = levels.blockRoot(rowKey, level) ?: return 0f
        val index = levels.indexOf[root] ?: return 0f
        return when {
            dragTo > dragFrom && index in (dragFrom + 1)..dragTo -> -dragHeight
            dragTo < dragFrom && index in dragTo until dragFrom -> dragHeight
            else -> 0f
        }
    }

    fun dragGesture(row: TreeRow, longPress: Boolean): Modifier {
        val sibling = row.dragSiblingKey ?: return Modifier
        return Modifier.pointerInput(row.rowKey, sibling, editMode, dragEnabled, longPress) {
            if (!editMode || !dragEnabled) return@pointerInput
            val start = { if (!dropping) beginDrag(row) }
            val end = { if (dragKey == row.rowKey) finishDrag() }
            val cancel = { if (dragKey == row.rowKey) resetDrag() }
            val move = { change: PointerInputChange, amount: Offset ->
                if (dragKey == row.rowKey && !dropping) {
                    change.consume()
                    dragFingerDelta += amount.y
                    dragOffset = anchoredOffset()
                    updateDropTarget()
                    collectVisibleHeaders()
                }
            }
            if (longPress) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start() },
                    onDragEnd = { end() },
                    onDragCancel = { cancel() },
                    onDrag = { change, amount -> move(change, amount) },
                )
            } else {
                detectDragGestures(
                    onDragStart = { start() },
                    onDragEnd = { end() },
                    onDragCancel = { cancel() },
                    onDrag = { change, amount -> move(change, amount) },
                )
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = SEARCH_HEADER_KEY, contentType = "search") {
            val collapse: () -> Float = {
                // Compress the row into whatever strip is still visible, as if
                // the top bar were pressing it shut.
                val info = listState.layoutInfo
                val header = info.visibleItemsInfo.firstOrNull { it.key == SEARCH_HEADER_KEY }
                if (header == null || header.size == 0) {
                    1f
                } else {
                    val visible = header.size + header.offset - info.beforeContentPadding
                    visible.toFloat() / header.size
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        scaleY = collapse().coerceIn(0f, 1f)
                    },
            ) {
                header(collapse)
            }
        }
        if (visibleRows.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    title = emptyTitle,
                )
            }
        }
        items(visibleRows, key = { it.rowKey }, contentType = { row ->
            when (row) {
                is TreeRow.GroupHeader -> "group"
                is TreeRow.UntaggedHeader -> "untagged"
                is TreeRow.TopicHeader -> "topic"
                is TreeRow.DoneHeader -> "done"
                is TreeRow.Divider -> "divider"
                is TreeRow.Item -> "task"
            }
        }) { row ->
            val dragging = dragKey == row.rowKey
            val shift = siblingShiftTarget(row.rowKey)
            val drag = dragGesture(row, longPress = true)
            val offsetModifier = Modifier
                .animateItem(
                    fadeInSpec = null,
                    fadeOutSpec = null,
                    placementSpec = tween(Motion.NORMAL),
                )
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer {
                    translationY = if (dragging) dragOffset else shift
                    val scale = if (dragging) 1.02f else 1f
                    scaleX = scale
                    scaleY = scale
                }
            when (row) {
                is TreeRow.GroupHeader -> GroupRow(
                    prefix = row.prefix,
                    count = row.count,
                    collapsed = row.collapsed,
                    state = selectionState(row.rowKey),
                    editMode = editMode,
                    showDragHandle = showDragHandle && row.dragSiblingKey != null,
                    modifier = offsetModifier.then(drag),
                    dragHandle = { DragHandle(dragGesture(row, longPress = false)) },
                    onToggleCollapse = { onToggleCollapse(row.rowKey) },
                    onToggleSelect = { onToggleNode(row.rowKey) },
                    onLongPress = { onEnterEdit(row.rowKey) },
                )
                is TreeRow.UntaggedHeader -> UntaggedRow(
                    count = row.count,
                    collapsed = row.collapsed,
                    state = selectionState(row.rowKey),
                    editMode = editMode,
                    onToggleCollapse = { onToggleCollapse(row.rowKey) },
                    onToggleSelect = { onToggleNode(row.rowKey) },
                    onLongPress = { onEnterEdit(row.rowKey) },
                    modifier = offsetModifier,
                )
                is TreeRow.TopicHeader -> TopicRow(
                    topic = row.topic,
                    count = row.count,
                    collapsed = row.collapsed,
                    state = selectionState(row.rowKey),
                    editMode = editMode,
                    showDragHandle = showDragHandle && row.dragSiblingKey != null,
                    modifier = offsetModifier.then(drag),
                    dragHandle = { DragHandle(dragGesture(row, longPress = false)) },
                    onToggleCollapse = { onToggleCollapse(row.rowKey) },
                    onToggleSelect = { onToggleNode(row.rowKey) },
                    onLongPress = { onEnterEdit(row.rowKey) },
                )
                is TreeRow.DoneHeader -> DoneRow(
                    count = row.count,
                    depth = row.depth,
                    collapsed = row.collapsed,
                    state = selectionState(row.rowKey),
                    editMode = editMode,
                    onToggleCollapse = { onToggleCollapse(row.rowKey) },
                    onToggleSelect = { onToggleNode(row.rowKey) },
                    onLongPress = { onEnterEdit(row.rowKey) },
                    modifier = offsetModifier.then(drag),
                )
                is TreeRow.Divider -> HorizontalDivider(
                    modifier = offsetModifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                is TreeRow.Item -> {
                    val rowKey = row.rowKey
                    val openDetail = {
                        onOpenRow(null)
                        onOpenDetail(row.task.id)
                    }
                    SwipeRevealRow(
                        reveal = SwipeReveal.End,
                        open = openRowKey == rowKey,
                        onOpenChange = { onOpenRow(if (it) rowKey else null) },
                        revealColor = MaterialTheme.colorScheme.secondaryContainer,
                        onFullSwipe = openDetail,
                        enabled = !editMode,
                        modifier = offsetModifier
                            .then(drag)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
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
                        TaskCardRow(
                            task = row.task,
                            selected = row.task.id in selection,
                            editMode = editMode,
                            showDragHandle = showDragHandle && row.dragSiblingKey != null,
                            dragHandle = { DragHandle(dragGesture(row, longPress = false)) },
                            onClick = {
                                when {
                                    openRowKey != null -> onOpenRow(null)
                                    editMode -> onToggleNode(rowKey)
                                    else -> onEditTask(row.task.id)
                                }
                            },
                            onLongClick = if (editMode) null else { { onEnterEdit(rowKey) } },
                            onToggleDone = { onToggleDone(row.task.id) },
                        )
                    }
                }
            }
        }
    }
}

// Sibling positions of the visible rows, cached per row list: level keys,
// per-level index, the first done slot of each level, and which rows are done.
//
// Only levels that render a done block report one; the prefix list itself is
// a single block, so its headers are never clamped.
private class LevelGeometry(rows: List<TreeRow>) {
    val keysBySibling: Map<String?, List<String>> =
        rows.groupBy { it.siblingKey }.mapValues { entry -> entry.value.map { it.rowKey } }
    val indexOf: Map<String, Int> = buildMap {
        keysBySibling.values.forEach { keys ->
            keys.forEachIndexed { index, key -> put(key, index) }
        }
    }
    val done: Map<String, Boolean> = rows.associate { it.rowKey to isDoneRow(it) }

    private val byKey: Map<String, TreeRow> = rows.associateBy { it.rowKey }
    private val roots = mutableMapOf<Pair<String, String>, String?>()

    fun blockRoot(rowKey: String, level: String): String? =
        roots.getOrPut(rowKey to level) { TreeCollapse.blockRoot(byKey, rowKey, level) }
}

private fun isDoneRow(row: TreeRow): Boolean = when (row) {
    is TreeRow.Item -> row.task.done
    is TreeRow.GroupHeader -> row.doneSection || row.count == 0
    is TreeRow.UntaggedHeader -> false
    is TreeRow.TopicHeader -> row.doneSection || row.count == 0
    is TreeRow.DoneHeader -> true
    is TreeRow.Divider -> false
}

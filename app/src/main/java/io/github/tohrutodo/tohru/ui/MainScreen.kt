package io.github.tohrutodo.tohru.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FabPosition
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.ReadmeDoc
import io.github.tohrutodo.tohru.task.TaskCopy
import io.github.tohrutodo.tohru.task.TaskOrder
import io.github.tohrutodo.tohru.task.TaskSelection
import io.github.tohrutodo.tohru.task.TaskSorting
import io.github.tohrutodo.tohru.task.TaskStats
import io.github.tohrutodo.tohru.sync.GitSnapshot
import io.github.tohrutodo.tohru.task.TaskTree
import io.github.tohrutodo.tohru.task.TreeRow

internal const val PAGE_MANAGE = 0
internal const val PAGE_TASKS = 1

@Composable
private fun StatValue(count: Int, label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(InlineGap)) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
fun MainScreen(
    tasks: List<Task>,
    readmes: List<ReadmeDoc>,
    onOpenReadme: (List<String>) -> Unit,
    order: TaskOrder,
    query: String,
    onQueryChange: (String) -> Unit,
    prefixFilter: Set<String>,
    notePrefixes: Set<String>,
    onTogglePrefix: (String) -> Unit,
    collapsed: Set<String>,
    onToggleCollapse: (String) -> Unit,
    editMode: Boolean,
    selection: Set<String>,
    page: Int,
    onPageChange: (Int) -> Unit,
    onToggleDone: (String) -> Unit,
    onEditTask: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onAdd: (title: String, body: String) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnterEdit: () -> Unit,
    onEnterEditNode: (String) -> Unit,
    onExitEdit: () -> Unit,
    onToggleNode: (String) -> Unit,
    onSelectAll: (Set<String>) -> Unit,
    onClearSelection: () -> Unit,
    onArchiveSelected: () -> Unit,
    onDeleteSelected: (Set<String>) -> Unit,
    onMarkSelectedDone: () -> Unit,
    onMarkSelectedUndone: () -> Unit,
    onMove: (siblingKey: String, movedId: String, beforeId: String?) -> Unit,
    gitSnapshot: GitSnapshot?,
    gitBusy: Boolean,
    onSync: () -> Unit,
    gitPanel: @Composable () -> Unit,
    snackbar: SnackbarHostState,
) {
    val pagerState = rememberPagerState(initialPage = page) { 2 }
    val scope = rememberCoroutineScope()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    val tasksListState = rememberLazyListState()
    val manageGridState = rememberLazyStaggeredGridState()
    var openRowKey by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val resources = LocalResources.current

    // The search row starts collapsed, so a board with only a few tasks hides
    // it just as well as a long one. Filter chips are a second, separate toggle.
    var tasksSearchOpen by remember { mutableStateOf(false) }
    var tasksFilterVisible by remember { mutableStateOf(false) }
    var manageFilterVisible by remember { mutableStateOf(false) }

    val pinned = query.isNotBlank() || prefixFilter.isNotEmpty()
    val pinnedNow = rememberUpdatedState(pinned)
    LaunchedEffect(pinned) {
        if (pinned) {
            tasksSearchOpen = true
        }
    }
    // A selected prefix has to stay visible, or the list looks filtered for no
    // reason. The chips can still be closed again afterwards.
    LaunchedEffect(prefixFilter) {
        if (prefixFilter.isNotEmpty()) {
            tasksFilterVisible = true
            manageFilterVisible = true
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect(onPageChange)
    }

    // A revealed row closes when the board moves: another row opens, the list
    // scrolls, the tab changes or selection starts.
    LaunchedEffect(editMode) {
        if (editMode) openRowKey = null
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { openRowKey = null }
    }
    LaunchedEffect(tasksListState) {
        snapshotFlow { tasksListState.isScrollInProgress }
            .collect { if (it) openRowKey = null }
    }
    LaunchedEffect(manageGridState) {
        snapshotFlow { manageGridState.isScrollInProgress }
            .collect { if (it) openRowKey = null }
    }

    // Leaving the app collapses the row, so coming back always starts clean.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!pinnedNow.value) {
            tasksSearchOpen = false
        }
    }

    // A pull at the top of a board opens its row; nothing else grows it.
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y <= 0f) return Offset.Zero
                if (pagerState.currentPage != PAGE_TASKS) return Offset.Zero
                if (tasksSearchOpen) return Offset.Zero
                if (tasksListState.canScrollBackward) return Offset.Zero
                tasksSearchOpen = true
                return available
            }
        }
    }

    // Switching boards collapses the search row, so coming back never lands on
    // a field left open. An active query or prefix keeps both rows.
    LaunchedEffect(pagerState) {
        var last = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            if (settled != last) {
                if (!pinnedNow.value) tasksSearchOpen = false
                last = settled
            }
        }
    }

    // A header left half cut by a scroll settles on the nearer state as soon
    // as the list stops, so it never rests between fully shown and hidden.
    LaunchedEffect(Unit) {
        snapshotFlow { tasksListState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling || !tasksSearchOpen) return@collect
                val info = tasksListState.layoutInfo
                val header = info.visibleItemsInfo.firstOrNull { it.key == SEARCH_HEADER_KEY }
                    ?: return@collect
                val delta = HeaderSnap.snapDelta(header.offset, header.size, info.beforeContentPadding)
                    ?: return@collect
                tasksListState.animateScrollBy(delta, tween(Motion.NORMAL))
            }
    }
    val active = remember(tasks) { tasks.filterNot { it.archived } }
    val taskActive = remember(active, notePrefixes) {
        active.filterNot { it.parsed.prefix in notePrefixes }
    }
    val prefixes = remember(taskActive, order, notePrefixes) {
        TaskSorting.prefixes(taskActive, order, notePrefixes)
    }
    val stats = remember(taskActive, notePrefixes) {
        TaskStats.of(taskActive, notePrefixes)
    }
    val filteredTasks = remember(taskActive, query, prefixFilter) {
        taskActive.filter { task ->
            (prefixFilter.isEmpty() || task.parsed.prefix in prefixFilter) &&
                TaskTree.matches(task, query)
        }
    }
    val filteredManage = remember(active, query, prefixFilter) {
        active.filter { task ->
            (prefixFilter.isEmpty() || task.parsed.prefix in prefixFilter) &&
                TaskTree.matches(task, query)
        }
    }
    val scopeTasks = if (page == PAGE_TASKS) filteredTasks else filteredManage
    val readmeNodes = remember(readmes, active, query) {
        buildReadmeTree(readmes, active, query)
    }
    val rows = remember(filteredTasks, order, collapsed, query) {
        if (query.isBlank()) {
            TaskTree.rows(filteredTasks, order, collapsed = collapsed)
        } else {
            TaskTree.rows(filteredTasks, order, query = query)
        }
    }
    val activeById = remember(active) { active.associateBy { it.id } }
    // Selection stores plain task ids, not node keys.
    val selectedIds = remember(selection, active) { TaskSelection.selectedIds(selection, active) }
    val allSelectedDone = selectedIds.isNotEmpty() &&
        selectedIds.all { activeById[it]?.done == true }
    val anySelectedDone = selectedIds.any { activeById[it]?.done == true }

    fun copySelected() {
        val inBoardOrder = if (page == PAGE_TASKS) {
            TaskTree.rows(taskActive, order, collapsed = emptySet())
                .filterIsInstance<TreeRow.Item>()
                .map { it.task }
                .filter { it.id in selectedIds }
        } else {
            TaskSorting.alphabetical(active).filter { it.id in selectedIds }
        }
        val included = inBoardOrder.mapTo(mutableSetOf()) { it.id }
        val ordered = inBoardOrder + active.filter { it.id in selectedIds && it.id !in included }
        if (ordered.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Tohru", TaskCopy.format(ordered)))
        scope.launch { snackbar.showSnackbar(resources.getString(R.string.app_copied, ordered.size)) }
    }

    // The header lives inside each page's list, so it scrolls away with the
    // content instead of floating above it. The filter row is a separate
    // toggle inside the same item.
    val tasksHeader: @Composable (() -> Float) -> Unit = { collapse ->
        SearchHeader(
            open = tasksSearchOpen,
            filterVisible = tasksFilterVisible,
            query = query,
            onQueryChange = onQueryChange,
            prefixes = prefixes,
            selectedPrefixes = prefixFilter,
            onTogglePrefix = onTogglePrefix,
            onToggleFilter = { tasksFilterVisible = !tasksFilterVisible },
            collapse = collapse,
        )
    }
    val manageHeader: @Composable (() -> Float) -> Unit = { _ ->
        SearchHeader(
            open = true,
            filterVisible = manageFilterVisible,
            query = query,
            onQueryChange = onQueryChange,
            prefixes = prefixes,
            selectedPrefixes = prefixFilter,
            onTogglePrefix = onTogglePrefix,
            onToggleFilter = { manageFilterVisible = !manageFilterVisible },
            stats = stats,
            animate = false,
            collapse = { 1f },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (editMode) {
                SelectionTopBar(
                    count = selection.size,
                    onClose = onExitEdit,
                    allSelected = TaskSelection.allSelected(selection, scopeTasks),
                    onToggleSelectAll = if (scopeTasks.isEmpty()) {
                        null
                    } else {
                        {
                            if (TaskSelection.allSelected(selection, scopeTasks)) {
                                onClearSelection()
                            } else {
                                onSelectAll(TaskSelection.idsOf(scopeTasks))
                            }
                        }
                    },
                    canMarkDone = selectedIds.isNotEmpty() && !allSelectedDone,
                    canMarkUndone = anySelectedDone,
                    allSelectedDone = allSelectedDone,
                    onMarkDone = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onMarkSelectedDone()
                    },
                    onMarkUndone = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onMarkSelectedUndone()
                    },
                    onCopy = if (selectedIds.isNotEmpty()) ::copySelected else null,
                    menuActions = { dismiss ->
                        if (selectedIds.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.app_archive)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_archive),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    dismiss()
                                    onArchiveSelected()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.app_delete_selected),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    dismiss()
                                    confirmDeleteSelection = true
                                },
                            )
                        }
                    },
                )
            } else {
                TohruTopBar(
                    title = if (page == PAGE_TASKS) {
                        stringResource(R.string.app_tasks)
                    } else {
                        stringResource(R.string.app_manage)
                    },
                    actions = {
                        SyncTopBarButton(snapshot = gitSnapshot, busy = gitBusy, onClick = onSync)
                        TohruIconButton(
                            icon = R.drawable.ic_archive,
                            description = stringResource(R.string.app_archive_desc),
                            onClick = onOpenArchive,
                        )
                        TohruIconButton(
                            icon = R.drawable.ic_settings,
                            description = stringResource(R.string.app_settings_desc),
                            onClick = onOpenSettings,
                        )
                    },
                )
            }
        },
        floatingActionButton = {
            if (!editMode) QuickAddButton(onClick = { showAdd = true })
        },
        floatingActionButtonPosition = FabPosition.End,
        bottomBar = {
            if (!editMode) {
                NavigationBar(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .height(64.dp),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    NavigationBarItem(
                        selected = page == PAGE_MANAGE,
                        onClick = {
                            if (page == PAGE_MANAGE) {
                                scope.launch { manageGridState.animateScrollToItem(0) }
                            } else {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                scope.launch { pagerState.animateScrollToPage(PAGE_MANAGE) }
                            }
                        },
                        icon = {
                            Icon(painterResource(R.drawable.ic_note), contentDescription = null)
                        },
                        label = { Text(stringResource(R.string.app_manage)) },
                    )
                    NavigationBarItem(
                        selected = page == PAGE_TASKS,
                        onClick = {
                            if (page == PAGE_TASKS) {
                                scope.launch { tasksListState.animateScrollToItem(0) }
                            } else {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                scope.launch { pagerState.animateScrollToPage(PAGE_TASKS) }
                            }
                        },
                        icon = {
                            Icon(painterResource(R.drawable.ic_task), contentDescription = null)
                        },
                        label = { Text(stringResource(R.string.app_tasks)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(connection),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // Rows swipe away from the board they leave: the tab
                    // gesture and the row gesture never share a direction.
                    userScrollEnabled = !editMode,
                    beyondViewportPageCount = 1,
                ) { pageIndex ->
                    val emptyTitle = if (query.isNotBlank()) {
                        stringResource(R.string.app_empty_no_match)
                    } else {
                        stringResource(R.string.app_empty_no_tasks)
                    }
                    if (pageIndex == PAGE_MANAGE) {
                        ManagePage(
                            tasks = filteredManage,
                            notePrefixes = notePrefixes,
                            gridState = manageGridState,
                            header = manageHeader,
                            gitPanel = gitPanel,
                            readmeNodes = readmeNodes,
                            readmeQuery = query,
                            onOpenReadme = onOpenReadme,
                            emptyTitle = emptyTitle,
                            editMode = editMode,
                            selection = selection,
                            openRowKey = openRowKey,
                            onOpenRow = { openRowKey = it },
                            onToggleNode = onToggleNode,
                            onEnterEdit = onEnterEditNode,
                            onEditTask = onEditTask,
                            onOpenDetail = onOpenDetail,
                        )
                    } else {
                        TasksPage(
                            rows = rows,
                            tasks = taskActive,
                            selection = selection,
                            editMode = editMode,
                            listState = tasksListState,
                            openRowKey = openRowKey,
                            onOpenRow = { openRowKey = it },
                            header = tasksHeader,
                            emptyTitle = emptyTitle,
                            onToggleCollapse = onToggleCollapse,
                            onToggleNode = onToggleNode,
                            onToggleDone = onToggleDone,
                            onEnterEdit = onEnterEditNode,
                            onEditTask = onEditTask,
                            onOpenDetail = onOpenDetail,
                            onMove = onMove,
                            dragEnabled = query.isBlank(),
                        )
                    }
                }
            }
        }
    }

    if (confirmDeleteSelection) {
        ConfirmDialog(
            title = stringResource(R.string.app_delete_selected_title),
            text = stringResource(R.string.app_delete_selected_message, selectedIds.size),
            confirmLabel = stringResource(R.string.app_delete_selected_confirm),
            onConfirm = {
                confirmDeleteSelection = false
                onDeleteSelected(selectedIds)
            },
            onDismiss = { confirmDeleteSelection = false },
        )
    }

    if (showAdd) {
        QuickAddSheet(
            onDismiss = { showAdd = false },
            onSave = { title, body -> onAdd(title, body) },
        )
    }
}

// Search row plus the filter toggle. The tasks board animates this row while
// the Manage board keeps it visible all the time.
@Composable
private fun SearchHeader(
    open: Boolean,
    filterVisible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    prefixes: List<String>,
    selectedPrefixes: Set<String>,
    onTogglePrefix: (String) -> Unit,
    onToggleFilter: () -> Unit,
    stats: TaskStats? = null,
    animate: Boolean = true,
    collapse: () -> Float,
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                collapse = collapse,
                trailingIcon = {
                    IconButton(
                        onClick = onToggleFilter,
                        enabled = prefixes.isNotEmpty(),
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer { alpha = collapse().coerceIn(0f, 1f) },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_filter),
                            contentDescription = if (filterVisible) {
                                stringResource(R.string.app_filter_hide)
                            } else {
                                stringResource(R.string.app_filter_show)
                            },
                            modifier = Modifier.size(24.dp),
                            tint = if (filterVisible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
            stats?.let { summary ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ClusterGap),
                ) {
                    StatValue(
                        count = summary.total,
                        label = stringResource(R.string.app_stats_total),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    StatValue(
                        count = summary.open,
                        label = stringResource(R.string.app_stats_open),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    StatValue(
                        count = summary.done,
                        label = stringResource(R.string.app_stats_done),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            AnimatedVisibility(
                visible = filterVisible,
                enter = fadeIn(Motion.fadeIn()) + expandVertically(Motion.enter()),
                exit = fadeOut(Motion.fadeOut()) + shrinkVertically(Motion.exit()),
            ) {
                PrefixChips(
                    prefixes = prefixes,
                    selected = selectedPrefixes,
                    onToggle = onTogglePrefix,
                )
            }
        }
    }
    if (animate) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(Motion.fadeIn()) + expandVertically(Motion.enter()),
            exit = fadeOut(Motion.fadeOut()) + shrinkVertically(Motion.exit()),
        ) {
            content()
        }
    } else {
        content()
    }
}

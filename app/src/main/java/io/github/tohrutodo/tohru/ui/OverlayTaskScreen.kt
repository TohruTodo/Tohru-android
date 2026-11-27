package io.github.tohrutodo.tohru.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.overlay.overlayEdgeGestures
import io.github.tohrutodo.tohru.TohruApp
import io.github.tohrutodo.tohru.sync.SyncSettings
import io.github.tohrutodo.tohru.store.TaskStoreEvent
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskEditor
import io.github.tohrutodo.tohru.task.TaskFile
import io.github.tohrutodo.tohru.task.TaskTree

// Full-screen task overlay. The window is transparent and blurred behind a
// near-opaque surface from the same theme as the main app.
@Composable
fun OverlayTaskScreen(
    app: TohruApp,
    onClose: () -> Unit,
) {
    val menuState = remember { AppTextContextMenuState() }
    var closing by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (closing) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "overlay-exit",
    )
    LaunchedEffect(closing) {
        if (closing) {
            delay(180)
            onClose()
        }
    }
    val density = LocalDensity.current
    val shift = with(density) { 16.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = progress
                scaleX = 0.98f + 0.02f * progress
                scaleY = 0.98f + 0.02f * progress
                translationY = (1f - progress) * shift
            },
    ) {
        AppTextContextMenuHost(menuState) {
            OverlayTaskContent(app, onClose = { if (!closing) closing = true })
        }
    }
}

@Composable
private fun OverlayTaskContent(
    app: TohruApp,
    onClose: () -> Unit,
) {
    var ready by remember(app.store) { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val taskFileError = stringResource(R.string.overlay_error_task_file)
    val readError = stringResource(R.string.overlay_error_read)
    val taskMissing = stringResource(R.string.overlay_task_missing)
    LaunchedEffect(app.store) {
        app.store.events.collect { event ->
            when (event) {
                is TaskStoreEvent.Failure -> {
                    Log.e("TohruTaskStore", "overlay task operation failed", event.cause)
                    snackbar.showSnackbar(taskFileError)
                }
                is TaskStoreEvent.InvalidFile -> {
                    Log.e("TohruTaskStore", "overlay invalid task file: ${event.fileName}", event.cause)
                }
            }
        }
    }
    LaunchedEffect(app.store) {
        try {
            app.store.refresh()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("TohruTaskStore", "overlay refresh failed", error)
            snackbar.showSnackbar(readError)
        }
        ready = true
    }
    val tasks by app.store.tasks.collectAsStateWithLifecycle()
    val order by app.store.order.collectAsStateWithLifecycle()
    val taskLinks by app.store.links.collectAsStateWithLifecycle()
    val collapsed by app.collapsed.collectAsStateWithLifecycle()
    val notePrefixes = remember { SyncSettings.readNotePrefixes(app.prefs) }
    val active = remember(tasks, notePrefixes) {
        tasks.filterNot { it.archived || it.parsed.prefix in notePrefixes }
    }
    val rows = remember(active, order, collapsed) {
        TaskTree.rows(active, order, collapsed = collapsed)
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var openRowKey by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var composerOpen by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var previewTaskId by remember { mutableStateOf<String?>(null) }
    val handleBack: () -> Unit = {
        when {
            previewTaskId != null -> previewTaskId = null
            composerOpen -> composerOpen = false
            detailId != null -> detailId = null
            else -> onClose()
        }
    }

    if (!ready) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .overlayEdgeGestures(onBack = handleBack),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.overlay_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    BackHandler(onBack = handleBack)

    val openTaskLink: (String) -> Unit = { ref ->
        val target = taskLinks.idFor(ref)?.let { id -> tasks.firstOrNull { it.id == id } }
        if (target != null) {
            previewTaskId = target.id
        } else {
            Log.w("TohruTaskStore", "overlay task link not found: $ref")
            scope.launch { snackbar.showSnackbar(taskMissing) }
        }
    }
    val detail = detailId?.let { id -> active.firstOrNull { it.id == id } }
    if (detail != null) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .overlayEdgeGestures(onBack = handleBack),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                TaskDetailScreen(
                    taskId = detail.id,
                    task = detail,
                    tasks = tasks,
                    taskLinks = taskLinks,
                    onSave = { draft -> scope.launch { app.store.save(draft) } },
                    onArchive = { id, draft ->
                        scope.launch {
                            draft?.let { app.store.save(it) }
                            app.store.setArchived(listOf(id), true)
                            detailId = null
                        }
                    },
                    onOpenTaskLink = openTaskLink,
                    onExit = { detailId = null },
                )
                val previewTask = previewTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
                if (previewTask != null) {
                    // The overlay runs in a Service window, so a Dialog-based
                    // sheet has no window token. Draw the card in this window.
                    BackHandler { previewTaskId = null }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                            .clickable { previewTaskId = null },
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .navigationBarsPadding(),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(modifier = Modifier.padding(20.dp)) {
                            TaskPreviewContent(
                                task = previewTask,
                                onTaskLink = openTaskLink,
                                taskExists = { ref -> taskLinks.idFor(ref) != null },
                            )
                        }
                    }
                }
                TohruSnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(12.dp)
                        .zIndex(10f),
                )
            }
        }
        return
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .overlayEdgeGestures(onBack = handleBack),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                OverlayTopBar(
                    onAdd = {
                        editing = null
                        composerOpen = true
                    },
                    onClose = onClose,
                )
                if (composerOpen) {
                    TaskComposer(
                        initial = editing,
                        onDone = { title, body, done ->
                            val base = editing
                            if (TaskEditor.shouldSave(base, title, body, done)) {
                                val task = if (base != null) {
                                    base.copy(title = title.trim(), body = body, done = done)
                                } else {
                                    Task(
                                        id = TaskFile.newId(),
                                        title = title.trim(),
                                        body = body,
                                        done = done,
                                    )
                                }
                                scope.launch {
                                    app.store.save(task)
                                    composerOpen = false
                                    editing = null
                                }
                            } else {
                                composerOpen = false
                                editing = null
                            }
                        },
                    )
                }
                TasksPage(
                    rows = rows,
                    tasks = active,
                    selection = emptySet(),
                    editMode = false,
                    listState = listState,
                    openRowKey = openRowKey,
                    onOpenRow = { openRowKey = it },
                    onToggleCollapse = { app.toggleCollapsed(it) },
                    onToggleNode = {},
                    onToggleDone = { id -> scope.launch { app.store.toggle(id) } },
                    onEnterEdit = {},
                    onEditTask = { id ->
                        editing = active.firstOrNull { it.id == id }
                        composerOpen = editing != null
                    },
                    onOpenDetail = { detailId = it },
                    onMove = { _, _, _ -> },
                    header = { _ -> },
                    emptyTitle = stringResource(R.string.overlay_empty),
                    modifier = Modifier.weight(1f),
                    showDragHandle = false,
                    dragEnabled = false,
                )
            }
            TohruSnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .zIndex(10f),
            )
        }
    }
}

@Composable
private fun OverlayTopBar(onAdd: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilledIconButton(
            onClick = onAdd,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.overlay_add_task))
        }
        Text(
            stringResource(R.string.overlay_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.overlay_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Inline composer matching the task row language: checkbox, title, body, and
// a single Xong action. No child-task hint or reminder controls.
@Composable
private fun TaskComposer(
    initial: Task?,
    onDone: (String, String, Boolean) -> Unit,
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var body by remember(initial?.id) { mutableStateOf(initial?.body.orEmpty()) }
    var done by remember(initial?.id) { mutableStateOf(initial?.done ?: false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(checked = done, onCheckedChange = { done = it })
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.overlay_task_name),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.overlay_task_description),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        minLines = 1,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { onDone(title, body, done) },
                ) {
                    Text(stringResource(R.string.ui_done), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

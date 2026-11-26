package io.github.tohrutodo.tohru.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.TohruApp
import io.github.tohrutodo.tohru.security.CredentialVault
import io.github.tohrutodo.tohru.store.TaskStore
import io.github.tohrutodo.tohru.store.TaskStoreEvent
import io.github.tohrutodo.tohru.sync.ConflictResolution
import io.github.tohrutodo.tohru.sync.ConflictSession
import io.github.tohrutodo.tohru.sync.GitBranch
import io.github.tohrutodo.tohru.sync.GitSnapshot
import io.github.tohrutodo.tohru.sync.GitSync
import io.github.tohrutodo.tohru.sync.PullOutcome
import io.github.tohrutodo.tohru.sync.syncFailureRes
import io.github.tohrutodo.tohru.sync.SyncSettings
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskArchive
import io.github.tohrutodo.tohru.task.TaskFile
import io.github.tohrutodo.tohru.task.TaskParser
import io.github.tohrutodo.tohru.task.TaskSettings
import io.github.tohrutodo.tohru.task.SelectionScope
import io.github.tohrutodo.tohru.task.TaskSelection
import io.github.tohrutodo.tohru.task.TaskSorting
import java.io.File

sealed interface Screen {
    data object Main : Screen
    data class Detail(val id: String) : Screen
    data object Archive : Screen
    data object Settings : Screen
    data object GitHistory : Screen
    data object GitDirty : Screen
    data class Readme(val segments: List<String>) : Screen
}

private val ScreenSaver = Saver<Screen, List<String>>(
    save = { screen ->
        when (screen) {
            is Screen.Main -> listOf("main")
            is Screen.Detail -> listOf("detail", screen.id)
            is Screen.Archive -> listOf("archive")
            is Screen.Settings -> listOf("settings")
            is Screen.GitHistory -> listOf("git-history")
            is Screen.GitDirty -> listOf("git-dirty")
            is Screen.Readme -> listOf("readme", screen.segments.joinToString("/"))
        }
    },
    restore = { parts ->
        when (parts.firstOrNull()) {
            "detail" -> parts.getOrNull(1)?.let { Screen.Detail(it) } ?: Screen.Main
            "archive" -> Screen.Archive
            "settings" -> Screen.Settings
            "git-history" -> Screen.GitHistory
            "git-dirty" -> Screen.GitDirty
            "readme" -> Screen.Readme(
                parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.split('/').orEmpty(),
            )
            else -> Screen.Main
        }
    },
)

private fun pruneExports(directory: File, keep: Int) {
    directory.listFiles()
        ?.sortedByDescending { it.lastModified() }
        ?.drop(keep.coerceAtLeast(1))
        ?.forEach { old -> runCatching { old.delete() } }
}

@Composable
fun AppRoot(
    app: TohruApp,
    store: TaskStore,
    git: GitSync,
    prompter: SyncPrompter,
    vault: CredentialVault,
    dynamic: Boolean,
    onDynamicChange: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    haptics: Boolean,
    onHapticsChange: (Boolean) -> Unit,
) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Main) }
    var page by rememberSaveable { mutableIntStateOf(PAGE_TASKS) }
    var query by rememberSaveable { mutableStateOf("") }
    var prefixFilterList by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var tasksLoaded by remember { mutableStateOf(false) }
    var editMode by rememberSaveable { mutableStateOf(false) }
    var editingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var branchDialog by remember { mutableStateOf(false) }
    var newBranch by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf<List<GitBranch>>(emptyList()) }
    var branchToDelete by remember { mutableStateOf<String?>(null) }
    var selectionList by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var gitSnapshot by remember { mutableStateOf<GitSnapshot?>(null) }
    var gitBusy by remember { mutableStateOf(false) }
    var conflictSession by remember { mutableStateOf<ConflictSession?>(null) }
    var commitDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<File?>(null) }

    val context = LocalContext.current
    val resources = LocalResources.current
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var inboxPrefixes by remember { mutableStateOf(SyncSettings.readInboxPrefixes(prefs)) }
    var notePrefixes by remember { mutableStateOf(SyncSettings.readNotePrefixes(prefs)) }
    val collapsed by app.collapsed.collectAsStateWithLifecycle()
    fun toggleCollapsed(key: String) = app.toggleCollapsed(key)

    val selection = remember(selectionList) { selectionList.toSet() }
    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            uiScope.launch {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "imports").apply { mkdirs() }
                    val target = File(directory, "import-${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.takeIf { it.isFile }
                }
                if (file != null) pendingImport = file
            }
        }
    }
    val tasks by store.tasks.collectAsStateWithLifecycle()
    val readmes by store.readmes.collectAsStateWithLifecycle()
    val order by store.order.collectAsStateWithLifecycle()
    val taskLinks by store.links.collectAsStateWithLifecycle()
    val active = remember(tasks) { tasks.filterNot { it.archived } }
    val openTaskLink: (String) -> Unit = { ref ->
        val target = taskLinks.idFor(ref)?.let { id -> tasks.firstOrNull { it.id == id } }
        if (target != null) {
            previewTaskId = target.id
        } else {
            Log.w("TohruTaskStore", "task link not found: $ref")
            uiScope.launch { snackbar.showSnackbar(resources.getString(R.string.app_task_not_found)) }
        }
    }
    val hostKeyRequest by prompter.hostKey.collectAsStateWithLifecycle()
    var lastSync by remember { mutableLongStateOf(SyncSettings.readLastSync(prefs)) }
    val lastSyncLabel = lastSync.takeIf { it > 0 }?.let {
        stringResource(R.string.app_last_sync, relativeTime(it, System.currentTimeMillis(), resources))
    }

    LaunchedEffect(store) {
        store.events.collect { event ->
            when (event) {
                is TaskStoreEvent.Failure -> {
                    Log.e("TohruTaskStore", "task operation failed", event.cause)
                    snackbar.showSnackbar(resources.getString(R.string.app_task_file_error))
                }
                is TaskStoreEvent.InvalidFile -> {
                    Log.e("TohruTaskStore", "invalid task file: ${event.fileName}", event.cause)
                }
            }
        }
    }
    LaunchedEffect(store, inboxPrefixes) {
        TaskParser.configureInbox(inboxPrefixes)
        try {
            store.refresh()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("TohruTaskStore", "refresh failed", error)
            snackbar.showSnackbar(resources.getString(R.string.app_task_data_error))
        }
        tasksLoaded = true
        gitSnapshot = git.snapshot()
        conflictSession = git.pendingSession()
        pruneExports(File(context.cacheDir, "exports"), SyncSettings.readMaintenance(prefs).exportKeep)
        uiScope.launch { git.maintain() }
    }
    LaunchedEffect(tasks, tasksLoaded, notePrefixes, order) {
        if (!tasksLoaded) return@LaunchedEffect
        val pruned = TaskSorting.prunePrefixFilter(
            selected = prefixFilterList.toSet(),
            tasks = tasks,
            order = order,
            excludedPrefixes = notePrefixes,
        ).toList()
        if (pruned != prefixFilterList) prefixFilterList = pruned
    }
    LaunchedEffect(tasks) {
        delay(350)
        gitSnapshot = git.snapshot()
    }
    LaunchedEffect(editingTaskId, tasks) {
        val id = editingTaskId
        if (id != null && tasks.none { it.id == id }) editingTaskId = null
    }

    // Priority: sheet handles itself, then edit mode, then navigation.
    BackHandler(enabled = editMode) {
        editMode = false
        selectionList = emptyList()
    }
    BackHandler(enabled = !editMode && screen !is Screen.Main) { screen = Screen.Main }

    fun saveTitle(title: String, body: String) {
        uiScope.launch {
            store.save(Task(id = TaskFile.newId(), title = title, body = body))
            page = PAGE_TASKS
        }
    }

    fun exitEdit() {
        editMode = false
        selectionList = emptyList()
    }

    fun refreshGit() {
        uiScope.launch { gitSnapshot = git.snapshot() }
    }

    fun syncFailureMessage(error: Throwable): String {
        Log.e("TohruSync", "sync operation failed", error)
        return resources.getString(syncFailureRes(error))
    }

    fun markSynced() {
        SyncSettings.saveLastSync(prefs)
        lastSync = System.currentTimeMillis()
    }

    fun beginGitOperation(): Boolean {
        if (gitBusy) return false
        gitBusy = true
        // A previous result must not sit in the snackbar queue while a new
        // operation is already running.
        snackbar.currentSnackbarData?.dismiss()
        return true
    }

    suspend fun endGitOperation(message: String?) {
        gitSnapshot = runCatching { git.snapshot() }.getOrNull() ?: gitSnapshot
        gitBusy = false
        message?.let { snackbar.showSnackbar(it) }
        uiScope.launch { git.maintain() }
    }

    fun openBranches() {
        newBranch = ""
        uiScope.launch {
            val cached = git.branches().getOrElse {
                snackbar.showSnackbar(syncFailureMessage(it))
                return@launch
            }
            branches = cached
            branchDialog = true
            // Refresh remote refs in the background so a branch created
            // elsewhere appears without delaying the dialog.
            git.fetch()
            git.branches().onSuccess { branches = it }
        }
    }

    fun deleteBranch(name: String) {
        branchToDelete = null
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                val result = git.deleteBranch(name)
                if (result.isSuccess) {
                    branches = git.branches().getOrDefault(branches)
                    outcome = resources.getString(R.string.app_branch_deleted, name)
                } else {
                    outcome = syncFailureMessage(result.exceptionOrNull()!!)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                outcome = syncFailureMessage(error)
            } finally {
                endGitOperation(outcome)
            }
        }
    }

    fun chooseBranch(name: String, create: Boolean = false) {
        branchDialog = false
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                val result = if (create) git.createBranch(name) else git.checkout(name)
                if (result.isSuccess) {
                    store.refresh()
                    outcome = if (create) {
                        resources.getString(R.string.app_branch_created, name)
                    } else {
                        resources.getString(R.string.app_branch_switched, name)
                    }
                } else {
                    outcome = syncFailureMessage(result.exceptionOrNull()!!)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                outcome = syncFailureMessage(error)
            } finally {
                endGitOperation(outcome)
            }
        }
    }

    fun commitNow(message: String) {
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                store.flushWrites()
                outcome = git.commit(message).fold(
                    onSuccess = { resources.getString(R.string.app_commit_local) },
                    onFailure = { syncFailureMessage(it) },
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                outcome = syncFailureMessage(error)
            } finally {
                endGitOperation(outcome)
            }
        }
    }

    fun fetchNow() {
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                outcome = git.fetch().fold(
                    onSuccess = { resources.getString(R.string.app_remote_updated) },
                    onFailure = { syncFailureMessage(it) },
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                outcome = syncFailureMessage(error)
            } finally {
                endGitOperation(outcome)
            }
        }
    }

    fun pullNow() {
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                store.flushWrites()
                val pulled = git.pull()
                if (pulled.isFailure) {
                    outcome = syncFailureMessage(pulled.exceptionOrNull()!!)
                } else {
                    when (val result = pulled.getOrThrow()) {
                        is PullOutcome.Conflicts -> conflictSession = result.session
                        PullOutcome.UpToDate, PullOutcome.Updated -> {
                            store.refresh()
                            markSynced()
                            outcome = resources.getString(R.string.app_pulled)
                        }
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                outcome = syncFailureMessage(error)
            } finally {
                endGitOperation(outcome)
            }
        }
    }

    fun pushNow() {
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                val pushed = git.push()
                outcome = pushed.fold(
                    onSuccess = {
                        markSynced()
                        resources.getString(R.string.app_pushed)
                    },
                    onFailure = { syncFailureMessage(it) },
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                outcome = syncFailureMessage(error)
            } finally {
                endGitOperation(outcome)
            }
        }
    }

    fun syncNow() {
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                store.flushWrites()
                var snapshot = git.snapshot()
                if (!snapshot.remoteConfigured) {
                    outcome = resources.getString(R.string.sync_error_not_configured)
                    return@launch
                }
                if (snapshot.conflicts.isNotEmpty()) {
                    conflictSession = git.pendingSession()
                    return@launch
                }
                if (snapshot.dirtyFiles.isNotEmpty() || snapshot.unpushedCommits > 0) {
                    val fileCount = snapshot.pendingFiles
                    val fileName = snapshot.pendingFileName?.substringAfterLast('/')
                    val scopeLabel = if (fileCount == 1 && fileName != null) {
                        fileName
                    } else {
                        "$fileCount ${if (fileCount == 1) "file" else "files"}"
                    }
                    val committed = git.commit("chore(tasks): sync $scopeLabel")
                    if (committed.isFailure) {
                        outcome = syncFailureMessage(committed.exceptionOrNull()!!)
                        return@launch
                    }
                }
                val pulled = git.pull(rebase = true)
                if (pulled.isFailure) {
                    outcome = syncFailureMessage(pulled.exceptionOrNull()!!)
                    return@launch
                }
                when (val result = pulled.getOrThrow()) {
                    is PullOutcome.Conflicts -> {
                        conflictSession = result.session
                        return@launch
                    }
                    PullOutcome.UpToDate, PullOutcome.Updated -> Unit
                }
                snapshot = git.snapshot()
                if (snapshot.ahead > 0) {
                    val pushed = git.push()
                    if (pushed.isFailure) {
                        outcome = syncFailureMessage(pushed.exceptionOrNull()!!)
                        return@launch
                    }
                }
                store.refresh()
                markSynced()
                outcome = resources.getString(R.string.app_synced)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                outcome = syncFailureMessage(error)
            } finally {
                endGitOperation(outcome)
            }
        }
    }

    fun applyConflict(resolution: ConflictResolution) {
        conflictSession = null
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                val applied = git.applySession(resolution)
                if (applied.isFailure) {
                    conflictSession = git.pendingSession()
                    outcome = syncFailureMessage(applied.exceptionOrNull()!!)
                } else {
                    store.refresh()
                    outcome = resources.getString(R.string.app_conflict_resolved)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                outcome = syncFailureMessage(error)
            } finally {
                endGitOperation(outcome)
            }
        }
    }

    fun discardConflict() {
        conflictSession = null
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                outcome = git.discardSession().fold(
                    onSuccess = { resources.getString(R.string.app_conflict_discarded) },
                    onFailure = { syncFailureMessage(it) },
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                outcome = syncFailureMessage(error)
            } finally {
                endGitOperation(outcome)
            }
        }
    }

    fun exportArchive() {
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                store.flushWrites()
                val directory = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(directory, "tohru-${System.currentTimeMillis()}.zip")
                val count = store.exportArchive(file)
                pruneExports(directory, SyncSettings.readMaintenance(prefs).exportKeep)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, resources.getString(R.string.app_share_title)))
                outcome = resources.getString(R.string.app_export_done, count)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("TohruArchive", "zip export failed", error)
                outcome = resources.getString(R.string.app_export_failed)
            } finally {
                endGitOperation(outcome)
            }
        }
    }

    fun importArchive(mode: TaskArchive.MergeMode) {
        val file = pendingImport ?: return
        pendingImport = null
        if (!beginGitOperation()) return
        uiScope.launch {
            var outcome: String? = null
            try {
                val count = store.importArchive(file, mode)
                outcome = resources.getString(R.string.app_import_done, count)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("TohruArchive", "zip import failed", error)
                outcome = resources.getString(R.string.app_import_failed)
            } finally {
                file.delete()
                endGitOperation(outcome)
            }
        }
    }

    fun markSelected(done: Boolean) {
        val ids = TaskSelection.selectedIds(selection, active)
        if (ids.isEmpty()) return
        uiScope.launch { store.setDone(ids, done) }
    }

    fun deleteTasks(ids: Set<String>) {
        if (ids.isEmpty()) return
        uiScope.launch {
            store.removeAll(ids)
            exitEdit()
            snackbar.showSnackbar(
                resources.getString(R.string.app_deleted_count, ids.size),
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
        }
    }

    fun archiveTasks(ids: Set<String>) {
        if (ids.isEmpty()) return
        uiScope.launch {
            store.setArchived(ids, true)
            exitEdit()
            val result = snackbar.showSnackbar(
                message = if (ids.size == 1) {
                    resources.getString(R.string.app_archived)
                } else {
                    resources.getString(R.string.app_archived_count, ids.size)
                },
                actionLabel = resources.getString(R.string.app_undo),
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) store.setArchived(ids, false)
        }
    }

    val toastBottomPadding = if (screen is Screen.Main && !editMode) 76.dp else 12.dp

    val textMenuState = remember { AppTextContextMenuState() }
    TohruTheme(dynamic = dynamic, mode = themeMode) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(modifier = Modifier.fillMaxSize()) {
            when (val current = screen) {
                is Screen.Main -> MainScreen(
                    tasks = tasks,
                    readmes = readmes,
                    onOpenReadme = { screen = Screen.Readme(it) },
                    order = order,
                    query = query,
                    onQueryChange = { query = it },
                    prefixFilter = prefixFilterList.toSet(),
                    notePrefixes = notePrefixes,
                    onTogglePrefix = { prefix ->
                        prefixFilterList = if (prefix in prefixFilterList) {
                            prefixFilterList - prefix
                        } else {
                            prefixFilterList + prefix
                        }
                    },
                    collapsed = collapsed,
                    onToggleCollapse = ::toggleCollapsed,
                    editMode = editMode,
                    selection = selection,
                    page = page,
                    onPageChange = { page = it },
                    onToggleDone = { id -> uiScope.launch { store.toggle(id) } },
                    onEditTask = { editingTaskId = it },
                    onOpenDetail = { screen = Screen.Detail(it) },
                    onAdd = ::saveTitle,
                    onOpenArchive = { screen = Screen.Archive },
                    onOpenSettings = { screen = Screen.Settings },
                    onEnterEdit = { editMode = true; selectionList = emptyList() },
                    onEnterEditNode = { node ->
                        editMode = true
                        selectionList = TaskSelection.idsForNode(node, active, SelectionScope.OPEN).toList()
                    },
                    onExitEdit = ::exitEdit,
                    onToggleNode = { node ->
                        val ids = TaskSelection.idsForNode(node, active, SelectionScope.OPEN)
                        selectionList = if (ids.isNotEmpty() && selection.containsAll(ids)) {
                            (selection - ids).toList()
                        } else {
                            (selection + ids).toList()
                        }
                    },
                    onSelectAll = { ids -> selectionList = ids.toList() },
                    onClearSelection = { selectionList = emptyList() },
                    onArchiveSelected = { archiveTasks(selection) },
                    onDeleteSelected = ::deleteTasks,
                    onMarkSelectedDone = { markSelected(true) },
                    onMarkSelectedUndone = { markSelected(false) },
                    onMove = { siblingKey, movedId, beforeId ->
                        uiScope.launch { store.move(siblingKey, movedId, beforeId) }
                    },
                    gitSnapshot = gitSnapshot,
                    gitBusy = gitBusy,
                    onSync = ::syncNow,
                    gitPanel = {
                        GitPanel(
                            snapshot = gitSnapshot,
                            busy = gitBusy,
                            lastSyncLabel = lastSyncLabel,
                            onSync = ::syncNow,
                            onChooseBranch = ::openBranches,
                            onCommit = { commitDialog = true },
                            onFetch = ::fetchNow,
                            onPull = ::pullNow,
                            onPush = ::pushNow,
                            onResolve = {
                                uiScope.launch {
                                    conflictSession = git.pendingSession()
                                }
                            },
                            onShowDirty = { screen = Screen.GitDirty },
                            onHistory = { screen = Screen.GitHistory },
                            onSettings = { screen = Screen.Settings },
                            onExport = ::exportArchive,
                            onImport = { importLauncher.launch(arrayOf("application/zip")) },
                        )
                    },
                    snackbar = snackbar,
                )
                is Screen.Detail -> AppTextContextMenuHost(textMenuState) {
                    TaskDetailScreen(
                        taskId = current.id,
                        task = tasks.firstOrNull { it.id == current.id },
                        tasks = tasks,
                        taskLinks = taskLinks,
                        onSave = { draft -> uiScope.launch { store.save(draft) } },
                        onOpenTaskLink = openTaskLink,
                        onArchive = { id, draft ->
                            uiScope.launch {
                                draft?.let { store.save(it) }
                                store.setArchived(listOf(id), true)
                                val result = snackbar.showSnackbar(
                                    message = resources.getString(R.string.app_archived),
                                    actionLabel = resources.getString(R.string.app_undo),
                                    withDismissAction = true,
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    store.setArchived(listOf(id), false)
                                }
                            }
                        },
                        onExit = { screen = Screen.Main },
                    )
                }
                is Screen.Archive -> ArchiveScreen(
                    tasks = tasks,
                    collapsed = collapsed,
                    onToggleCollapse = ::toggleCollapsed,
                    onRestore = { ids ->
                        uiScope.launch {
                            store.setArchived(ids, false)
                            snackbar.showSnackbar(
                                resources.getString(R.string.app_restored_count, ids.size),
                                withDismissAction = true,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                    onDelete = { ids ->
                        uiScope.launch {
                            store.removeAll(ids)
                            snackbar.showSnackbar(
                                resources.getString(R.string.app_deleted_count, ids.size),
                                withDismissAction = true,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                    onBack = { screen = Screen.Main },
                )
                is Screen.GitHistory -> GitHistoryScreen(
                    git = git,
                    onBack = { screen = Screen.Main },
                )
                is Screen.GitDirty -> DirtyFilesScreen(
                    files = gitSnapshot?.dirtyFiles.orEmpty(),
                    kinds = gitSnapshot?.dirtyKinds.orEmpty(),
                    onBack = { screen = Screen.Main },
                )
                is Screen.Readme -> ReadmeDetailScreen(
                    segments = current.segments,
                    readme = readmes.firstOrNull { it.segments == current.segments },
                    tasks = tasks,
                    taskLinks = taskLinks,
                    onSave = { segments, body ->
                        uiScope.launch { store.saveReadme(segments, body) }
                    },
                    onOpenTaskLink = openTaskLink,
                    onExit = { screen = Screen.Main },
                )
                is Screen.Settings -> SettingsScreen(
                    vault = vault,
                    dynamic = dynamic,
                    onDynamicChange = onDynamicChange,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    haptics = haptics,
                    onHapticsChange = onHapticsChange,
                    snackbar = snackbar,
                    onResetOrder = {
                        uiScope.launch {
                            store.clearAllOrder()
                            snackbar.showSnackbar(
                                resources.getString(R.string.app_order_reset),
                                withDismissAction = true,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                    inboxPrefixes = inboxPrefixes,
                    notePrefixes = notePrefixes,
                    onTaskConfigChange = { inbox, note ->
                        inboxPrefixes = inbox
                        notePrefixes = note
                        TaskParser.configureInbox(inbox)
                        uiScope.launch {
                            try {
                                store.refresh()
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.e("TohruTaskStore", "task config refresh failed", error)
                                snackbar.showSnackbar(resources.getString(R.string.app_task_data_error))
                            }
                        }
                    },
                    onBack = { screen = Screen.Main },
                )
            }

            pendingImport?.let { file ->
                AlertDialog(
                    onDismissRequest = { pendingImport = null; file.delete() },
                    title = { Text(stringResource(R.string.app_import_title)) },
                    text = { Text(stringResource(R.string.app_import_message)) },
                    confirmButton = {
                        TextButton(onClick = { importArchive(TaskArchive.MergeMode.OVERWRITE) }) {
                            Text(stringResource(R.string.app_import_overwrite))
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { importArchive(TaskArchive.MergeMode.SKIP) }) {
                                Text(stringResource(R.string.app_import_skip))
                            }
                            TextButton(onClick = { importArchive(TaskArchive.MergeMode.DUPLICATE) }) {
                                Text(stringResource(R.string.app_import_duplicate))
                            }
                        }
                    },
                )
            }

            if (branchDialog) {
                AlertDialog(
                    onDismissRequest = { branchDialog = false },
                    title = { Text(stringResource(R.string.app_branch_title)) },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            OutlinedTextField(
                                value = newBranch,
                                onValueChange = { newBranch = it },
                                label = { Text(stringResource(R.string.app_branch_new_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                onClick = { chooseBranch(newBranch.trim(), create = true) },
                                enabled = newBranch.isNotBlank(),
                            ) {
                                Text(stringResource(R.string.app_branch_create))
                            }
                            if (branches.isEmpty()) {
                                Text(stringResource(R.string.app_branch_empty))
                            } else {
                                val currentSuffix = stringResource(R.string.app_branch_current)
                                val remoteSuffix = stringResource(R.string.app_branch_remote)
                                val staleSuffix = stringResource(R.string.app_branch_stale)
                                branches.forEach { branch ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { chooseBranch(branch.name) }) {
                                            Text(
                                                buildString {
                                                    append(branch.name)
                                                    if (branch.current) append(currentSuffix)
                                                    if (branch.remote) append(remoteSuffix)
                                                    if (branch.stale) append(staleSuffix)
                                                },
                                            )
                                        }
                                        if (!branch.remote && !branch.current) {
                                            TextButton(onClick = { branchToDelete = branch.name }) {
                                                Text(stringResource(R.string.app_delete))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { branchDialog = false }) { Text(stringResource(R.string.app_close)) }
                    },
                )
            }

            val deleting = branchToDelete
            if (deleting != null) {
                AlertDialog(
                    onDismissRequest = { branchToDelete = null },
                    title = { Text(stringResource(R.string.app_branch_delete_title)) },
                    text = { Text(stringResource(R.string.app_branch_delete_message, deleting)) },
                    confirmButton = {
                        TextButton(onClick = { deleteBranch(deleting) }) { Text(stringResource(R.string.app_delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { branchToDelete = null }) { Text(stringResource(R.string.app_cancel)) }
                    },
                )
            }

            if (commitDialog) {
                CommitMessageDialog(
                    fileCount = gitSnapshot?.pendingFiles ?: 0,
                    fileName = gitSnapshot?.pendingFileName,
                    onDismiss = { commitDialog = false },
                    onCommit = { message ->
                        commitDialog = false
                        commitNow(message)
                    },
                )
            }

            conflictSession?.let { session ->
                ConflictScreen(
                    session = session,
                    onApply = ::applyConflict,
                    onDiscard = ::discardConflict,
                    onSaveDrafts = { drafts -> git.saveDrafts(drafts) },
                )
            }

            hostKeyRequest?.let { request ->
                AlertDialog(
                    onDismissRequest = { prompter.answerHostKey(false) },
                    title = { Text(stringResource(R.string.app_ssh_title)) },
                    text = { Text(request.message) },
                    confirmButton = {
                        TextButton(onClick = { prompter.answerHostKey(true) }) { Text(stringResource(R.string.app_ssh_trust)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { prompter.answerHostKey(false) }) { Text(stringResource(R.string.app_cancel)) }
                    },
                )
            }

            val previewTask = previewTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
            if (previewTask != null) {
                TaskPreviewSheet(
                    task = previewTask,
                    onDismiss = { previewTaskId = null },
                    onTaskLink = openTaskLink,
                    taskExists = { ref -> taskLinks.idFor(ref) != null },
                )
            }

            val editingTask = tasks.firstOrNull { it.id == editingTaskId }
            if (editingTask != null) {
                TaskEditSheet(
                    task = editingTask,
                    onSave = { draft -> uiScope.launch { store.save(draft) } },
                    onDismiss = { editingTaskId = null },
                )
            }

            TohruSnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = toastBottomPadding)
                    .zIndex(10f),
            )
            }
        }
    }
}

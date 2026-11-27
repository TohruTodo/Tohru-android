package io.github.tohrutodo.tohru.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.sync.ConflictChoice
import io.github.tohrutodo.tohru.sync.ConflictFile
import io.github.tohrutodo.tohru.sync.ConflictResolution
import io.github.tohrutodo.tohru.sync.ConflictSession
import io.github.tohrutodo.tohru.sync.ConflictSide
import io.github.tohrutodo.tohru.sync.ConflictVersion
import io.github.tohrutodo.tohru.sync.DiffKind
import io.github.tohrutodo.tohru.sync.TextDiff
import io.github.tohrutodo.tohru.sync.TaskMerge

@Composable
fun ConflictScreen(
    session: ConflictSession,
    onApply: (ConflictResolution) -> Unit,
    onDiscard: () -> Unit,
    onSaveDrafts: suspend (Map<String, ConflictChoice>) -> Unit,
) {
    val choices = remember(session) {
        mutableStateMapOf<String, ConflictChoice>().apply {
            session.files.forEach { file ->
                put(file.path, session.drafts[file.path] ?: ConflictChoice.Keep(file.suggested))
            }
        }
    }
    val draftSnapshot = choices.toMap()
    LaunchedEffect(draftSnapshot) {
        onSaveDrafts(draftSnapshot)
    }
    val expanded = remember(session) { mutableStateMapOf<String, Boolean>() }
    var editingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errors = session.files.mapNotNull { file ->
        val custom = choices[file.path] as? ConflictChoice.Custom ?: return@mapNotNull null
        taskContentError(file.path, custom.text)?.let { file.path to it }
    }.toMap()

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        scope.launch { onSaveDrafts(choices.toMap()) }
    }

    val file = session.files.firstOrNull { it.path == editingPath }
    if (file != null) {
        ConflictEditor(
            file = file,
            initial = choices[file.path] ?: ConflictChoice.Keep(file.suggested),
            onCancel = { editingPath = null },
            onDraft = { text ->
                choices[file.path] = ConflictChoice.Custom(text)
                scope.launch { onSaveDrafts(choices.toMap()) }
            },
        )
        return
    }

    BackHandler { confirmDiscard = true }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TohruTopBar(title = stringResource(R.string.git_conflict_title), onBack = { confirmDiscard = true }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { confirmDiscard = true },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.git_cancel)) }
                    Button(
                        enabled = errors.isEmpty(),
                        onClick = { onApply(ConflictResolution(choices.toMap())) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.git_continue)) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = pluralStringResource(R.plurals.git_conflict_summary, session.files.size, session.files.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(session.files, key = { it.path }) { conflict ->
                ConflictCard(
                    file = conflict,
                    choice = choices[conflict.path] ?: ConflictChoice.Keep(conflict.suggested),
                    expanded = expanded[conflict.path] == true,
                    onChoice = { choices[conflict.path] = it },
                    onToggleDiff = { expanded[conflict.path] = expanded[conflict.path] != true },
                    onEdit = { editingPath = conflict.path },
                    error = errors[conflict.path],
                )
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.git_conflict_discard_title)) },
            text = { Text(stringResource(R.string.git_conflict_discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDiscard()
                }) { Text(stringResource(R.string.git_conflict_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.git_conflict_back)) }
            },
        )
    }
}

@Composable
private fun ConflictCard(
    file: ConflictFile,
    choice: ConflictChoice,
    expanded: Boolean,
    onChoice: (ConflictChoice) -> Unit,
    onToggleDiff: () -> Unit,
    onEdit: () -> Unit,
    error: Int?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = file.local.title ?: file.remote.title ?: file.path,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = file.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SideOption(
                label = stringResource(R.string.git_conflict_local),
                version = file.local,
                selected = choice == ConflictChoice.Keep(ConflictSide.LOCAL),
                onSelect = { onChoice(ConflictChoice.Keep(ConflictSide.LOCAL)) },
            )
            SideOption(
                label = stringResource(R.string.git_conflict_remote),
                version = file.remote,
                selected = choice == ConflictChoice.Keep(ConflictSide.REMOTE),
                onSelect = { onChoice(ConflictChoice.Keep(ConflictSide.REMOTE)) },
            )
            if (choice is ConflictChoice.Custom) {
                Text(
                    text = stringResource(R.string.git_conflict_custom, choice.text.lines().size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                error?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToggleDiff) {
                    Text(stringResource(if (expanded) R.string.git_conflict_hide_diff else R.string.git_conflict_show_diff))
                }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.git_conflict_edit)) }
            }
            if (expanded) {
                DiffBox(local = file.local.text, remote = file.remote.text)
            }
        }
    }
}

@Composable
private fun SideOption(
    label: String,
    version: ConflictVersion,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (version.exists) label else stringResource(R.string.git_conflict_deleted_suffix, label),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (version.exists) {
                Text(
                    text = stringResource(R.string.git_conflict_line_count, version.lineCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (version.preview.isNotBlank()) {
                    Text(
                        text = version.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffBox(local: String, remote: String) {
    val lines = remember(local, remote) { TextDiff.unified(local, remote) }
    if (lines.isEmpty()) {
        Text(
            text = stringResource(R.string.git_conflict_no_diff),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val colors = rememberDiffColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            lines.forEach { line ->
                val prefix = when (line.kind) {
                    DiffKind.REMOVED -> "- "
                    DiffKind.ADDED -> "+ "
                    DiffKind.CONTEXT -> "  "
                }
                val kind = when (line.kind) {
                    DiffKind.REMOVED -> DiffLineKind.Removed
                    DiffKind.ADDED -> DiffLineKind.Added
                    DiffKind.CONTEXT -> DiffLineKind.Context
                }
                DiffLine(
                    text = prefix + line.text,
                    kind = kind,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ConflictEditor(
    file: ConflictFile,
    initial: ConflictChoice,
    onCancel: () -> Unit,
    onDraft: (String) -> Unit,
) {
    val initialText = when (initial) {
        is ConflictChoice.Keep ->
            if (initial.side == ConflictSide.LOCAL) file.local.text else file.remote.text
        is ConflictChoice.Custom -> initial.text
    }
    var text by remember(file.path) { mutableStateOf(initialText) }
    val error = remember(file.path, text) { taskContentError(file.path, text) }
    val latestDraft by rememberUpdatedState(onDraft)
    val latestText by rememberUpdatedState(text)

    fun commitDraft() {
        latestDraft(latestText)
    }

    LaunchedEffect(text) {
        if (text == initialText) return@LaunchedEffect
        delay(600)
        commitDraft()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { commitDraft() }
    DisposableEffect(Unit) {
        onDispose { commitDraft() }
    }

    BackHandler {
        commitDraft()
        onCancel()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TohruTopBar(
                title = stringResource(R.string.git_conflict_editor_title),
                onBack = {
                    commitDraft()
                    onCancel()
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = file.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                placeholder = { Text(stringResource(R.string.git_conflict_editor_placeholder)) },
            )
            error?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun taskContentError(path: String, text: String): Int? =
    TaskMerge.invalidReasonRes(path, text)

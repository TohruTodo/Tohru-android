package io.github.tohrutodo.tohru.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.util.Log
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.sync.GitSnapshot
import io.github.tohrutodo.tohru.sync.syncFailureRes

// Compact Git state card above the manage board. Actions are intentionally
// explicit: only Sync combines commit, pull, and push.

@Composable
fun GitPanel(
    snapshot: GitSnapshot?,
    busy: Boolean,
    lastSyncLabel: String?,
    onSync: () -> Unit,
    onChooseBranch: () -> Unit,
    onShowDirty: () -> Unit,
    onCommit: () -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onResolve: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = snapshot ?: GitSnapshot()
    LaunchedEffect(state.error) {
        state.error?.cause?.let { Log.e("TohruSync", "git snapshot failed", it) }
    }
    val statusColor = when {
        busy -> MaterialTheme.colorScheme.primary
        state.error != null -> MaterialTheme.colorScheme.error
        state.conflicts.isNotEmpty() -> MaterialTheme.colorScheme.error
        state.dirtyFiles.isNotEmpty() -> MaterialTheme.colorScheme.tertiary
        state.unpushedCommits > 0 -> MaterialTheme.colorScheme.primary
        state.ahead > 0 || state.behind > 0 -> MaterialTheme.colorScheme.primary
        state.remoteConfigured -> Color(0xFF386A20)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when {
        busy -> stringResource(R.string.git_status_syncing)
        state.error != null -> stringResource(R.string.git_status_error)
        state.conflicts.isNotEmpty() -> pluralStringResource(R.plurals.git_status_conflicts, state.conflicts.size, state.conflicts.size)
        state.dirtyFiles.isNotEmpty() -> pluralStringResource(R.plurals.git_status_dirty, state.dirtyFiles.size, state.dirtyFiles.size)
        state.unpushedCommits > 0 -> pluralStringResource(R.plurals.git_status_unpushed, state.unpushedCommits, state.unpushedCommits)
        state.ahead > 0 && state.behind > 0 ->
            stringResource(R.string.git_status_diverged, state.ahead, state.behind)
        state.ahead > 0 -> pluralStringResource(R.plurals.git_status_ahead, state.ahead, state.ahead)
        state.behind > 0 -> pluralStringResource(R.plurals.git_status_behind, state.behind, state.behind)
        state.remoteConfigured -> stringResource(R.string.git_status_synced)
        else -> stringResource(R.string.git_status_no_remote)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.git_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = stringResource(R.string.git_status_line, statusText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    modifier = Modifier.weight(1f),
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.branch?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                state.upstream?.let { Text(stringResource(R.string.git_upstream, it), style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = onChooseBranch, enabled = !busy) {
                    Text(stringResource(R.string.git_change_branch), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.dirtyFiles.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.git_local_changes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TohruBadge(
                        stringResource(R.string.git_changes_new, state.dirtyFiles.size),
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.clickable(onClick = onShowDirty),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            stringResource(R.string.git_view_all),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            state.lastCommit?.let { commit ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(ClusterGap)) {
                    Text(
                        commit.shortHash,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        commit.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        modifier = Modifier
                            .weight(1f)
                            .alignByBaseline(),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ClusterGap),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(InlineGap),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_person),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            commit.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(InlineGap),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_schedule),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            relativeTime(commit.time, LocalNowMillis.current, LocalResources.current),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.error?.let {
                Text(
                    stringResource(syncFailureRes(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            lastSyncLabel?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onCommit,
                    enabled = !busy &&
                        (state.dirtyFiles.isNotEmpty() || state.unpushedCommits > 0) &&
                        state.conflicts.isEmpty(),
                ) {
                    Text(stringResource(R.string.git_action_commit))
                }
                TextButton(onClick = onFetch, enabled = !busy && state.remoteConfigured) { Text(stringResource(R.string.git_action_fetch)) }
                TextButton(
                    onClick = onPull,
                    enabled = !busy && state.remoteConfigured && state.dirtyFiles.isEmpty() && state.conflicts.isEmpty(),
                ) { Text(stringResource(R.string.git_action_pull)) }
                TextButton(
                    onClick = onPush,
                    enabled = !busy && state.remoteConfigured && state.dirtyFiles.isEmpty() &&
                        state.ahead > 0 && state.conflicts.isEmpty(),
                ) { Text(stringResource(R.string.git_action_push)) }
            }
            if (state.conflicts.isNotEmpty()) {
                TextButton(onClick = onResolve, enabled = !busy) { Text(stringResource(R.string.git_resolve_conflicts)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onHistory) { Text(stringResource(R.string.git_history)) }
                TextButton(onClick = onExport, enabled = !busy) { Text(stringResource(R.string.git_export_zip)) }
                TextButton(onClick = onImport, enabled = !busy) { Text(stringResource(R.string.git_import_zip)) }
                if (!state.remoteConfigured) {
                    TextButton(onClick = onSettings) { Text(stringResource(R.string.git_open_settings)) }
                }
                TextButton(
                    onClick = onSync,
                    enabled = !busy && state.remoteConfigured && state.conflicts.isEmpty(),
                ) { Text(stringResource(R.string.git_action_sync)) }
            }
        }
    }
}

@Composable
fun CommitMessageDialog(
    fileCount: Int,
    fileName: String? = null,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
) {
    val name = fileName?.substringAfterLast('/')
    val scope = if (fileCount == 1 && name != null) {
        name
    } else {
        stringResource(
            if (fileCount == 1) R.string.git_commit_scope_one else R.string.git_commit_scope_many,
            fileCount,
        )
    }
    val defaultMessage = stringResource(R.string.git_commit_default_message, scope)
    var message by remember {
        mutableStateOf(defaultMessage)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_commit_dialog_title)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text(stringResource(R.string.git_commit_message_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = message.isNotBlank(),
                onClick = { onCommit(message.trim()) },
            ) { Text(stringResource(R.string.git_action_commit)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.git_cancel)) } },
    )
}

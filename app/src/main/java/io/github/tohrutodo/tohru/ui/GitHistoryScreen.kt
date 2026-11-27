package io.github.tohrutodo.tohru.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.util.Log
import kotlinx.coroutines.launch
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.sync.GitCommitDetail
import io.github.tohrutodo.tohru.sync.GitCommitSummary
import io.github.tohrutodo.tohru.sync.GitFileChange
import io.github.tohrutodo.tohru.sync.GitSync

// Read-only commit browser. The phone layout shows a list first, then a
// detail/diff screen; tablet side-by-side can be added later without changing
// the engine.
@Composable
fun GitHistoryScreen(
    git: GitSync,
    onBack: () -> Unit,
) {
    var commits by remember { mutableStateOf(emptyList<GitCommitSummary>()) }
    var loading by remember { mutableStateOf(true) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var allBranches by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<GitCommitSummary?>(null) }

    LaunchedEffect(allBranches, query) {
        loading = true
        git.history(limit = 50, allBranches = allBranches, query = query).fold(
            onSuccess = {
                commits = it
                errorRes = null
            },
            onFailure = {
                logGitHistoryError(it)
                errorRes = R.string.git_history_error
            },
        )
        loading = false
    }

    val current = selected
    if (current != null) {
        CommitDetailScreen(
            git = git,
            summary = current,
            onBack = { selected = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TohruTopBar(
                title = stringResource(R.string.git_history_title),
                onBack = onBack,
                actions = {
                    TextButton(onClick = { allBranches = !allBranches }) {
                        Text(stringResource(if (allBranches) R.string.git_history_filter_all else R.string.git_history_filter_branch))
                    }
                },
            )
        },
    ) { padding ->
        val historyErrorRes = errorRes
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(value = query, onValueChange = { query = it })
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                historyErrorRes != null -> Text(stringResource(historyErrorRes), color = MaterialTheme.colorScheme.error)
                commits.isEmpty() -> Text(
                    stringResource(R.string.git_history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(commits, key = { it.hash }) { commit ->
                        CommitRow(
                            commit = commit,
                            onClick = { selected = commit },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(
    commit: GitCommitSummary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(10.dp),
                shape = CircleShape,
                color = if ("HEAD" in commit.refs) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            ) {}
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ClusterGap),
            ) {
                Text(
                    commit.shortHash,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    commit.subject,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            if (commit.refs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    commit.refs.take(3).forEach { ref ->
                        Surface(
                            color = if (ref == "HEAD") MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                ref,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
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
                        style = MaterialTheme.typography.labelSmall,
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun logGitHistoryError(error: Throwable) {
    Log.e("TohruGitHistory", "git history failed", error)
}

@Composable
private fun CommitDetailScreen(
    git: GitSync,
    summary: GitCommitSummary,
    onBack: () -> Unit,
) {
    var detail by remember { mutableStateOf<GitCommitDetail?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var diff by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(summary.hash) {
        git.commitDetail(summary.hash).fold(
            onSuccess = { detail = it; errorRes = null },
            onFailure = {
                logGitHistoryError(it)
                errorRes = R.string.git_history_commit_error
            },
        )
    }

    val selectedDiff = diff
    if (selectedDiff != null) {
        DiffScreen(
            title = selectedDiff.first,
            text = selectedDiff.second,
            onBack = { diff = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TohruTopBar(
                title = summary.shortHash,
                onBack = onBack,
            )
        },
    ) { padding ->
        val current = detail
        val detailErrorRes = errorRes
        if (detailErrorRes != null) {
            Text(stringResource(detailErrorRes), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        if (current == null) {
            CircularProgressIndicator(modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(current.message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_person),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${current.author} <${current.authorEmail}>",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_schedule),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        relativeTime(current.time, LocalNowMillis.current, LocalResources.current),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (current.parents.isNotEmpty()) {
                Text(
                    stringResource(R.string.git_history_parent, current.parents.joinToString { it.take(7) }),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            HorizontalDivider()
            current.files.forEach { file ->
                FileRow(file) {
                    scope.launch {
                        git.commitDiff(current.hash, file.path).fold(
                            onSuccess = { text -> diff = file.path to text },
                            onFailure = {
                                logGitHistoryError(it)
                                errorRes = R.string.git_history_diff_error
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: GitFileChange, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(file.status.take(1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
        Text(file.path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (!file.binary) {
            Text(stringResource(R.string.git_history_stat, file.additions, file.deletions), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DiffScreen(title: String, text: String, onBack: () -> Unit) {
    val colors = rememberDiffColors()
    val noDiff = stringResource(R.string.git_history_no_diff)
    val blank = text.isBlank()
    val lines = remember(text, noDiff) {
        if (text.isBlank()) listOf(noDiff) else text.lines()
    }
    Scaffold(
        topBar = { TohruTopBar(title = title, onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            lines.forEach { line ->
                DiffLine(
                    text = line,
                    kind = if (blank) DiffLineKind.Context else diffLineKind(line),
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

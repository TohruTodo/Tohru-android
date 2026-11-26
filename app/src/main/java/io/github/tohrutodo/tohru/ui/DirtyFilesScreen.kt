package io.github.tohrutodo.tohru.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.sync.DirtyKind

// Read-only list of every dirty path, styled like the commit browser. Each
// row keeps the file name first and shows its directory and status below.
@Composable
fun DirtyFilesScreen(
    files: List<String>,
    kinds: Map<String, DirtyKind>,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val visible = remember(files, needle) {
        if (needle.isEmpty()) files else files.filter { it.contains(needle, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TohruTopBar(
                title = stringResource(R.string.git_local_changes),
                onBack = onBack,
                actions = {
                    Text(
                        if (needle.isEmpty()) stringResource(R.string.git_dirty_count_total, files.size)
                        else stringResource(R.string.git_dirty_count_filtered, visible.size, files.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(value = query, onValueChange = { query = it })
            when {
                files.isEmpty() -> Text(
                    stringResource(R.string.git_dirty_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
                visible.isEmpty() -> Text(
                    stringResource(R.string.git_dirty_no_result),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visible, key = { it }) { path ->
                        DirtyFileRow(path = path, kind = kinds[path])
                    }
                }
            }
        }
    }
}

@Composable
private fun DirtyFileRow(path: String, kind: DirtyKind?) {
    val name = path.substringAfterLast('/')
    val directory = path.substringBeforeLast('/', "")
    val dotColor = when (kind) {
        DirtyKind.MODIFIED -> MaterialTheme.colorScheme.primary
        DirtyKind.ADDED, DirtyKind.UNTRACKED -> MaterialTheme.colorScheme.tertiary
        DirtyKind.MOVED -> MaterialTheme.colorScheme.secondary
        DirtyKind.REMOVED, DirtyKind.CONFLICTING -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = dotColor,
        ) {}
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    directory,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                kind?.let { DirtyKindBadge(it) }
            }
        }
    }
}

package io.github.tohrutodo.tohru.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.sync.DirtyKind

@StringRes
internal fun DirtyKind.labelRes(): Int = when (this) {
    DirtyKind.MODIFIED -> R.string.git_dirty_kind_modified
    DirtyKind.ADDED -> R.string.git_dirty_kind_added
    DirtyKind.REMOVED -> R.string.git_dirty_kind_removed
    DirtyKind.UNTRACKED -> R.string.git_dirty_kind_untracked
    DirtyKind.MOVED -> R.string.git_dirty_kind_moved
    DirtyKind.CONFLICTING -> R.string.git_dirty_kind_conflicting
}

@Composable
internal fun DirtyKind.containerColor(): Color = when (this) {
    DirtyKind.MODIFIED -> MaterialTheme.colorScheme.primaryContainer
    DirtyKind.ADDED -> MaterialTheme.colorScheme.secondaryContainer
    DirtyKind.REMOVED -> MaterialTheme.colorScheme.errorContainer
    DirtyKind.UNTRACKED -> MaterialTheme.colorScheme.tertiaryContainer
    DirtyKind.MOVED -> MaterialTheme.colorScheme.secondaryContainer
    DirtyKind.CONFLICTING -> MaterialTheme.colorScheme.errorContainer
}

@Composable
internal fun DirtyKindBadge(kind: DirtyKind, count: Int? = null) {
    val label = stringResource(kind.labelRes())
    TohruBadge(
        text = if (count == null) label else "$label $count",
        container = kind.containerColor(),
    )
}

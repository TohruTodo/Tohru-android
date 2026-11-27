package io.github.tohrutodo.tohru.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// Diff colors are fixed semantic pairs instead of theme accents, so added and
// removed lines stay distinguishable in every palette. Bands are separate
// colors because they sit on the app surface instead of a container.
internal data class DiffColors(
    val added: Color,
    val addedBand: Color,
    val removed: Color,
    val removedBand: Color,
    val hunk: Color,
    val header: Color,
    val context: Color,
)

internal fun diffColors(
    surface: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    accent: Color,
): DiffColors = if (surface.luminance() < 0.5f) {
    DiffColors(
        added = Color(0xFF8FE3AC),
        addedBand = Color(0xFF17311F),
        removed = Color(0xFFFFA8A2),
        removedBand = Color(0xFF45181B),
        hunk = accent,
        header = onSurfaceVariant,
        context = onSurface,
    )
} else {
    DiffColors(
        added = Color(0xFF176033),
        addedBand = Color(0xFFDCEFE2),
        removed = Color(0xFFA3231F),
        removedBand = Color(0xFFFBE1DE),
        hunk = accent,
        header = onSurfaceVariant,
        context = onSurface,
    )
}

@Composable
internal fun rememberDiffColors(): DiffColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        diffColors(scheme.surface, scheme.onSurface, scheme.onSurfaceVariant, scheme.primary)
    }
}

internal enum class DiffLineKind { Added, Removed, Hunk, Header, Context }

internal fun diffLineKind(line: String): DiffLineKind = when {
    line.startsWith("+++") || line.startsWith("---") -> DiffLineKind.Header
    line.startsWith("@@") -> DiffLineKind.Hunk
    line.startsWith("diff ") ||
        line.startsWith("index ") ||
        line.startsWith("new file") ||
        line.startsWith("deleted file") ||
        line.startsWith("similarity ") ||
        line.startsWith("rename ") -> DiffLineKind.Header
    line.startsWith("+") -> DiffLineKind.Added
    line.startsWith("-") -> DiffLineKind.Removed
    else -> DiffLineKind.Context
}

// One diff line with a band that can span the whole row and wrap long text.
@Composable
internal fun DiffLine(
    text: String,
    kind: DiffLineKind,
    colors: DiffColors,
    modifier: Modifier = Modifier,
) {
    val band = when (kind) {
        DiffLineKind.Added -> colors.addedBand
        DiffLineKind.Removed -> colors.removedBand
        DiffLineKind.Hunk, DiffLineKind.Header, DiffLineKind.Context -> Color.Transparent
    }
    val color = when (kind) {
        DiffLineKind.Added -> colors.added
        DiffLineKind.Removed -> colors.removed
        DiffLineKind.Hunk -> colors.hunk
        DiffLineKind.Header -> colors.header
        DiffLineKind.Context -> colors.context
    }
    Row(
        modifier = modifier
            .background(band)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            minLines = 1,
        )
    }
}

package io.github.tohrutodo.tohru.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.tohrutodo.tohru.R

// Circular selection control: outline when off, filled with a check or a dash
// when on or partial. The 48dp box keeps the touch target square.
@Composable
fun RoundCheckbox(
    state: ToggleableState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val filled = state != ToggleableState.Off
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .triStateToggleable(
                state = state,
                role = Role.Checkbox,
                enabled = enabled,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .clip(CircleShape)
                .background(if (filled) colors.primary else Color.Transparent)
                .border(
                    width = 2.dp,
                    color = if (filled) colors.primary else colors.onSurfaceVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (filled) {
                Icon(
                    painter = painterResource(
                        if (state == ToggleableState.On) R.drawable.ic_check else R.drawable.ic_minus,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.onPrimary,
                )
            }
        }
    }
}

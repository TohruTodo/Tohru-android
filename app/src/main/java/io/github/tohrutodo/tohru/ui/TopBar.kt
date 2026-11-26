package io.github.tohrutodo.tohru.ui

import androidx.compose.ui.res.stringResource
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.sync.GitSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TohruTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    navIcon: Int = R.drawable.ic_arrow_back,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(navIcon),
                        contentDescription = stringResource(R.string.ui_back),
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
fun TohruIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(painter = painterResource(icon), contentDescription = description)
    }
}


@Composable
fun SyncTopBarButton(
    snapshot: GitSnapshot?,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val state = snapshot ?: GitSnapshot()
    val targetColor = when {
        state.error != null || state.conflicts.isNotEmpty() -> Color(0xFFBA1A1A)
        state.dirtyFiles.isNotEmpty() -> Color(0xFFB26A00)
        state.ahead > 0 || state.behind > 0 -> MaterialTheme.colorScheme.primary
        state.remoteConfigured -> Color(0xFF386A20)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 140),
        label = "sync-color",
    )
    val angle = if (busy) {
        val transition = rememberInfiniteTransition(label = "sync")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sync-angle",
        )
        value
    } else {
        0f
    }
    BadgedBox(
        badge = {
            if (state.dirtyFiles.isNotEmpty() || state.ahead > 0 || state.behind > 0 ||
                state.conflicts.isNotEmpty() || state.error != null
            ) {
                Badge(containerColor = color)
            }
        },
    ) {
        IconButton(onClick = onClick, enabled = !busy) {
            Icon(
                painter = painterResource(R.drawable.ic_sync),
                contentDescription = stringResource(R.string.ui_sync_git),
                tint = color,
                modifier = Modifier.graphicsLayer { rotationZ = angle },
            )
        }
    }
}

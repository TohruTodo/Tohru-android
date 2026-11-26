package io.github.tohrutodo.tohru.ui

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.delay
import io.github.tohrutodo.tohru.task.Task
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import io.github.tohrutodo.tohru.R

// Snackbars dismiss on their own and can be swiped away; the archive undo
// action stays available while the bar is visible.
@Composable
fun TohruSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        DismissibleSnackbar(data)
    }
}

@Composable
private fun DismissibleSnackbar(data: SnackbarData) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            data.dismiss()
        }
    }
    val forcedDismiss = remember(data) { ForcedDismissSnackbarData(data) }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {},
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
    ) {
        Snackbar(
            snackbarData = forcedDismiss,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .clickable { data.dismiss() },
        )
    }
}

// Toast-like bars always expose the same close action, including plain
// messages that were queued without one.
private class ForcedDismissSnackbarData(private val base: SnackbarData) : SnackbarData {
    override val visuals: SnackbarVisuals = object : SnackbarVisuals {
        override val message: String = base.visuals.message
        override val actionLabel: String? = base.visuals.actionLabel
        override val withDismissAction: Boolean = true
        override val duration = base.visuals.duration
    }

    override fun performAction() = base.performAction()

    override fun dismiss() = base.dismiss()
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    collapse: () -> Float = { 1f },
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "⌕",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { alpha = collapse().coerceIn(0f, 1f) },
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
            )
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer { alpha = collapse().coerceIn(0f, 1f) },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.app_clear_desc),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            trailingIcon?.invoke()
        }
    }
}

@Composable
fun TohruFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier,
    )
}

@Composable
fun PrefixChips(
    prefixes: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (prefixes.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(prefixes, key = { it }, contentType = { "chip" }) { prefix ->
            TohruFilterChip(
                label = prefix,
                selected = prefix in selected,
                onClick = { onToggle(prefix) },
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_cancel_alt)) } },
    )
}

@Composable
fun EmptyState(modifier: Modifier = Modifier, title: String, hint: String? = null) {
    val resolvedHint = hint ?: stringResource(R.string.app_empty_hint)
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                resolvedHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MarkdownPreview(
    body: String,
    onTaskLink: ((String) -> Unit)? = null,
    taskExists: ((String) -> Boolean)? = null,
) {
    if (body.isBlank()) {
        Text(
            text = stringResource(R.string.app_no_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val components = rememberTohruMarkdownComponents(
        onTaskLink = onTaskLink,
        taskExists = taskExists,
        onToggleChecklist = null,
    )
    Markdown(
        markdownState = rememberMarkdownState(body),
        colors = tohruMarkdownColors(),
        typography = tohruMarkdownTypography(),
        extendedSpans = tohruMarkdownExtendedSpans(),
        components = components,
        modifier = Modifier.fillMaxWidth(),
    )
}

// Read-only task card shared by the archive list and task-link taps. Links
// inside the body open another card instead of the editor.
@Composable
fun TaskPreviewSheet(
    task: Task,
    onDismiss: () -> Unit,
    onTaskLink: ((String) -> Unit)? = null,
    taskExists: ((String) -> Boolean)? = null,
) {
    InstantSheet(onDismissRequest = onDismiss) {
        TaskPreviewContent(task, onTaskLink, taskExists)
    }
}

@Composable
fun TaskPreviewContent(
    task: Task,
    onTaskLink: ((String) -> Unit)? = null,
    taskExists: ((String) -> Boolean)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = task.parsed.body.ifEmpty { task.title },
            style = MaterialTheme.typography.titleLarge,
        )
        TaskBadges(task)
        Text(
            text = activityLabel(task, LocalNowMillis.current, LocalResources.current),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MarkdownPreview(task.body, onTaskLink, taskExists)
    }
}

// Prefix is bold on the tertiary container; topics use the secondary
// container so the two levels stay distinguishable.
@Composable
fun TaskBadges(task: Task) {
    val parsed = task.parsed
    if (parsed.prefix == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Badge(
            text = parsed.prefix,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            bold = true,
        )
        parsed.topic?.let { topic ->
            Badge(
                text = topic,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                bold = false,
            )
        }
    }
}

@Composable
private fun Badge(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    bold: Boolean,
) {
    androidx.compose.material3.Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = if (bold) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.labelMedium
            },
            fontWeight = if (bold) {
                androidx.compose.ui.text.font.FontWeight.ExtraBold
            } else {
                null
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private val badgeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

internal fun badgeTime(task: Task): String =
    Instant.ofEpochMilli(maxOf(task.created, task.updated))
        .atZone(ZoneId.systemDefault())
        .format(badgeFormat)

// Wall clock shared by every activity label, refreshed by the root ticker.
val LocalNowMillis = compositionLocalOf { System.currentTimeMillis() }

// One tick for every relative label on screen: cards read the local instead
// of each owning a timer.
@Composable
fun ProvideNow(content: @Composable () -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    CompositionLocalProvider(LocalNowMillis provides now) {
        content()
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val EDIT_TOLERANCE_MS = 5_000L
private const val RECENT_DAYS = 7

internal fun relativeTime(millis: Long, now: Long, resources: Resources? = null): String {
    val age = (now - millis).coerceAtLeast(0L)
    return when {
        age < MINUTE_MS -> resources?.getString(R.string.time_just_now) ?: "vừa xong"
        age < HOUR_MS -> {
            val minutes = (age / MINUTE_MS).toInt()
            resources?.getQuantityString(R.plurals.time_minutes_ago, minutes, minutes)
                ?: "$minutes phút trước"
        }
        age < DAY_MS -> {
            val hours = (age / HOUR_MS).toInt()
            resources?.getQuantityString(R.plurals.time_hours_ago, hours, hours)
                ?: "$hours giờ trước"
        }
        age < RECENT_DAYS * DAY_MS -> {
            val days = (age / DAY_MS).toInt()
            resources?.getQuantityString(R.plurals.time_days_ago, days, days)
                ?: "$days ngày trước"
        }
        else -> Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(badgeFormat)
    }
}

// One stamp, named for where it comes from. The update stamp is only used
// when it is clearly later than creation, so a fresh task never reads as
// edited.
internal fun activityLabel(task: Task, now: Long, resources: Resources? = null): String {
    val edited = task.updated - task.created > EDIT_TOLERANCE_MS
    val stamp = if (edited) task.updated else task.created
    val relative = relativeTime(stamp, now, resources)
    return when {
        resources == null -> (if (edited) "Sửa " else "Tạo ") + relative
        edited -> resources.getString(R.string.time_edited, relative)
        else -> resources.getString(R.string.time_created, relative)
    }
}

// Dialog-based sheet: appears without the slide animation of ModalBottomSheet.
@Composable
fun InstantSheet(
    onDismissRequest: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .clickable(
                    interactionSource = androidx.compose.runtime.remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    },
                    indication = null,
                    onClick = onDismissRequest,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = androidx.compose.runtime.remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = if (scrollable) {
                        Modifier
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(16.dp)
                    } else {
                        Modifier.padding(16.dp)
                    },
                    content = content,
                )
            }
        }
    }
}

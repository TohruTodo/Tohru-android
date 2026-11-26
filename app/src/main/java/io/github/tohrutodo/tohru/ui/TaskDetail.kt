package io.github.tohrutodo.tohru.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.launch
import org.intellij.markdown.ast.ASTNode
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.md.MarkdownDocument
import io.github.tohrutodo.tohru.md.MarkdownSection
import io.github.tohrutodo.tohru.task.ParsedTitle
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskEditor
import io.github.tohrutodo.tohru.task.TaskFile
import io.github.tohrutodo.tohru.task.TaskLinkIndex
import io.github.tohrutodo.tohru.task.TaskOrder
import io.github.tohrutodo.tohru.task.TaskParser
import io.github.tohrutodo.tohru.task.TaskTree
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val DETAIL_EDIT = 0
private const val DETAIL_PREVIEW = 1
private const val DETAIL_CHIPS_KEY = "detail-chips"

private val TreeIndent = 16.dp
private val TreeBulletWidth = 16.dp

private data class OutlineEntry(
    val depth: Int,
    val label: String,
    // Null targets the title; otherwise the index in the section list.
    val sectionIndex: Int?,
    val headingLine: Int,
    // Markdown level of a heading, null for the title entry.
    val level: Int?,
)

// One layout for both sides of the swipe: the same chips, title, meta and
// body. Editing shows the raw title and raw markdown in place; reading shows
// the parsed title and rendered markdown, split per heading so the outline can
// jump to any of them.
@Composable
fun TaskDetailScreen(
    taskId: String,
    task: Task?,
    tasks: List<Task>,
    taskLinks: TaskLinkIndex,
    onSave: (Task) -> Unit,
    onArchive: (id: String, draft: Task?) -> Unit,
    onOpenTaskLink: (String) -> Unit,
    onExit: () -> Unit,
    screenTitle: String? = null,
    documentChips: List<String> = emptyList(),
    documentRoot: Boolean = false,
    showArchive: Boolean = true,
    showMeta: Boolean = true,
    showTitle: Boolean = true,
    titleEditable: Boolean = true,
    linkBasePath: String? = null,
) {
    var title by rememberSaveable(taskId) { mutableStateOf(task?.title) }
    var bodyValue by rememberSaveable(taskId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = task?.body.orEmpty(),
                selection = TextRange(task?.body?.length ?: 0),
            ),
        )
    }
    val body = bodyValue.text
    var showLinkPicker by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = DETAIL_EDIT) { 2 }
    val editListState = rememberLazyListState()
    val previewListState = rememberLazyListState()
    var editBodyLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scope = rememberCoroutineScope()
    var showOutline by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val taskExists: (String) -> Boolean = remember(taskLinks) {
        { target -> taskLinks.idFor(target) != null }
    }

    fun insertTaskLink(target: Task) {
        val text = bodyValue.text
        val start = bodyValue.selection.start.coerceIn(0, text.length)
        val end = bodyValue.selection.end.coerceIn(start, text.length)
        val label = target.title
            .replace("\\", "\\\\")
            .replace("[", "\\[")
            .replace("]", "\\]")
        val destination = taskLinks.linkFrom(taskId, target.id)
            ?: TaskFile.link(linkBasePath, TaskFile.relativePath(target))
        val link = "[$label]($destination)"
        val separator = if (text.getOrNull(end)?.isWhitespace() == true) "" else " "
        val insertion = link + separator
        bodyValue = TextFieldValue(
            text = text.replaceRange(start, end, insertion),
            selection = TextRange(start + insertion.length),
        )
        showLinkPicker = false
    }

    // Reading has no cursor: drop the keyboard when the read page settles.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            if (settled == DETAIL_PREVIEW) {
                keyboard?.hide()
                focus.clearFocus()
            }
        }
    }

    // The task list loads asynchronously, so the draft fills in once the
    // stored copy arrives and never overwrites later typing.
    LaunchedEffect(taskId, task) {
        if (task != null && title == null) {
            title = task.title
            bodyValue = TextFieldValue(task.body, TextRange(task.body.length))
        }
    }

    val commit: () -> Unit = {
        val currentTitle = title
        val loaded = task
        if (loaded != null && currentTitle != null && currentTitle.isNotBlank()) {
            val currentBody = body.orEmpty()
            if (TaskEditor.needsSave(loaded, currentTitle, currentBody)) {
                onSave(TaskEditor.draft(loaded, currentTitle, currentBody))
            }
        }
    }
    val latestCommit by rememberUpdatedState(commit)

    // Parsed once per edit so typing does not rescan the document for the
    // outline and the sections stay in step with the draft. The renderer and
    // the task link index read the same AST.
    val draftBody = body.orEmpty()
    val parsed = remember(title) { TaskParser.parse(title.orEmpty()) }
    val markdownState = rememberMarkdownState(draftBody, retainState = true)
    val markdown by markdownState.state.collectAsState()
    val markdownContent = (markdown as? State.Success)?.content.orEmpty()
    val sections = remember(markdown) {
        (markdown as? State.Success)
            ?.let { MarkdownDocument.sections(it.node, it.content) }
            .orEmpty()
    }
    val editing = pagerState.currentPage == DETAIL_EDIT
    val outline = remember(sections, markdownContent, editing, title, parsed) {
        buildOutline(
            sections = sections,
            content = markdownContent,
            editing = editing,
            rawTitle = title.orEmpty(),
            parsedTitle = parsed.body.ifEmpty { title.orEmpty() },
            includeTitle = showTitle,
        )
    }
    val hasHeadings = sections.any { it.heading != null }
    val markdownColors = tohruMarkdownColors()
    val markdownTypography = tohruMarkdownTypography()
    val latestBody by rememberUpdatedState(bodyValue.text)
    val toggleChecklist: (ASTNode, String) -> Unit = remember {
        { node, content ->
            if (latestBody == content) {
                val current = bodyValue
                val toggled = MarkdownDocument.toggleCheckbox(content, node)
                bodyValue = current.copy(
                    text = toggled,
                    selection = TextRange(
                        current.selection.start.coerceAtMost(toggled.length),
                        current.selection.end.coerceAtMost(toggled.length),
                    ),
                )
            }
        }
    }
    val markdownComponents = rememberTohruMarkdownComponents(
        onTaskLink = onOpenTaskLink,
        taskExists = taskExists,
        onToggleChecklist = toggleChecklist,
    )

    BackHandler {
        latestCommit()
        onExit()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { latestCommit() }

    if (showLinkPicker) {
        TaskLinkPickerScreen(
            tasks = tasks,
            onDismiss = { showLinkPicker = false },
            onPick = ::insertTaskLink,
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TohruTopBar(
                title = screenTitle ?: stringResource(R.string.git_task_title),
                onBack = {
                    commit()
                    onExit()
                },
                actions = {
                    if (editing) {
                        TohruIconButton(
                            icon = R.drawable.ic_link,
                            description = stringResource(R.string.git_task_insert_link),
                            onClick = { showLinkPicker = true },
                        )
                    }
                    if (showArchive) {
                        TohruIconButton(
                            icon = R.drawable.ic_archive,
                            description = stringResource(R.string.git_task_archive),
                            onClick = {
                                val loaded = task
                                val currentTitle = title
                                if (loaded != null && currentTitle != null) {
                                    val currentBody = body.orEmpty()
                                    val dirty = currentTitle.isNotBlank() &&
                                        TaskEditor.needsSave(loaded, currentTitle, currentBody)
                                    onArchive(
                                        loaded.id,
                                        if (dirty) TaskEditor.draft(loaded, currentTitle, currentBody) else null,
                                    )
                                }
                                onExit()
                            },
                        )
                    }
                    if (hasHeadings) {
                        TohruIconButton(
                            icon = R.drawable.ic_toc,
                            description = stringResource(R.string.git_task_toc),
                            onClick = { showOutline = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        val currentTitle = title
        if (task == null || currentTitle == null) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.git_task_loading), style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }
        val currentBody = bodyValue.text
        val hasChips = parsed.prefix != null || parsed.topic != null
        val chipsOffset = if (hasChips || documentChips.isNotEmpty()) 1 else 0
        val bodyBase = chipsOffset + (if (showTitle) 1 else 0) + (if (showMeta) 1 else 0)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            val pageEditable = page == DETAIL_EDIT
            DetailPage(
                listState = if (pageEditable) editListState else previewListState,
                task = task,
                parsed = parsed,
                title = currentTitle,
                body = currentBody,
                bodyValue = bodyValue,
                sections = sections,
                markdown = if (pageEditable) null else markdown as? State.Success,
                markdownColors = markdownColors,
                markdownTypography = markdownTypography,
                markdownComponents = markdownComponents,
                hasChips = hasChips,
                documentChips = documentChips,
                documentRoot = documentRoot,
                editable = pageEditable,
                showMeta = showMeta,
                showTitle = showTitle,
                titleEditable = titleEditable,
                onTitleChange = { title = it },
                onBodyValueChange = { bodyValue = it },
                onBodyTextLayout = { editBodyLayout = it },
                onOpenTaskLink = onOpenTaskLink,
                taskExists = taskExists,
            )
        }

        if (showOutline) {
            OutlineSheet(
                entries = outline,
                onPick = { entry ->
                    showOutline = false
                    scope.launch {
                        jumpToEntry(
                            entry = entry,
                            editing = editing,
                            body = currentBody,
                            bodyBase = bodyBase,
                            titleIndex = chipsOffset,
                            editListState = editListState,
                            previewListState = previewListState,
                            editBodyLayout = { editBodyLayout },
                        )
                    }
                },
                onDismiss = { showOutline = false },
            )
        }
    }
}

@Composable
private fun TaskLinkPickerScreen(
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onPick: (Task) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    val active = remember(tasks, query) {
        val needle = query.trim().lowercase()
        tasks.filterNot { it.archived }
            .filter { task ->
                needle.isEmpty() ||
                    task.title.lowercase().contains(needle) ||
                    task.body.lowercase().contains(needle) ||
                    task.parsed.prefix?.lowercase()?.contains(needle) == true
            }
    }
    val rows = remember(active, collapsed) {
        TaskTree.rows(active, TaskOrder(), collapsed = collapsed)
    }
    val listState = rememberLazyListState()
    BackHandler(onBack = onDismiss)
    Scaffold(
        topBar = {
            TohruTopBar(title = stringResource(R.string.git_task_pick_title), onBack = onDismiss)
        },
    ) { padding ->
        TasksPage(
            rows = rows,
            tasks = active,
            selection = emptySet(),
            editMode = false,
            listState = listState,
            openRowKey = null,
            onOpenRow = {},
            onToggleCollapse = { key ->
                collapsed = if (key in collapsed) collapsed - key else collapsed + key
            },
            onToggleNode = {},
            onToggleDone = {},
            onEnterEdit = {},
            onEditTask = { id -> active.firstOrNull { it.id == id }?.let(onPick) },
            onOpenDetail = { id -> active.firstOrNull { it.id == id }?.let(onPick) },
            onMove = { _, _, _ -> },
            header = { _ ->
                SearchField(value = query, onValueChange = { query = it })
            },
            emptyTitle = stringResource(R.string.git_task_pick_empty),
            modifier = Modifier.padding(padding),
            showDragHandle = false,
            dragEnabled = false,
        )
    }
}

private fun buildOutline(
    sections: List<MarkdownSection>,
    content: String,
    editing: Boolean,
    rawTitle: String,
    parsedTitle: String,
    includeTitle: Boolean,
): List<OutlineEntry> = buildList {
    if (includeTitle) {
        add(
            OutlineEntry(
                depth = 0,
                label = if (editing) rawTitle else parsedTitle,
                sectionIndex = null,
                headingLine = 0,
                level = null,
            ),
        )
    }
    sections.forEachIndexed { index, section ->
        val heading = section.heading ?: return@forEachIndexed
        add(
            OutlineEntry(
                depth = heading.depth,
                label = if (editing) {
                    MarkdownDocument.lineText(content, heading.line).trim()
                } else {
                    heading.text
                },
                sectionIndex = index,
                headingLine = heading.line,
                level = heading.level,
            ),
        )
    }
}

private suspend fun jumpToEntry(
    entry: OutlineEntry,
    editing: Boolean,
    body: String,
    bodyBase: Int,
    titleIndex: Int,
    editListState: LazyListState,
    previewListState: LazyListState,
    editBodyLayout: () -> TextLayoutResult?,
) {
    val sectionIndex = entry.sectionIndex
    if (sectionIndex == null) {
        val listState = if (editing) editListState else previewListState
        listState.animateScrollToItem(titleIndex)
        return
    }
    if (!editing) {
        previewListState.animateScrollToItem(sectionIndex + bodyBase)
        return
    }
    // The raw editor is one text field. Scroll it into place, then run the
    // heading's line to the top in the same animation to avoid a double jump.
    if (editBodyLayout() == null) {
        editListState.scrollToItem(bodyBase)
        repeat(2) { withFrameNanos { } }
    }
    val resolved = editBodyLayout()
    if (resolved == null) {
        editListState.animateScrollToItem(bodyBase)
        return
    }
    val offset = lineStartOffset(body, entry.headingLine)
        .coerceIn(0, resolved.layoutInput.text.length)
    val lineTop = resolved.getLineTop(resolved.getLineForOffset(offset)).roundToInt()
    editListState.animateScrollToItem(bodyBase, scrollOffset = lineTop)
}

private fun lineStartOffset(text: String, line: Int): Int {
    if (line <= 0) return 0
    var seen = 0
    text.forEachIndexed { index, char ->
        if (char == '\n') {
            seen++
            if (seen == line) return index + 1
        }
    }
    return text.length
}

@Composable
private fun DetailPage(
    listState: LazyListState,
    task: Task,
    parsed: ParsedTitle,
    title: String,
    body: String,
    bodyValue: TextFieldValue,
    sections: List<MarkdownSection>,
    markdown: State.Success?,
    markdownColors: MarkdownColors,
    markdownTypography: MarkdownTypography,
    markdownComponents: MarkdownComponents,
    hasChips: Boolean,
    documentChips: List<String>,
    documentRoot: Boolean,
    editable: Boolean,
    showMeta: Boolean,
    showTitle: Boolean,
    titleEditable: Boolean,
    onTitleChange: (String) -> Unit,
    onBodyValueChange: (TextFieldValue) -> Unit,
    onBodyTextLayout: (TextLayoutResult) -> Unit,
    onOpenTaskLink: (String) -> Unit,
    taskExists: (String) -> Boolean,
) {
    if (!editable && markdown != null && body.isNotBlank()) {
        Markdown(
            state = markdown,
            colors = markdownColors,
            typography = markdownTypography,
            extendedSpans = tohruMarkdownExtendedSpans(),
            components = markdownComponents,
            modifier = Modifier.fillMaxSize(),
            success = { state, components, modifier ->
                PreviewPage(
                    modifier = modifier,
                    listState = listState,
                    state = state,
                    components = components,
                    task = task,
                    parsed = parsed,
                    title = title,
                    sections = sections,
                    hasChips = hasChips,
                    documentChips = documentChips,
                    documentRoot = documentRoot,
                    showMeta = showMeta,
                    showTitle = showTitle,
                )
            },
        )
        return
    }
    SnapChipsOnScroll(listState)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasChips) {
            item(key = DETAIL_CHIPS_KEY) {
                SquashedChips(state = listState, parsed = parsed)
            }
        } else if (documentChips.isNotEmpty()) {
            item(key = DETAIL_CHIPS_KEY) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    documentChips.forEachIndexed { index, label ->
                        DocumentChip(label, root = documentRoot && index == 0)
                    }
                }
            }
        }
        if (showTitle) item(key = "title") {
            if (editable && titleEditable) {
                PlainField(
                    value = title,
                    onValueChange = onTitleChange,
                    style = MaterialTheme.typography.headlineMedium,
                )
            } else {
                Text(
                    text = parsed.body.ifEmpty { title },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        if (showMeta) {
            item(key = "meta") { DetailMeta(task = task, body = body) }
        }
        if (editable) {
            item(key = "body-edit") {
                MarkdownField(
                    value = bodyValue,
                    onValueChange = onBodyValueChange,
                    style = MaterialTheme.typography.bodyLarge,
                    minHeight = 240.dp,
                    onTextLayout = onBodyTextLayout,
                )
            }
        } else if (body.isBlank()) {
            item(key = "body-empty") { MarkdownPreview(body, onOpenTaskLink, taskExists) }
        }
    }
}

@Composable
private fun PreviewPage(
    modifier: Modifier,
    listState: LazyListState,
    state: State.Success,
    components: MarkdownComponents,
    task: Task,
    parsed: ParsedTitle,
    title: String,
    sections: List<MarkdownSection>,
    hasChips: Boolean,
    documentChips: List<String>,
    documentRoot: Boolean,
    showMeta: Boolean,
    showTitle: Boolean,
) {
    SnapChipsOnScroll(listState)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasChips) {
            item(key = DETAIL_CHIPS_KEY) {
                SquashedChips(state = listState, parsed = parsed)
            }
        } else if (documentChips.isNotEmpty()) {
            item(key = DETAIL_CHIPS_KEY) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    documentChips.forEachIndexed { index, label ->
                        DocumentChip(label, root = documentRoot && index == 0)
                    }
                }
            }
        }
        if (showTitle) item(key = "title") {
            Text(
                text = parsed.body.ifEmpty { title },
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        if (showMeta) {
            item(key = "meta") { DetailMeta(task = task, body = state.content) }
        }
        itemsIndexed(
            items = sections,
            key = { index, _ -> "section-$index" },
            contentType = { _, _ -> "markdown" },
        ) { _, section ->
            MarkdownSectionView(section, state.content, components)
        }
    }
}

// A text field stripped of its container, so editing and reading share the
// same typography.
@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    style: TextStyle,
    minHeight: Dp = 0.dp,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        onTextLayout = onTextLayout,
    )
}

@Composable
private fun MarkdownField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    style: TextStyle,
    minHeight: Dp = 0.dp,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        onTextLayout = onTextLayout,
    )
}

@Composable
private fun formatDay(millis: Long): String {
    val pattern = stringResource(R.string.ui_detail_date_pattern)
    return remember(pattern, millis) {
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(pattern))
    }
}

@Composable
private fun DetailMeta(task: Task, body: String) {
    Text(
        text = stringResource(
            R.string.git_task_meta,
            formatDay(maxOf(task.created, task.updated)),
            body.length,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// The chip row is the first item of each page. It squashes and fades while it
// is cut by the top edge, then snaps fully in or out when the list stops. At
// rest the transform is clamped to an endpoint so a partial scroll can never
// leave the text half-squashed.
@Composable
private fun SquashedChips(state: LazyListState, parsed: ParsedTitle) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val header = state.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == DETAIL_CHIPS_KEY }
                val fraction = ChipSquash.fraction(
                    firstVisibleIndex = state.firstVisibleItemIndex,
                    scrollOffset = state.firstVisibleItemScrollOffset,
                    size = header?.size ?: 0,
                )
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleY = fraction
                alpha = fraction
            },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            parsed.prefix?.let { prefix ->
                DetailChip(label = prefix)
            }
            parsed.topic?.let { topic ->
                DetailChip(label = topic)
            }
        }
    }
}

@Composable
private fun DetailChip(label: String) {
    LabelChip(
        label = label,
        container = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

// A document label, not a task group/topic filter. It keeps a filled chip like
// the task tags but uses the document color and a note icon.
@Composable
private fun DocumentChip(label: String, root: Boolean) {
    LabelChip(
        label = label,
        container = if (root) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (root) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        icon = R.drawable.ic_note,
    )
}

@Composable
private fun LabelChip(
    label: String,
    container: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    icon: Int? = null,
) {
    Surface(color = container, contentColor = contentColor, shape = MaterialTheme.shapes.small) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun OutlineSheet(
    entries: List<OutlineEntry>,
    onPick: (OutlineEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    InstantSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            entries.forEach { entry ->
                OutlineRow(entry = entry, onClick = { onPick(entry) })
            }
        }
    }
}

// Tree guide lines are drawn instead of typed characters, so they run
// unbroken from row to row and stay a touch fainter than the text.
@Composable
private fun OutlineRow(entry: OutlineEntry, onClick: () -> Unit) {
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val bulletColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.depth > 0) {
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(TreeIndent * entry.depth + TreeBulletWidth),
            ) {
                val step = TreeIndent.toPx()
                for (level in 0 until entry.depth) {
                    val x = level * step + step / 2f
                    drawLine(
                        color = lineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                drawCircle(
                    color = bulletColor,
                    radius = 1.8.dp.toPx(),
                    center = Offset(entry.depth * step + step / 2f, size.height / 2f),
                )
            }
        }
        Text(
            text = entry.label,
            style = entry.level?.let { level ->
                TextStyle(
                    fontSize = MarkdownDocument.headingSize(level),
                    lineHeight = MarkdownDocument.headingLineHeight(level),
                    fontWeight = FontWeight.Bold,
                )
            } ?: MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 0.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun SnapChipsOnScroll(state: LazyListState) {
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            val info = state.layoutInfo
            val header = info.visibleItemsInfo.firstOrNull { it.key == DETAIL_CHIPS_KEY }
                ?: return@collect
            val delta = HeaderSnap.snapDelta(header.offset, header.size, info.beforeContentPadding)
                ?: return@collect
            state.animateScrollBy(delta, tween(Motion.NORMAL))
        }
    }
}

package io.github.tohrutodo.tohru.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.task.ReadmeDoc
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskLinkIndex
import io.github.tohrutodo.tohru.task.readmeLabels

// A README reuses the task detail editor: the same edit/read pager, outline,
// link picker and draft saving. Only the title, date metadata and archive
// action are hidden because the folder path owns those.
@Composable
fun ReadmeDetailScreen(
    segments: List<String>,
    readme: ReadmeDoc?,
    tasks: List<Task>,
    taskLinks: TaskLinkIndex,
    onSave: (List<String>, String) -> Unit,
    onOpenTaskLink: (String) -> Unit,
    onExit: () -> Unit,
) {
    val labels = remember(segments, tasks) { readmeLabels(segments, tasks) }
    val label = labels.lastOrNull() ?: "Repo"
    val path = (segments + ReadmeDoc.README_NAME).joinToString("/")
    val document = remember(segments, readme) {
        Task(
            id = "readme:$path",
            title = label,
            created = 0L,
            updated = 0L,
            body = readme?.body.orEmpty(),
        )
    }
    TaskDetailScreen(
        taskId = document.id,
        task = document,
        tasks = tasks,
        taskLinks = taskLinks,
        onSave = { draft -> onSave(segments, draft.body) },
        onArchive = { _, _ -> },
        onOpenTaskLink = onOpenTaskLink,
        onExit = onExit,
        screenTitle = stringResource(R.string.readme_title),
        documentChips = labels,
        documentRoot = segments.isEmpty(),
        showArchive = false,
        showMeta = false,
        showTitle = false,
        titleEditable = false,
        linkBasePath = path,
    )
}

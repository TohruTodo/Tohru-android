package io.github.tohrutodo.tohru.sync

import androidx.annotation.StringRes
import io.github.tohrutodo.tohru.R
import io.github.tohrutodo.tohru.task.TaskFile

// Conflict helpers stay separate from the JGit engine so the choice policy
// can be tested without a repository.
internal object TaskMerge {
    private const val PREVIEW_LINES = 3
    private const val PREVIEW_CHARS = 240

    fun suggest(local: String?, remote: String?): ConflictSide {
        if (local == null) return ConflictSide.REMOTE
        if (remote == null) return ConflictSide.LOCAL
        val localTask = runCatching { TaskFile.deserialize(local) }.getOrNull()
        val remoteTask = runCatching { TaskFile.deserialize(remote) }.getOrNull()
        if (localTask == null || remoteTask == null) return ConflictSide.LOCAL
        // Frontmatter stamps are the only cross-device ordering signal.
        return if (remoteTask.updated > localTask.updated) ConflictSide.REMOTE else ConflictSide.LOCAL
    }

    // Returns a user-facing reason when the edited task cannot be applied.
    @StringRes
    fun invalidReasonRes(path: String, text: String): Int? {
        val task = runCatching { TaskFile.deserialize(text) }.getOrNull()
            ?: return R.string.ui_conflict_invalid_frontmatter
        val expectedId = path.substringAfterLast('/').substringBefore('_').removeSuffix(".md")
        return if (task.id != expectedId) R.string.ui_conflict_id_mismatch else null
    }

    fun describe(text: String?): ConflictVersion {
        if (text == null) return ConflictVersion(exists = false)
        val task = runCatching { TaskFile.deserialize(text) }.getOrNull()
        val preview = (task?.body ?: text)
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(PREVIEW_LINES)
            .joinToString("\n")
            .take(PREVIEW_CHARS)
        return ConflictVersion(
            exists = true,
            title = task?.title,
            updated = task?.updated,
            preview = preview,
            text = text,
            lineCount = text.lines().size,
        )
    }
}

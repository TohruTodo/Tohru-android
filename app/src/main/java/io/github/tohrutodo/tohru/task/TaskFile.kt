package io.github.tohrutodo.tohru.task

import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

data class TaskState(val done: Boolean = false, val archived: Boolean = false)

// One task maps to one markdown file under <repo>/tasks. The relative path
// mirrors the title: [<group>/][<topic>/]<id>_<slug>.md, with done and
// archived as top-level state directories.
//
// Path segments keep Unicode letters but normalize to NFC and replace every
// other character with '_', so directory names and file slugs share one rule.
// Files outside this layout are not migrated by the app.
object TaskFile {
    // A leading '_' keeps these names outside the group namespace: parsed
    // prefixes start with a letter or digit.
    const val DONE_DIR = "_done"
    const val ARCHIVED_DIR = "_archived"

    private val nonSlug = Regex("""[^\p{L}\p{M}\p{N}]+""")

    fun newId(now: Long = System.currentTimeMillis()): String =
        "%d-%04d".format(now, Random.nextInt(10000))

    // File names keep the stable id but add a readable slug. Titles can be
    // arbitrarily long; only the path segment is capped.
    fun fileName(task: Task): String = "${task.id}_${slug(task.title)}.md"

    fun slug(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
        return normalized.replace(nonSlug, "_")
            .trim('_')
            .take(48)
            .trim('_')
            .ifEmpty { "task" }
    }

    // Path relative to the tasks directory, always with '/' separators.
    fun relativePath(task: Task): String = buildList {
        when {
            task.archived && task.done -> {
                add(ARCHIVED_DIR)
                add(DONE_DIR)
            }
            task.archived -> add(ARCHIVED_DIR)
            task.done -> add(DONE_DIR)
        }
        val parsed = task.parsed
        parsed.prefix?.let { add(slug(it)) }
        parsed.topic?.let { add(slug(it)) }
        add(fileName(task))
    }.joinToString("/")

    fun taskFile(tasksDir: File, task: Task): File =
        File(tasksDir, relativePath(task).replace('/', File.separatorChar))

    // The folder carries the state. A group cannot start with '_', so these
    // names cannot collide with a group folder.
    fun stateOf(relativePath: String): TaskState {
        val parts = relativePath.split('/')
        return when (parts.firstOrNull()) {
            ARCHIVED_DIR -> TaskState(
                done = parts.getOrNull(1) == DONE_DIR,
                archived = true,
            )
            DONE_DIR -> TaskState(done = true)
            else -> TaskState()
        }
    }

    // Normalized relative path of an existing file, used for cache keys and
    // link sources.
    fun relativePath(tasksDir: File, file: File): String {
        val base = tasksDir.absoluteFile.toPath().normalize()
        val target = file.absoluteFile.toPath().normalize()
        return Normalizer.normalize(base.relativize(target).toString(), Normalizer.Form.NFC)
            .replace(File.separatorChar, '/')
    }

    // Markdown destination that reaches [toPath] from the file at [fromPath].
    // Both are paths relative to the tasks directory.
    fun link(fromPath: String?, toPath: String): String {
        val fromDir = fromPath
            ?.substringBeforeLast('/', "")
            ?.split('/')
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val target = toPath.split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < fromDir.size && common < target.size - 1 && fromDir[common] == target[common]) {
            common++
        }
        val parts = buildList {
            repeat(fromDir.size - common) { add("..") }
            addAll(target.drop(common))
        }
        val value = parts.joinToString("/")
        return if (value.any(Char::isWhitespace)) "<$value>" else value
    }

    fun serialize(task: Task): String = buildString {
        appendLine("---")
        appendLine("id: ${task.id}")
        appendLine("title: ${singleLine(task.title)}")
        appendLine("created: ${task.created}")
        appendLine("updated: ${task.updated}")
        appendLine("---")
        append(task.body)
    }

    fun deserialize(text: String, state: TaskState = TaskState()): Task? {
        val lines = text.lines()
        if (lines.firstOrNull() != "---") return null
        val end = lines.drop(1).indexOf("---").let { if (it < 0) -1 else it + 1 }
            .takeIf { it > 0 } ?: return null
        val header = lines.subList(1, end).associate { line ->
            val cut = line.indexOf(':')
            if (cut < 0) return null
            line.substring(0, cut).trim() to line.substring(cut + 1).trim()
        }
        val id = header["id"]?.takeIf { it.isNotEmpty() } ?: return null
        val title = header["title"] ?: return null
        return Task(
            id = id,
            title = title,
            done = state.done,
            archived = state.archived,
            created = header["created"]?.toLongOrNull() ?: return null,
            updated = header["updated"]?.toLongOrNull() ?: return null,
            body = lines.drop(end + 1).joinToString("\n"),
        )
    }

    private fun singleLine(value: String): String = value.replace('\n', ' ').trim()
}

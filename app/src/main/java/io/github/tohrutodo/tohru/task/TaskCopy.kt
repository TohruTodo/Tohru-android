package io.github.tohrutodo.tohru.task

// Plain text for copying a selection into notes or chat. The first line is a
// markdown checklist item; body lines stay inside that item with two spaces.
object TaskCopy {
    fun format(tasks: List<Task>): String = tasks.joinToString(separator = "\n\n") { task ->
        val marker = if (task.done) "[x]" else "[ ]"
        val title = task.title.trim()
        val body = task.body.trimEnd()
        if (body.isEmpty()) {
            "- $marker $title"
        } else {
            val indented = body.lines().joinToString(separator = "\n") { line ->
                if (line.isEmpty()) "" else "  $line"
            }
            "- $marker $title\n$indented"
        }
    }
}

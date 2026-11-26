package io.github.tohrutodo.tohru.task

// A README placed beside the folder it documents:
// tasks/README.md, tasks/<group>/README.md, tasks/<group>/<topic>/README.md.
// Segments are the folder path below tasks; an empty list is the task-repo
// root. The model allows deeper paths so a repo-root README can be added later
// without changing the tree contract.
data class ReadmeDoc(
    val segments: List<String>,
    val body: String,
) {
    val path: String
        get() = (segments + README_NAME).joinToString("/")

    val label: String
        get() = segments.lastOrNull()?.replace('_', ' ') ?: "Repo"

    fun matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return label.contains(needle, ignoreCase = true) ||
            path.contains(needle, ignoreCase = true) ||
            body.contains(needle, ignoreCase = true)
    }

    companion object {
        const val README_NAME = "README.md"
    }
}

// Display labels for a readme path, using task titles when a matching group or
// topic exists and falling back to the folder segment.
fun readmeLabels(segments: List<String>, tasks: List<Task>): List<String> {
    if (segments.isEmpty()) return listOf("Repo")
    return segments.mapIndexed { index, segment ->
        val match = tasks.firstOrNull { task ->
            val parsed = task.parsed
            val prefix = parsed.prefix ?: return@firstOrNull false
            if (TaskFile.slug(prefix) != segments.first()) return@firstOrNull false
            index == 0 || parsed.topic?.let { TaskFile.slug(it) == segment } == true
        }
        when (index) {
            0 -> match?.parsed?.prefix
            1 -> match?.parsed?.topic
            else -> null
        } ?: segment.replace('_', ' ')
    }
}

package io.github.tohrutodo.tohru.task

// Small summary for the manage header. Special prefixes and archived tasks
// are outside the task counters.
data class TaskStats(
    val total: Int,
    val done: Int,
    val open: Int,
) {
    companion object {
        fun of(
            tasks: List<Task>,
            excludedPrefixes: Set<String> = emptySet(),
        ): TaskStats {
            val scoped = tasks.filterNot {
                it.archived || (it.parsed.prefix != null && it.parsed.prefix in excludedPrefixes)
            }
            val done = scoped.count { it.done }
            return TaskStats(total = scoped.size, done = done, open = scoped.size - done)
        }
    }
}

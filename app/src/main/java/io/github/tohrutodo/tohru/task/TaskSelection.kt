package io.github.tohrutodo.tohru.task

// Node ids used by the selection model:
//   k:<taskId>          single task
//   t:<prefix>|<topic>  topic group
//   p:<prefix>          whole prefix group
enum class SelectionScope { OPEN, ALL }

object TaskSelection {
    fun taskIds(
        selected: Set<String>,
        tasks: List<Task>,
        scope: SelectionScope = SelectionScope.ALL,
    ): Set<String> {
        if (selected.isEmpty()) return emptySet()
        return tasks.filter { task ->
            val parsed = task.parsed
            val prefix = parsed.prefix ?: TaskParser.INBOX
            selected.any { node ->
                when {
                    node.startsWith("k:") -> node.removePrefix("k:") == task.id
                    node.startsWith("t:") -> {
                        val scoped = scope == SelectionScope.ALL || !task.done
                        scoped &&
                            node.removePrefix("t:").substringBefore('|') == prefix &&
                            node.removePrefix("t:").substringAfter('|', "") == parsed.topic.orEmpty()
                    }
                    node.startsWith("p:") -> {
                        (scope == SelectionScope.ALL || !task.done) &&
                            node.removePrefix("p:") == prefix
                    }
                    else -> false
                }
            }
        }.map { it.id }.toSet()
    }

    fun idsForNode(
        nodeKey: String,
        tasks: List<Task>,
        scope: SelectionScope = SelectionScope.ALL,
    ): Set<String> = when {
        nodeKey.startsWith(TreeRow.DONE_GROUP_PREFIX) -> {
            val prefix = nodeKey.removePrefix(TreeRow.DONE_GROUP_PREFIX)
            tasks.filter {
                (it.parsed.prefix ?: TaskParser.INBOX) == prefix && it.done
            }.map { it.id }.toSet()
        }
        nodeKey.startsWith(TreeRow.DONE_TOPIC_PREFIX) -> {
            val scopeKey = nodeKey.removePrefix(TreeRow.DONE_TOPIC_PREFIX)
            val prefix = scopeKey.substringBefore('|')
            val topic = scopeKey.substringAfter('|', "")
            tasks.filter {
                (it.parsed.prefix ?: TaskParser.INBOX) == prefix &&
                    it.parsed.topic.orEmpty() == topic && it.done
            }.map { it.id }.toSet()
        }
        nodeKey.startsWith(TreeRow.DONE_PREFIX) ->
            doneIds(nodeKey.removePrefix(TreeRow.DONE_PREFIX), tasks)
        nodeKey == TreeRow.UNTAGGED_KEY ->
            tasks.filter {
                it.parsed.prefix == null &&
                    (scope == SelectionScope.ALL || !it.done)
            }.map { it.id }.toSet()
        else -> taskIds(setOf(nodeKey), tasks, scope)
    }

    // The Done section now holds every completed task, grouped by its old
    // prefix and topic, so the section header selects all of them.
    private fun doneIds(scope: String, tasks: List<Task>): Set<String> {
        if (scope != "all") return emptySet()
        return tasks.filter { it.done }.map { it.id }.toSet()
    }

    // Bulk selection works on the plain task ids every board stores.
    fun allSelected(selected: Set<String>, tasks: List<Task>): Boolean =
        tasks.isNotEmpty() && tasks.all { it.id in selected }

    // The selection holds plain task ids; keep only the ones in the given scope.
    fun selectedIds(selected: Set<String>, tasks: List<Task>): Set<String> =
        tasks.filter { it.id in selected }.map { it.id }.toSet()

    fun idsOf(tasks: List<Task>): Set<String> = tasks.map { it.id }.toSet()

    fun nodeKeyForTask(task: Task): String = "k:${task.id}"

    fun nodeKeyForTopic(task: Task): String? {
        val parsed = task.parsed
        val topic = parsed.topic ?: return null
        return "t:${parsed.prefix ?: TaskParser.INBOX}|$topic"
    }
}

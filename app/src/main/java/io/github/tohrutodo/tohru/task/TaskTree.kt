package io.github.tohrutodo.tohru.task

// Row model of the task tree and the ordering rules that produce it.
//
// Untagged tasks sit at the top without a header. Below them, groups hold
// their plain tasks and topic branches as siblings. Automatic order is newest
// activity first (max of created/updated). Manual order from TaskOrder
// overrides the automatic order inside a level; unknown nodes are inserted at
// the top of that level. Active boards render open tasks in the tree and all
// completed tasks in one Done section that mirrors the same hierarchy.
object TaskTree {
    fun activity(task: Task): Long = maxOf(task.created, task.updated)

    fun matches(task: Task, query: String): Boolean {
        if (query.isBlank()) return true
        val parsed = task.parsed
        val needle = query.lowercase()
        return listOf(
            parsed.prefix.orEmpty(),
            parsed.topic.orEmpty(),
            parsed.body,
            task.body,
        ).any { it.lowercase().contains(needle) }
    }

    fun rows(
        tasks: List<Task>,
        order: TaskOrder = TaskOrder(),
        query: String = "",
        prefixes: Set<String> = emptySet(),
        collapsed: Set<String> = emptySet(),
        archivedOnly: Boolean = false,
    ): List<TreeRow> {
        val filtered = tasks.asSequence()
            .filter { it.archived == archivedOnly }
            .filter { prefixes.isEmpty() || it.parsed.prefix in prefixes }
            .filter { matches(it, query) }
            .toList()
        if (filtered.isEmpty()) return emptyList()
        if (!archivedOnly) return rowsActive(filtered, order, query, collapsed)

        val searching = query.isNotBlank()
        val rows = ArrayList<TreeRow>()

        // Untagged open tasks render under their own collapsible header. Done
        // ones live in the section at the end.
        val sibling = TaskOrder.groupKey(TaskParser.INBOX)
        val untagged = filtered.filter { it.parsed.prefix == null }
        val untaggedOpen = untagged.filterNot { it.done }
        val untaggedDone = untagged.filter { it.done }
        if (untaggedOpen.isNotEmpty()) {
            val untaggedCollapsed = TreeRow.UNTAGGED_KEY in collapsed && !searching
            rows += TreeRow.UntaggedHeader(
                count = untaggedOpen.size,
                collapsed = untaggedCollapsed,
            )
            if (!untaggedCollapsed) {
                rows += orderedLevel(untaggedOpen, order, sibling)
                    .map { TreeRow.Item(it, sibling) }
            }
        }

        val prefixed = filtered.filter { it.parsed.prefix != null }
        val byPrefix = prefixed.groupBy { it.parsed.prefix!! }
        val prefixKeys = byPrefix.keys.toList()
        val orderedPrefixes = orderNodes(
            stored = order.groups.filter { it != TaskParser.INBOX },
            present = prefixKeys,
            keyOf = { prefix -> byPrefix.getValue(prefix).maxOf { activity(it) } },
        )
        val openPrefixes = orderedPrefixes.filterNot { prefix ->
            byPrefix.getValue(prefix).all { it.done }
        }
        val donePrefixes = orderedPrefixes.filter { prefix ->
            byPrefix.getValue(prefix).all { it.done }
        }

        fun emitTopic(prefix: String, topic: String, topicTasks: List<Task>) {
            val topicKey = TaskOrder.topicKey(prefix, topic)
            val topicCollapsed = topicKey in collapsed && !searching
            rows += TreeRow.TopicHeader(
                rowKey = topicKey,
                prefix = prefix,
                topic = topic,
                count = topicTasks.count { !it.done },
                collapsed = topicCollapsed,
            )
            if (topicCollapsed) return
            rows += orderedLevel(topicTasks, order, topicKey)
                .map { TreeRow.Item(it, topicKey) }
        }

        fun emitPrefix(prefix: String) {
            val prefixTasks = byPrefix.getValue(prefix)
            val plain = prefixTasks.filter { it.parsed.topic == null }
            val topics = prefixTasks.filter { it.parsed.topic != null }
                .groupBy { it.parsed.topic!! }

            val topicNodes = topics.keys.toList()
            val topicSiblingItems = (
                plain.map { TaskOrder.taskId(it.id) } +
                    topicNodes.map { TaskOrder.topicChild(it) }
                )
            val siblingKey = TaskOrder.groupKey(prefix)
            val storedSiblings = order.children[prefix].orEmpty()

            val presentKeys = topicSiblingItems.toSet()
            val orderedSiblings = if (searching) {
                autoSortSiblings(plain, topics, topicNodes)
            } else {
                orderNodes(
                    stored = storedSiblings.filter { it in presentKeys },
                    present = topicSiblingItems,
                    keyOf = { item -> siblingKeyOf(item, plain, topics) },
                )
            }

            val plainById = plain.associateBy { TaskOrder.taskId(it.id) }
            val doneSiblings = doneNodes(prefixTasks, siblingKey)
            // Plain tasks always sit above topic blocks inside a prefix; the
            // done partition applies inside each block.
            val blocked = blockOrder(siblingKey, orderedSiblings)
            val grouped = blocked.filter { it.startsWith("k:") }
                .sortedBy { if (it in doneSiblings) 1 else 0 } +
                blocked.filterNot { it.startsWith("k:") }
                    .sortedBy { if (it in doneSiblings) 1 else 0 }

            val prefixCollapsed = siblingKey in collapsed && !searching
            rows += TreeRow.GroupHeader(
                rowKey = siblingKey,
                prefix = prefix,
                count = prefixTasks.count { !it.done },
                collapsed = prefixCollapsed,
            )
            if (prefixCollapsed) return

            for (item in grouped) {
                if (item.startsWith("k:")) {
                    plainById[item]?.let { rows += TreeRow.Item(it, siblingKey) }
                } else {
                    val topic = item.removePrefix("t:")
                    emitTopic(prefix, topic, topics[topic].orEmpty())
                }
            }
        }

        openPrefixes.forEach { emitPrefix(it) }

        // One done section at the very bottom: done untagged tasks and whole
        // prefixes whose tasks are all done. Everything else keeps the old
        // done-at-the-bottom order inside its own level.
        if (untaggedDone.isNotEmpty() || donePrefixes.isNotEmpty()) {
            val sectionKey = TreeRow.DONE_PREFIX + "all"
            val sectionCollapsed = sectionKey in collapsed && !searching
            val count = untaggedDone.size + donePrefixes.sumOf { byPrefix.getValue(it).size }
            rows += TreeRow.Divider()
            rows += TreeRow.DoneHeader(
                rowKey = sectionKey,
                count = count,
                depth = 0,
                collapsed = sectionCollapsed,
            )
            if (!sectionCollapsed) {
                rows += orderedLevel(untaggedDone, order, sibling)
                    .map { TreeRow.Item(it, sibling) }
                donePrefixes.forEach { emitPrefix(it) }
            }
        }
        return rows
    }

    // Prefixes in the order the tree renders them. Used by filter chips so
    // they follow the board instead of an unrelated alphabetical sort.
    fun orderedPrefixes(tasks: List<Task>, order: TaskOrder): List<String> {
        val byPrefix = tasks.filter { it.parsed.prefix != null }
            .groupBy { it.parsed.prefix!! }
        return orderNodes(
            stored = order.groups.filter { it != TaskParser.INBOX },
            present = byPrefix.keys.toList(),
            keyOf = { prefix -> byPrefix.getValue(prefix).maxOf { activity(it) } },
        )
    }

    // Active boards split open and done tasks into two branches. Both use the
    // same stored order, so completing a task only moves it to the Done branch
    // and reopening puts it back where it was.
    private fun rowsActive(
        filtered: List<Task>,
        order: TaskOrder,
        query: String,
        collapsed: Set<String>,
    ): List<TreeRow> {
        val searching = query.isNotBlank()
        val rows = ArrayList<TreeRow>()
        val sibling = TaskOrder.groupKey(TaskParser.INBOX)

        val untagged = filtered.filter { it.parsed.prefix == null }
        val untaggedOpen = untagged.filterNot { it.done }
        val untaggedDone = untagged.filter { it.done }
        if (untaggedOpen.isNotEmpty()) {
            val untaggedCollapsed = TreeRow.UNTAGGED_KEY in collapsed && !searching
            rows += TreeRow.UntaggedHeader(
                count = untaggedOpen.size,
                collapsed = untaggedCollapsed,
            )
            if (!untaggedCollapsed) {
                rows += orderedLevel(untaggedOpen, order, sibling)
                    .map { TreeRow.Item(it, sibling) }
            }
        }

        val byPrefix = filtered.filter { it.parsed.prefix != null }
            .groupBy { it.parsed.prefix!! }
        val orderedPrefixes = orderNodes(
            stored = order.groups.filter { it != TaskParser.INBOX },
            present = byPrefix.keys.toList(),
            keyOf = { prefix -> byPrefix.getValue(prefix).maxOf { activity(it) } },
        )
        val openPrefixes = orderedPrefixes.filter { prefix ->
            byPrefix.getValue(prefix).any { !it.done }
        }
        val donePrefixes = orderedPrefixes.filter { prefix ->
            byPrefix.getValue(prefix).any { it.done }
        }

        fun splitTopics(tasks: List<Task>): Pair<List<Task>, Map<String, List<Task>>> =
            tasks.filter { it.parsed.topic == null } to
                tasks.filter { it.parsed.topic != null }.groupBy { it.parsed.topic!! }

        // The full level order keeps group/topic positions identical in the
        // open and done branches; each branch only filters what it renders.
        fun levelOrder(prefix: String): List<String> {
            val prefixTasks = byPrefix.getValue(prefix)
            val (allPlain, allTopics) = splitTopics(prefixTasks)
            val topicNodes = allTopics.keys.toList()
            val plainNodes = allPlain.map { TaskOrder.taskId(it.id) }
            val present = plainNodes + topicNodes.map { TaskOrder.topicChild(it) }
            val byNode = plainNodes.associateBy { it } +
                topicNodes.associate { TaskOrder.topicChild(it) to it }
            val base = if (searching) {
                autoSortSiblings(allPlain, allTopics, topicNodes)
            } else {
                orderNodes(
                    stored = order.children[prefix].orEmpty().filter { it in present },
                    present = present,
                    keyOf = { node ->
                        val topic = byNode[node]
                        if (topic == null) {
                            allPlain.firstOrNull { TaskOrder.taskId(it.id) == node }?.let { activity(it) } ?: 0L
                        } else {
                            allTopics[topic].orEmpty().maxOfOrNull { activity(it) } ?: 0L
                        }
                    },
                )
            }
            return blockOrder(TaskOrder.groupKey(prefix), base)
        }

        fun emitOpenTopic(prefix: String, topic: String, topicTasks: List<Task>) {
            if (topicTasks.isEmpty()) return
            val topicKey = TaskOrder.topicKey(prefix, topic)
            val topicCollapsed = topicKey in collapsed && !searching
            rows += TreeRow.TopicHeader(
                rowKey = topicKey,
                prefix = prefix,
                topic = topic,
                count = topicTasks.size,
                collapsed = topicCollapsed,
            )
            if (topicCollapsed) return
            rows += orderedLevel(topicTasks, order, topicKey)
                .map { TreeRow.Item(it, topicKey) }
        }

        fun emitOpenPrefix(prefix: String) {
            val prefixTasks = byPrefix.getValue(prefix)
            val openTasks = prefixTasks.filterNot { it.done }
            if (openTasks.isEmpty()) return
            val (plain, topics) = splitTopics(openTasks)
            val plainById = plain.associateBy { TaskOrder.taskId(it.id) }
            val siblingKey = TaskOrder.groupKey(prefix)
            val collapsedPrefix = siblingKey in collapsed && !searching
            rows += TreeRow.GroupHeader(
                rowKey = siblingKey,
                prefix = prefix,
                count = openTasks.size,
                collapsed = collapsedPrefix,
            )
            if (collapsedPrefix) return
            levelOrder(prefix).forEach { node ->
                if (node.startsWith("k:")) {
                    plainById[node]?.let { rows += TreeRow.Item(it, siblingKey) }
                } else {
                    val topic = node.removePrefix(TaskOrder.TOPIC)
                    topics[topic]?.let { emitOpenTopic(prefix, topic, it) }
                }
            }
        }

        fun emitDoneTopic(prefix: String, groupKey: String, topic: String, topicTasks: List<Task>) {
            if (topicTasks.isEmpty()) return
            val rowKey = TreeRow.DONE_TOPIC_PREFIX + prefix + "|" + topic
            val topicCollapsed = rowKey in collapsed && !searching
            rows += TreeRow.TopicHeader(
                rowKey = rowKey,
                prefix = prefix,
                topic = topic,
                count = topicTasks.size,
                collapsed = topicCollapsed,
                siblingKey = groupKey,
                doneSection = true,
            )
            if (topicCollapsed) return
            val itemLevel = TreeRow.DONE_TOPIC_PREFIX + prefix + "|" + topic
            rows += orderedLevel(topicTasks, order, TaskOrder.topicKey(prefix, topic))
                .map { TreeRow.Item(it, itemLevel, doneSection = true) }
        }

        fun emitDonePrefix(prefix: String) {
            val doneTasks = byPrefix.getValue(prefix).filter { it.done }
            if (doneTasks.isEmpty()) return
            val (plain, topics) = splitTopics(doneTasks)
            val plainById = plain.associateBy { TaskOrder.taskId(it.id) }
            val groupKey = TreeRow.DONE_GROUP_PREFIX + prefix
            val collapsedGroup = groupKey in collapsed && !searching
            rows += TreeRow.GroupHeader(
                rowKey = groupKey,
                prefix = prefix,
                count = doneTasks.size,
                collapsed = collapsedGroup,
                doneSection = true,
            )
            if (collapsedGroup) return
            levelOrder(prefix).forEach { node ->
                if (node.startsWith("k:")) {
                    plainById[node]?.let {
                        rows += TreeRow.Item(it, groupKey, doneSection = true)
                    }
                } else {
                    val topic = node.removePrefix(TaskOrder.TOPIC)
                    topics[topic]?.let { emitDoneTopic(prefix, groupKey, topic, it) }
                }
            }
        }

        openPrefixes.forEach { emitOpenPrefix(it) }

        if (untaggedDone.isNotEmpty() || donePrefixes.isNotEmpty()) {
            val sectionKey = TreeRow.DONE_PREFIX + "all"
            val sectionCollapsed = sectionKey in collapsed && !searching
            val count = untaggedDone.size + donePrefixes.sumOf { prefix ->
                byPrefix.getValue(prefix).count { it.done }
            }
            rows += TreeRow.Divider()
            rows += TreeRow.DoneHeader(
                rowKey = sectionKey,
                count = count,
                depth = 0,
                collapsed = sectionCollapsed,
            )
            if (!sectionCollapsed) {
                rows += orderedLevel(untaggedDone, order, sibling)
                    .map { TreeRow.Item(it, sibling, doneSection = true) }
                donePrefixes.forEach { emitDonePrefix(it) }
            }
        }
        return rows
    }

    // Group levels keep plain tasks above topic blocks; other sibling lists
    // are plain and return unchanged.
    fun blockOrder(siblingKey: String, nodes: List<String>): List<String> =
        if (siblingKey.startsWith(TaskOrder.PREFIX)) {
            nodes.filter { it.startsWith("k:") } + nodes.filterNot { it.startsWith("k:") }
        } else {
            nodes
        }

    // Done nodes of one sibling level: plain tasks that are done and topics
    // whose tasks are all done. Node ids match the sibling order ("k:<id>",
    // "t:<topic>"), so callers can use them against ordered sibling keys.
    fun doneNodes(tasks: List<Task>, siblingKey: String): Set<String> = when {
        siblingKey.startsWith(TaskOrder.TOPIC) -> {
            val scope = siblingKey.removePrefix(TaskOrder.TOPIC)
            val prefix = scope.substringBefore('|')
            val topic = scope.substringAfter('|')
            tasks.filter {
                (it.parsed.prefix ?: TaskParser.INBOX) == prefix &&
                    it.parsed.topic == topic &&
                    it.done
            }.map { TaskOrder.taskId(it.id) }.toSet()
        }
        siblingKey.startsWith(TaskOrder.PREFIX) -> {
            val prefix = siblingKey.removePrefix(TaskOrder.PREFIX)
            val scoped = tasks.filter { (it.parsed.prefix ?: TaskParser.INBOX) == prefix }
            buildSet {
                scoped.filter { it.parsed.topic == null && it.done }
                    .forEach { add(TaskOrder.taskId(it.id)) }
                scoped.filter { it.parsed.topic != null }
                    .groupBy { it.parsed.topic!! }
                    .forEach { (topic, group) ->
                        if (group.all { it.done }) add(TaskOrder.topicChild(topic))
                    }
            }
        }
        else -> emptySet()
    }

    private fun siblingKeyOf(
        item: String,
        plain: List<Task>,
        topics: Map<String, List<Task>>,
    ): Long = when {
        item.startsWith("k:") -> plain.firstOrNull { TaskOrder.taskId(it.id) == item }
            ?.let { activity(it) } ?: 0L
        else -> topics[item.removePrefix(TaskOrder.TOPIC)].orEmpty().maxOfOrNull { activity(it) } ?: 0L
    }

    private fun autoSortSiblings(
        plain: List<Task>,
        topics: Map<String, List<Task>>,
        topicNodes: List<String>,
    ): List<String> {
        val entries = plain.map { TaskOrder.taskId(it.id) to activity(it) } +
            topicNodes.map { topic ->
                TaskOrder.topicChild(topic) to (topics[topic].orEmpty().maxOfOrNull { activity(it) } ?: 0L)
            }
        return entries.sortedByDescending { it.second }.map { it.first }
    }

    // Ordered tasks for one level (a topic, or the untagged bucket). Manual
    // positions come from TaskOrder; done tasks always sit at the bottom.
    private fun orderedLevel(tasks: List<Task>, order: TaskOrder, siblingKey: String): List<Task> {
        val stored = when {
            siblingKey.startsWith(TaskOrder.TOPIC) ->
                order.tasks[siblingKey.removePrefix(TaskOrder.TOPIC)].orEmpty()
            siblingKey.startsWith(TaskOrder.PREFIX) ->
                order.children[siblingKey.removePrefix(TaskOrder.PREFIX)].orEmpty()
                    .mapNotNull { if (it.startsWith("k:")) it.removePrefix("k:") else null }
            else -> emptyList()
        }
        val ordered = orderNodes(
            stored = stored,
            present = tasks.map { it.id },
            keyOf = { id -> tasks.firstOrNull { it.id == id }?.let { activity(it) } ?: 0L },
        )
        val byId = tasks.associateBy { it.id }
        return ordered.mapNotNull { byId[it] }.sortedBy { if (it.done) 1 else 0 }
    }

    // Stored order wins; unknown nodes go first, newest activity first.
    private fun orderNodes(
        stored: List<String>,
        present: List<String>,
        keyOf: (String) -> Long,
    ): List<String> {
        val presentSet = present.toSet()
        val known = stored.filter { it in presentSet }.distinct()
        val unknown = presentSet.filter { it !in known }.sortedByDescending(keyOf)
        return unknown + known
    }

    // Automatic sibling order for a level, ignoring manual order. Used when a
    // drag happens on a level that has no stored order yet.
    fun autoSiblingOrder(
        tasks: List<Task>,
        siblingKey: String,
        archivedOnly: Boolean = false,
    ): List<String> {
        val scoped = tasks.filter { it.archived == archivedOnly }
        return when {
            siblingKey == TaskOrder.GROUPS -> scoped
                .groupBy { it.parsed.prefix ?: TaskParser.INBOX }
                .entries
                .sortedByDescending { entry -> entry.value.maxOfOrNull { activity(it) } ?: 0L }
                .map { it.key }

            siblingKey.startsWith(TaskOrder.PREFIX) -> {
                val prefix = siblingKey.removePrefix(TaskOrder.PREFIX)
                val prefixTasks = scoped.filter { (it.parsed.prefix ?: TaskParser.INBOX) == prefix }
                if (prefix == TaskParser.INBOX) {
                    val entries = prefixTasks.map { TaskOrder.taskId(it.id) to activity(it) }
                    entries.sortedWith(
                        compareByDescending<Pair<String, Long>> { it.second },
                    ).map { it.first }
                } else {
                    val plain = prefixTasks.filter { it.parsed.topic == null }
                    val topics = prefixTasks.filter { it.parsed.topic != null }
                        .groupBy { it.parsed.topic!! }
                    val entries = plain.map { TaskOrder.taskId(it.id) to activity(it) } +
                        topics.map { (topic, items) ->
                            TaskOrder.topicChild(topic) to items.maxOfOrNull { activity(it) }!!
                        }
                    entries.sortedByDescending { it.second }.map { it.first }
                }
            }

            siblingKey.startsWith(TaskOrder.TOPIC) -> {
                val scope = siblingKey.removePrefix(TaskOrder.TOPIC)
                val prefix = scope.substringBefore('|')
                val topic = scope.substringAfter('|')
                scoped.filter {
                    (it.parsed.prefix ?: TaskParser.INBOX) == prefix && it.parsed.topic == topic
                }.sortedWith(
                    compareBy<Task> { if (it.done) 1 else 0 }.thenByDescending { activity(it) },
                ).map { it.id }
            }

            else -> emptyList()
        }
    }
}

package io.github.tohrutodo.tohru.task

// Transient collapse during a header drag: the dragged header and every header
// currently on screen hide their children. The persisted collapse state is
// never touched, and headers outside the viewport are left alone, so the work
// stays bounded by what is visible.
object TreeCollapse {
    fun isHeader(row: TreeRow): Boolean = when (row) {
        is TreeRow.GroupHeader, is TreeRow.TopicHeader -> true
        is TreeRow.Item, is TreeRow.UntaggedHeader, is TreeRow.DoneHeader, is TreeRow.Divider -> false
    }

    fun isDescendant(row: TreeRow, nodeKey: String): Boolean {
        val sibling = row.siblingKey ?: return false
        return when {
            nodeKey.startsWith(TaskOrder.PREFIX) -> {
                val prefix = nodeKey.removePrefix(TaskOrder.PREFIX)
                sibling == nodeKey || sibling.startsWith(TaskOrder.TOPIC + prefix + "|")
            }
            nodeKey.startsWith(TaskOrder.TOPIC) -> sibling == nodeKey
            else -> false
        }
    }

    fun visible(rows: List<TreeRow>, collapsedKeys: Set<String>): List<TreeRow> {
        if (collapsedKeys.isEmpty()) return rows
        val kept = ArrayList<TreeRow>(rows.size)
        // A collapsed subtree is one contiguous run of rows, so a single
        // active key is enough: the scan stays linear instead of testing every
        // collapsed key against every row.
        var hiddenKey: String? = null
        var hiddenTopicPrefix: String? = null
        for (row in rows) {
            if (hiddenKey != null) {
                val sibling = row.siblingKey
                val inside = sibling != null &&
                    (sibling == hiddenKey ||
                        (hiddenTopicPrefix != null && sibling.startsWith(hiddenTopicPrefix)))
                if (inside) continue
                hiddenKey = null
                hiddenTopicPrefix = null
            }
            kept += row
            if (row is TreeRow.GroupHeader && row.rowKey in collapsedKeys) {
                hiddenKey = row.rowKey
                hiddenTopicPrefix = if (row.doneSection) {
                    TreeRow.DONE_TOPIC_PREFIX + row.prefix + "|"
                } else {
                    TaskOrder.TOPIC + row.prefix + "|"
                }
            } else if (row is TreeRow.TopicHeader && row.rowKey in collapsedKeys) {
                hiddenKey = row.rowKey
            }
        }
        return kept
    }

    // The row's ancestor that sits in [level]; a row already in that level is
    // its own block root. A topic or group moves together with its children.
    fun blockRoot(rowsByKey: Map<String, TreeRow>, rowKey: String, level: String): String? {
        var key: String? = rowKey
        var guard = 0
        while (key != null && guard++ < 8) {
            val row = rowsByKey[key] ?: return null
            if (row.siblingKey == level) return key
            key = row.siblingKey?.takeIf { it in rowsByKey }
        }
        return null
    }
}

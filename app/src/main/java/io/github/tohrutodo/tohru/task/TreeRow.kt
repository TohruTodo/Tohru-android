package io.github.tohrutodo.tohru.task

sealed interface TreeRow {
    val rowKey: String
    // Sibling list this row can be reordered in, or null when not draggable.
    val siblingKey: String?

    // Rows in the bottom Done section can be collapsed and selected, but they
    // mirror the open tree instead of owning a manual position of their own.
    val doneSection: Boolean get() = false
    val dragSiblingKey: String? get() = if (doneSection) null else siblingKey

    companion object {
        // Collapsible "done" section at the end of the tree, after a divider.
        const val DONE_PREFIX = "done:"
        const val DIVIDER_KEY = "done-divider"
        // Header of the block that holds tasks without a group.
        const val UNTAGGED_KEY = "u:inbox"
        const val DONE_GROUP_PREFIX = "done-group:"
        const val DONE_TOPIC_PREFIX = "done-topic:"
    }

    data class GroupHeader(
        override val rowKey: String,
        val prefix: String,
        val count: Int,
        val collapsed: Boolean,
        override val doneSection: Boolean = false,
    ) : TreeRow {
        override val siblingKey: String? = if (doneSection) null else TaskOrder.GROUPS
    }

    data class UntaggedHeader(
        override val rowKey: String = UNTAGGED_KEY,
        val count: Int,
        val collapsed: Boolean,
    ) : TreeRow {
        override val siblingKey: String? = null
    }

    data class TopicHeader(
        override val rowKey: String,
        val prefix: String,
        val topic: String,
        val count: Int,
        val collapsed: Boolean,
        override val siblingKey: String? = TaskOrder.groupKey(prefix),
        override val doneSection: Boolean = false,
    ) : TreeRow

    data class DoneHeader(
        override val rowKey: String,
        val count: Int,
        val depth: Int,
        val collapsed: Boolean,
    ) : TreeRow {
        override val siblingKey: String? = null
    }

    data class Divider(override val rowKey: String = DIVIDER_KEY) : TreeRow {
        override val siblingKey: String? = null
    }

    data class Item(
        val task: Task,
        override val siblingKey: String,
        override val doneSection: Boolean = false,
    ) : TreeRow {
        override val rowKey: String = "k:${task.id}"
    }
}

package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TreeCollapse
import io.github.tohrutodo.tohru.task.TreeRow

class TreeCollapseTest {
    private fun item(id: String, siblingKey: String) =
        TreeRow.Item(Task(id = id, title = id), siblingKey)

    private val rows = listOf(
        TreeRow.GroupHeader(rowKey = "p:a", prefix = "a", count = 3, collapsed = false),
        TreeRow.TopicHeader(rowKey = "t:a|one", prefix = "a", topic = "one", count = 1, collapsed = false),
        item("1", "t:a|one"),
        item("2", "p:a"),
        TreeRow.GroupHeader(rowKey = "p:b", prefix = "b", count = 1, collapsed = false),
        item("3", "p:b"),
    )
    private val byKey = rows.associateBy { it.rowKey }

    @Test fun visibleHidesEveryChildOfACollapsedGroup() {
        val visible = TreeCollapse.visible(rows, setOf("p:a"))
        assertEquals(listOf("p:a", "p:b", "k:3"), visible.map { it.rowKey })
    }

    @Test fun visibleHidesOnlyTheItemsOfACollapsedTopic() {
        val visible = TreeCollapse.visible(rows, setOf("t:a|one"))
        assertEquals(
            listOf("p:a", "t:a|one", "k:2", "p:b", "k:3"),
            visible.map { it.rowKey },
        )
    }

    @Test fun nestedCollapsesSkipTheWholeSubtree() {
        val visible = TreeCollapse.visible(rows, setOf("p:a", "t:a|one"))
        assertEquals(listOf("p:a", "p:b", "k:3"), visible.map { it.rowKey })
    }

    @Test fun similarlyNamedPrefixesAreNotDescendants() {
        val extra = listOf(
            TreeRow.GroupHeader(rowKey = "p:a", prefix = "a", count = 1, collapsed = false),
            item("1", "p:a"),
            TreeRow.GroupHeader(rowKey = "p:ab", prefix = "ab", count = 1, collapsed = false),
            item("2", "p:ab"),
        )
        assertEquals(
            listOf("p:a", "p:ab", "k:2"),
            TreeCollapse.visible(extra, setOf("p:a")).map { it.rowKey },
        )
    }

    @Test fun headersAreNotDescendantsOfThemselves() {
        assertTrue(TreeCollapse.isDescendant(rows[1], "p:a"))
        assertTrue(!TreeCollapse.isDescendant(rows[0], "p:a"))
        assertTrue(!TreeCollapse.isDescendant(rows[1], "t:a|one"))
        assertTrue(TreeCollapse.isDescendant(item("1", "t:a|one"), "t:a|one"))
    }

    @Test fun onlyGroupAndTopicRowsCountAsHeaders() {
        assertTrue(TreeCollapse.isHeader(rows[0]))
        assertTrue(TreeCollapse.isHeader(rows[1]))
        assertFalse(TreeCollapse.isHeader(item("1", "t:a|one")))
        assertFalse(TreeCollapse.isHeader(TreeRow.DoneHeader("done:all", 1, 1, true)))
        assertFalse(TreeCollapse.isHeader(TreeRow.Divider()))
    }

    @Test fun blockRootFindsTheAncestorInTheDraggedLevel() {
        // A topic's items belong to the topic block at the prefix level.
        assertEquals("t:a|one", TreeCollapse.blockRoot(byKey, "k:1", "p:a"))
        // A prefix-level plain task is its own block.
        assertEquals("k:2", TreeCollapse.blockRoot(byKey, "k:2", "p:a"))
        // At the group level a whole prefix, topic children included, is one block.
        assertEquals("p:a", TreeCollapse.blockRoot(byKey, "k:1", "groups"))
        assertEquals("p:a", TreeCollapse.blockRoot(byKey, "t:a|one", "groups"))
        assertEquals("p:a", TreeCollapse.blockRoot(byKey, "k:2", "groups"))
        assertEquals("p:b", TreeCollapse.blockRoot(byKey, "k:3", "groups"))
        // Inside a topic, its items are their own block.
        assertEquals("k:1", TreeCollapse.blockRoot(byKey, "k:1", "t:a|one"))
    }

    @Test fun blockRootIsNullOutsideTheLevel() {
        assertNull(TreeCollapse.blockRoot(byKey, "p:a", "p:a"))
        assertNull(TreeCollapse.blockRoot(byKey, "missing", "p:a"))
    }
}

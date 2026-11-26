package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskOrder
import io.github.tohrutodo.tohru.task.TaskSorting
import io.github.tohrutodo.tohru.task.TaskStats

class TaskSortingTest {
    private fun task(id: String, title: String, done: Boolean = false, updated: Long = 100) =
        Task(id = id, title = title, done = done, updated = updated)

    @Test fun prefixesFollowStoredGroupOrder() {
        val tasks = listOf(
            task("1", "p:open", updated = 100),
            task("2", "r:open", updated = 300),
            task("3", "q:done", done = true, updated = 200),
            task("4", "s:done", done = true, updated = 400),
        )
        val order = TaskOrder(groups = listOf("s", "q", "p", "r"))
        assertEquals(listOf("p", "r", "s", "q"), TaskSorting.prefixes(tasks, order))
    }

    @Test fun mixedPrefixKeepsOpenPosition() {
        val tasks = listOf(
            task("1", "p:open"),
            task("2", "p:done", done = true),
            task("3", "q:done", done = true),
        )
        assertEquals(listOf("p", "q"), TaskSorting.prefixes(tasks))
    }

    @Test fun statsSplitDoneAndOpen() {
        val tasks = listOf(
            task("1", "p:one"),
            task("2", "p:two", done = true),
            task("3", "plain"),
        )
        assertEquals(TaskStats(total = 3, done = 1, open = 2), TaskStats.of(tasks))
    }

    @Test fun pruneFilterDropsDeletedPrefix() {
        val tasks = listOf(task("1", "work:one"), task("2", "home:two"))
        assertEquals(
            setOf("work"),
            TaskSorting.prunePrefixFilter(setOf("work", "gone"), tasks),
        )
    }

    @Test fun statsSkipExcludedPrefix() {
        val tasks = listOf(
            task("1", "p:one"),
            task("2", "note:two"),
        )
        assertEquals(TaskStats(total = 1, done = 0, open = 1), TaskStats.of(tasks, setOf("note")))
    }
}

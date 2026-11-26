package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskOrder
import io.github.tohrutodo.tohru.task.TaskSorting
import io.github.tohrutodo.tohru.task.TaskTree
import io.github.tohrutodo.tohru.task.TreeRow

class TaskTreeTest {
    private val now = 1_000L

    private fun task(
        id: String,
        title: String,
        done: Boolean = false,
        created: Long = now,
        updated: Long = created,
        archived: Boolean = false,
    ) = Task(
        id = id,
        title = title,
        done = done,
        archived = archived,
        created = created,
        updated = updated,
    )

    @Test fun untaggedTasksComeFirstUnderTheirHeader() {
        val tasks = listOf(
            task("1", "work:later", created = 500),
            task("2", "plain", created = 10),
        )
        val rows = TaskTree.rows(tasks)
        assertEquals(listOf("u:inbox", "k:2", "p:work", "k:1"), rows.map { it.rowKey })
        val headings = rows.filterIsInstance<TreeRow.GroupHeader>().map { it.rowKey }
        assertEquals(listOf("p:work"), headings)
    }

    @Test fun untaggedBlockCollapsesLikeAGroup() {
        val tasks = listOf(
            task("1", "plain"),
            task("2", "p:work"),
        )
        assertEquals(
            listOf("u:inbox", "k:1", "p:p", "k:2"),
            TaskTree.rows(tasks).map { it.rowKey },
        )
        assertEquals(
            listOf("u:inbox", "p:p", "k:2"),
            TaskTree.rows(tasks, collapsed = setOf(TreeRow.UNTAGGED_KEY)).map { it.rowKey },
        )
    }

    @Test fun searchingKeepsTheUntaggedBlockOpen() {
        val tasks = listOf(
            task("1", "plain"),
            task("2", "p:work"),
        )
        val keys = TaskTree.rows(
            tasks,
            query = "plain",
            collapsed = setOf(TreeRow.UNTAGGED_KEY),
        ).map { it.rowKey }
        assertTrue("k:1" in keys)
    }

    @Test fun newerChildLiftsItsTopicAndGroup() {
        val tasks = listOf(
            task("a", "old(t1):a", created = 100),
            task("b", "old(t2):b", created = 200),
            task("c", "old(t1):c", created = 300),
            task("d", "new:other", created = 50),
        )
        assertEquals(
            listOf("p:old", "t:old|t1", "k:c", "k:a", "t:old|t2", "k:b", "p:new", "k:d"),
            TaskTree.rows(tasks).map { it.rowKey },
        )
    }

    @Test fun newestActivitySortsFirstInsideTopic() {
        val tasks = listOf(
            task("a", "p(t):old", created = 100),
            task("b", "p(t):new", created = 300),
            task("c", "p(t):edited", created = 50, updated = 900),
        )
        val rows = TaskTree.rows(tasks)
        val order = rows.filterIsInstance<TreeRow.Item>().map { it.task.id }
        assertEquals(listOf("c", "b", "a"), order)
    }

    @Test fun doneTasksDropBelowDigits() {
        val tasks = listOf(
            task("a", "p(t):done", done = true, created = 900),
            task("b", "p(t):open", created = 100),
        )
        val rows = TaskTree.rows(tasks)
        assertEquals(listOf("b", "a"), rows.filterIsInstance<TreeRow.Item>().map { it.task.id })
    }

    @Test fun topicScopedTasksStayInTheirTopic() {
        val tasks = listOf(
            task("a", "p(one):a", created = 100),
            task("b", "p(two):b", created = 200),
            task("c", "p:plain", created = 50),
        )
        val rows = TaskTree.rows(tasks)
        val keys = rows.map { it.rowKey }
        // Plain tasks come first, then topics by activity.
        assertEquals(
            listOf("p:p", "k:c", "t:p|two", "k:b", "t:p|one", "k:a"),
            keys,
        )
    }

    @Test fun plainTasksStayAboveTopicsWhateverTheStoredOrder() {
        val tasks = listOf(
            task("a", "p:plain", created = 50),
            task("b", "p(topic):topic", created = 300),
        )
        val order = TaskOrder(children = mapOf("p" to listOf("t:topic", "k:a")))
        assertEquals(
            listOf("p:p", "k:a", "t:p|topic", "k:b"),
            TaskTree.rows(tasks, order).map { it.rowKey },
        )
    }

    @Test fun doneUntaggedTasksLiveInTheBottomSection() {
        val tasks = listOf(
            task("1", "plainDone", done = true),
            task("2", "plainOpen"),
            task("3", "p:work"),
        )
        val rows = TaskTree.rows(tasks)
        assertEquals(
            listOf("u:inbox", "k:2", "p:p", "k:3", "done-divider", "done:all", "k:1"),
            rows.map { it.rowKey },
        )
        assertEquals(listOf("2", "3", "1"), rows.filterIsInstance<TreeRow.Item>().map { it.task.id })
    }

    @Test fun collapsedDoneSectionHidesItsContents() {
        val tasks = listOf(
            task("1", "plainDone", done = true),
            task("2", "plainOpen"),
            task("3", "closed:done", done = true),
        )
        val rows = TaskTree.rows(tasks, collapsed = setOf("done:all"))
        assertEquals(
            listOf("u:inbox", "k:2", "done-divider", "done:all"),
            rows.map { it.rowKey },
        )
    }

    @Test fun doneSectionShowsUntaggedAndDonePrefixes() {
        val tasks = listOf(
            task("1", "plainDone", done = true),
            task("2", "plainOpen"),
            task("3", "closed:done", done = true),
            task("4", "open:live"),
        )
        val rows = TaskTree.rows(tasks)
        assertEquals(
            listOf(
                "u:inbox", "k:2", "p:open", "k:4",
                "done-divider", "done:all", "k:1",
                "done-group:closed", "k:3",
            ),
            rows.map { it.rowKey },
        )
    }

    @Test fun searchingOpensTheDoneSection() {
        val tasks = listOf(
            task("a", "p(t):needle", done = true),
            task("b", "p(t):other", done = true),
        )
        val keys = TaskTree.rows(tasks, query = "needle", collapsed = setOf("done:all"))
            .map { it.rowKey }
        assertTrue("done:all" in keys)
        assertTrue("k:a" in keys)
    }

    @Test fun manualOrderWinsWithinTopic() {
        val tasks = listOf(
            task("a", "p(t):a", created = 300),
            task("b", "p(t):b", created = 200),
            task("c", "p(t):c", created = 100),
        )
        val order = TaskOrder(tasks = mapOf("p|t" to listOf("c", "a", "b")))
        val rows = TaskTree.rows(tasks, order)
        assertEquals(listOf("c", "a", "b"), rows.filterIsInstance<TreeRow.Item>().map { it.task.id })
    }

    @Test fun newTaskLandsOnTopOfManualOrder() {
        val tasks = listOf(
            task("a", "p(t):a", created = 300),
            task("b", "p(t):b", created = 200),
            task("fresh", "p(t):fresh", created = 900),
        )
        val order = TaskOrder(tasks = mapOf("p|t" to listOf("b", "a")))
        val rows = TaskTree.rows(tasks, order)
        assertEquals(
            listOf("fresh", "b", "a"),
            rows.filterIsInstance<TreeRow.Item>().map { it.task.id },
        )
    }

    @Test fun collapsedPrefixHidesChildren() {
        val tasks = listOf(task("a", "p(t):a"), task("b", "other:b"))
        val rows = TaskTree.rows(
            tasks,
            collapsed = setOf("p:p", "p:other", "t:p|t"),
        )
        assertEquals(listOf("p:p", "p:other"), rows.map { it.rowKey })
    }

    @Test fun searchExpandsCollapsedBranchesAndFilters() {
        val tasks = listOf(
            task("a", "p(t):alpha"),
            task("b", "p(t):beta"),
            task("c", "other:gamma"),
        )
        val rows = TaskTree.rows(tasks, query = "alp", collapsed = setOf("p:p", "t:p|t"))
        assertEquals(listOf("p:p", "t:p|t", "k:a"), rows.map { it.rowKey })
    }

    @Test fun searchIsCaseInsensitiveButKeepsDiacritics() {
        val tasks = listOf(
            task("a", "p:Đổi màu"),
            task("b", "p:doi mau"),
        )
        assertEquals(
            listOf("a"),
            TaskTree.rows(tasks, query = "ĐỔI").filterIsInstance<TreeRow.Item>().map { it.task.id },
        )
        assertEquals(
            listOf("b"),
            TaskTree.rows(tasks, query = "doi").filterIsInstance<TreeRow.Item>().map { it.task.id },
        )
    }

    @Test fun prefixFilterKeepsMatchingBranches() {
        val tasks = listOf(task("a", "p:a"), task("b", "other:b"))
        val rows = TaskTree.rows(tasks, prefixes = setOf("other"))
        assertEquals(listOf("p:other", "k:b"), rows.map { it.rowKey })
    }

    @Test fun archiveBuildsItsOwnTree() {
        val tasks = listOf(
            task("a", "p(t):a", archived = true),
            task("b", "p:plain"),
        )
        val rows = TaskTree.rows(tasks, archivedOnly = true)
        assertEquals(listOf("p:p", "t:p|t", "k:a"), rows.map { it.rowKey })
    }

    @Test fun mixedPrefixAppearsInBothBranches() {
        val tasks = listOf(
            task("a", "p(t):done", done = true, created = 300),
            task("b", "p(t):open", created = 200),
            task("c", "p:plainDone", done = true, created = 100),
            task("d", "p:plainOpen", created = 50),
            task("e", "q:allDone", done = true, created = 400),
        )
        val rows = TaskTree.rows(tasks)
        assertEquals(
            listOf(
                "p:p", "k:d", "t:p|t", "k:b",
                "done-divider", "done:all",
                "done-group:q", "k:e",
                "done-group:p", "k:c", "done-topic:p|t", "k:a",
            ),
            rows.map { it.rowKey },
        )
        assertEquals(rows.map { it.rowKey }.size, rows.map { it.rowKey }.distinct().size)
        assertEquals(2, rows.filterIsInstance<TreeRow.GroupHeader>().first { it.prefix == "p" }.count)
        assertEquals(
            2,
            rows.filterIsInstance<TreeRow.GroupHeader>()
                .first { it.rowKey == "done-group:p" }.count,
        )
    }

    @Test fun doneCollapseIsIndependentFromOpenCollapse() {
        val tasks = listOf(
            task("a", "p:done", done = true, created = 200),
            task("b", "p:open", created = 100),
        )
        assertEquals(
            listOf("p:p", "done-divider", "done:all", "done-group:p", "k:a"),
            TaskTree.rows(tasks, collapsed = setOf("p:p")).map { it.rowKey },
        )
        assertEquals(
            listOf("p:p", "k:b", "done-divider", "done:all", "done-group:p"),
            TaskTree.rows(tasks, collapsed = setOf("done-group:p")).map { it.rowKey },
        )
    }

    @Test fun doneTopicCollapsesSeparately() {
        val tasks = listOf(
            task("a", "p(t):doneA", done = true, created = 300),
            task("b", "p(t):doneB", done = true, created = 200),
            task("c", "p:open", created = 100),
        )
        assertEquals(
            listOf("p:p", "k:c", "done-divider", "done:all", "done-group:p", "done-topic:p|t"),
            TaskTree.rows(tasks, collapsed = setOf("done-topic:p|t")).map { it.rowKey },
        )
    }

    @Test fun collapsingDoneGroupHidesNestedDoneTopics() {
        val tasks = listOf(
            task("a", "p(t):doneTopic", done = true, created = 300),
            task("b", "p:donePlain", done = true, created = 200),
            task("c", "p:open", created = 100),
        )
        assertEquals(
            listOf("p:p", "k:c", "done-divider", "done:all", "done-group:p"),
            TaskTree.rows(tasks, collapsed = setOf("done-group:p")).map { it.rowKey },
        )
    }

    @Test fun doneTopicKeepsStoredOrder() {
        val tasks = listOf(
            task("a", "p(t):a", done = true, created = 100),
            task("b", "p(t):b", done = true, created = 200),
            task("c", "p(t):c", done = true, created = 300),
        )
        val order = TaskOrder(tasks = mapOf("p|t" to listOf("c", "a", "b")))
        val done = TaskTree.rows(tasks, order)
            .filterIsInstance<TreeRow.Item>().map { it.task.id }
        assertEquals(listOf("c", "a", "b"), done)
    }

    @Test fun alphabeticalGroupsDoneLast() {
        val tasks = listOf(
            task("a", "p:zeta"),
            task("b", "p:alpha"),
            task("c", "p:beta", done = true),
        )
        assertEquals(
            listOf("alpha", "zeta", "beta"),
            TaskSorting.alphabetical(tasks).map { it.parsed.body },
        )
    }

    @Test fun allDonePrefixesSinkBelowOpenOnes() {
        val tasks = listOf(
            task("a", "closed:one", done = true, created = 300),
            task("b", "open:two", created = 200),
            task("c", "open2:three", created = 100),
            task("d", "also:four", done = true, created = 50),
        )
        assertEquals(
            listOf(
                "p:open", "k:b", "p:open2", "k:c",
                "done-divider", "done:all",
                "done-group:closed", "k:a", "done-group:also", "k:d",
            ),
            TaskTree.rows(tasks).map { it.rowKey },
        )
    }

    @Test fun allDoneTopicSinksInsideItsPrefix() {
        val tasks = listOf(
            task("a", "p(done):x", done = true, created = 300),
            task("b", "p(open):y", created = 200),
            task("c", "p:plain", created = 100),
        )
        assertEquals(
            listOf(
                "p:p", "k:c", "t:p|open", "k:b",
                "done-divider", "done:all",
                "done-group:p", "done-topic:p|done", "k:a",
            ),
            TaskTree.rows(tasks).map { it.rowKey },
        )
    }

    @Test fun topicSiblingsSortByTheirNewestTask() {
        val tasks = listOf(
            task("a", "p:plain", created = 100),
            task("b", "p(topic):newer", created = 200),
        )
        assertEquals(
            listOf("p:p", "k:a", "t:p|topic", "k:b"),
            TaskTree.rows(tasks).map { it.rowKey },
        )
    }
}

package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskSelection
import io.github.tohrutodo.tohru.task.SelectionScope
import io.github.tohrutodo.tohru.task.TreeRow

class TaskSelectionTest {
    private val tasks = listOf(
        Task(id = "1", title = "aniyt(back):one"),
        Task(id = "2", title = "aniyt(ui):two"),
        Task(id = "3", title = "work:three"),
        Task(id = "4", title = "plain"),
    )

    @Test fun taskNodeSelectsOnlyItself() {
        assertEquals(
            setOf("2"),
            TaskSelection.taskIds(setOf("k:2"), tasks),
        )
    }

    @Test fun topicNodeSelectsItsMembers() {
        assertEquals(
            setOf("1"),
            TaskSelection.taskIds(setOf("t:aniyt|back"), tasks),
        )
    }

    @Test fun prefixNodeSelectsWholeSubtree() {
        assertEquals(
            setOf("1", "2"),
            TaskSelection.taskIds(setOf("p:aniyt"), tasks),
        )
    }

    @Test fun untaggedBucketSelectsPlainItems() {
        assertEquals(
            setOf("4"),
            TaskSelection.taskIds(setOf("p:inbox"), tasks),
        )
    }

    @Test fun mixedNodesUnion() {
        assertEquals(
            setOf("1", "3"),
            TaskSelection.taskIds(setOf("t:aniyt|back", "k:3"), tasks),
        )
    }

    @Test fun allSelectedNeedsEveryTask() {
        assertEquals(false, TaskSelection.allSelected(emptySet(), tasks))
        assertEquals(false, TaskSelection.allSelected(setOf("1", "2", "3"), tasks))
        assertEquals(true, TaskSelection.allSelected(TaskSelection.idsOf(tasks), tasks))
    }

    @Test fun allSelectedIgnoresEmptyScope() {
        assertEquals(false, TaskSelection.allSelected(setOf("1"), emptyList()))
    }

    @Test fun selectedIdsKeepsOnlyIdsInScope() {
        assertEquals(
            setOf("1", "3"),
            TaskSelection.selectedIds(setOf("1", "3", "missing"), tasks),
        )
    }

    @Test fun untaggedHeaderSelectsAllUngroupedTasks() {
        val list = listOf(
            Task(id = "1", title = "plain", done = true),
            Task(id = "2", title = "work:one"),
            Task(id = "3", title = "other:two"),
        )
        assertEquals(setOf("1"), TaskSelection.idsForNode(TreeRow.UNTAGGED_KEY, list))
    }

    private val doneTasks = listOf(
        Task(id = "0", title = "plainDone", done = true),
        Task(id = "1", title = "p(t):done", done = true),
        Task(id = "2", title = "p(t):open"),
        Task(id = "3", title = "p:plainDone", done = true),
        Task(id = "5", title = "q:done", done = true),
    )

    @Test fun doneSectionSelectsEveryDoneTask() {
        assertEquals(setOf("0", "1", "3", "5"), TaskSelection.idsForNode("done:all", doneTasks))
    }

    @Test fun doneGroupSelectsItsDoneTasks() {
        assertEquals(setOf("1", "3"), TaskSelection.idsForNode("done-group:p", doneTasks))
    }

    @Test fun doneTopicSelectsItsDoneTasks() {
        assertEquals(setOf("1"), TaskSelection.idsForNode("done-topic:p|t", doneTasks))
    }

    @Test fun openPrefixSelectionExcludesDone() {
        assertEquals(
            setOf("2"),
            TaskSelection.idsForNode("p:p", doneTasks, SelectionScope.OPEN),
        )
    }

    @Test fun openTopicSelectionExcludesDone() {
        assertEquals(
            setOf("2"),
            TaskSelection.idsForNode("t:p|t", doneTasks, SelectionScope.OPEN),
        )
    }

    @Test fun archiveScopeStillSelectsDone() {
        assertEquals(
            setOf("1", "2", "3"),
            TaskSelection.idsForNode("p:p", doneTasks, SelectionScope.ALL),
        )
    }

    @Test fun openUntaggedHeaderExcludesDone() {
        val list = listOf(
            Task(id = "1", title = "plain", done = true),
            Task(id = "2", title = "plain"),
        )
        assertEquals(setOf("2"), TaskSelection.idsForNode(TreeRow.UNTAGGED_KEY, list, SelectionScope.OPEN))
    }
}

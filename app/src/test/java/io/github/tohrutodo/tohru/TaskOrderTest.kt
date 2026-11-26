package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.task.OrderFile
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskOrder

class TaskOrderTest {
    private val sample = TaskOrder(
        groups = listOf("aniyt", "work"),
        children = mapOf("aniyt" to listOf("k:1", "t:back"), "work" to listOf("k:2")),
        tasks = mapOf("aniyt|back" to listOf("3", "4")),
    )

    @Test fun roundTripKeepsEveryLevel() {
        assertEquals(sample, OrderFile.parse(OrderFile.serialize(sample)))
    }

    @Test fun malformedInputFallsBackToEmpty() {
        assertEquals(TaskOrder(), OrderFile.parse(""))
        assertEquals(TaskOrder(), OrderFile.parse("{oops"))
        assertEquals(TaskOrder(), OrderFile.parse("[]"))
    }

    @Test fun siblingKeysReadAndWrite() {
        val updated = sample
            .withSibling(TaskOrder.GROUPS, listOf("work", "aniyt"))
            .withSibling(TaskOrder.groupKey("work"), listOf("k:9"))
            .withSibling(TaskOrder.topicKey("aniyt", "back"), listOf("4", "3"))
        assertEquals(listOf("work", "aniyt"), updated.forSibling(TaskOrder.GROUPS))
        assertEquals(listOf("k:9"), updated.forSibling(TaskOrder.groupKey("work")))
        assertEquals(listOf("4", "3"), updated.forSibling(TaskOrder.topicKey("aniyt", "back")))
    }

    @Test fun pruneDropsMissingIds() {
        val pruned = sample.prune(
            validTasks = setOf("1", "2"),
            validPrefixes = setOf("aniyt", "work"),
            validTopics = emptySet(),
        )
        assertTrue(pruned.children.getValue("aniyt").contains("k:1"))
        assertFalse(pruned.children.getValue("aniyt").contains("t:back"))
        assertTrue(pruned.tasks.isEmpty())
    }

    @Test fun escapingSurvivesQuotes() {
        val tricky = TaskOrder(groups = listOf("a\"b", "c\\d"))
        assertEquals(tricky, OrderFile.parse(OrderFile.serialize(tricky)))
    }

    @Test fun activityUsesLatestEdit() {
        val task = Task(id = "x", title = "t", created = 10, updated = 99)
        assertEquals(99L, io.github.tohrutodo.tohru.task.TaskTree.activity(task))
    }
}

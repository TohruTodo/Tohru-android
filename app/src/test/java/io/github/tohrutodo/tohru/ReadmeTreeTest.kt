package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.task.ReadmeDoc
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.ui.buildReadmeTree

class ReadmeTreeTest {
    private val tasks = listOf(
        Task(id = "1-0001", title = "tohru: việc"),
        Task(id = "2-0002", title = "tohru(đồng bộ): việc"),
    )

    @Test fun treeFollowsFoldersAndTaskLabels() {
        val docs = listOf(
            ReadmeDoc(emptyList(), "repo"),
            ReadmeDoc(listOf("tohru"), "group"),
        )

        val root = buildReadmeTree(docs, tasks).single()

        assertEquals("Repo", root.label)
        assertEquals("repo", root.doc?.body)
        val group = root.children.single()
        assertEquals("tohru", group.label)
        assertEquals("group", group.doc?.body)
        val topic = group.children.single()
        assertEquals("đồng bộ", topic.label)
        assertNull(topic.doc)
    }

    @Test fun queryKeepsMatchingBranches() {
        val docs = listOf(
            ReadmeDoc(emptyList(), "repo"),
            ReadmeDoc(listOf("tohru", "đồng_bộ"), "sync notes"),
        )

        val root = buildReadmeTree(docs, tasks, "sync").single()

        val group = root.children.single()
        assertEquals("tohru", group.label)
        assertNull(group.doc)
        assertEquals("sync notes", group.children.single().doc?.body)
    }

    @Test fun unmatchedQueryHidesTheTree() {
        val docs = listOf(ReadmeDoc(emptyList(), "repo"))
        assertTrue(buildReadmeTree(docs, tasks, "không có").isEmpty())
    }
}

package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.sync.ConflictSide
import io.github.tohrutodo.tohru.sync.TaskMerge
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskFile

class TaskMergeTest {
    private fun task(id: String, title: String, updated: Long, body: String = "") =
        TaskFile.serialize(Task(id = id, title = title, updated = updated, created = updated, body = body))

    @Test fun newerRemoteVersionWins() {
        val local = task("1-0001", "local", updated = 1_000)
        val remote = task("1-0001", "remote", updated = 2_000)
        assertEquals(ConflictSide.REMOTE, TaskMerge.suggest(local, remote))
    }

    @Test fun newerLocalVersionWins() {
        val local = task("1-0001", "local", updated = 3_000)
        val remote = task("1-0001", "remote", updated = 2_000)
        assertEquals(ConflictSide.LOCAL, TaskMerge.suggest(local, remote))
    }

    @Test fun equalTimestampsKeepLocal() {
        val local = task("1-0001", "local", updated = 2_000)
        val remote = task("1-0001", "remote", updated = 2_000)
        assertEquals(ConflictSide.LOCAL, TaskMerge.suggest(local, remote))
    }

    @Test fun unreadableContentKeepsLocal() {
        assertEquals(ConflictSide.LOCAL, TaskMerge.suggest("not a task", "also not a task"))
    }

    @Test fun missingLocalSideTakesRemote() {
        assertEquals(ConflictSide.REMOTE, TaskMerge.suggest(null, "not a task"))
    }

    @Test fun missingRemoteSideKeepsLocal() {
        assertEquals(ConflictSide.LOCAL, TaskMerge.suggest("not a task", null))
    }

    @Test fun describeReadsTitleAndBodyPreview() {
        val side = TaskMerge.describe(task("1-0001", "title one", 1_500, body = "line a\nline b"))
        assertTrue(side.exists)
        assertEquals("title one", side.title)
        assertEquals(1_500L, side.updated)
        assertTrue(side.preview.contains("line a"))
    }

    @Test fun describeMissingSide() {
        val side = TaskMerge.describe(null)
        assertFalse(side.exists)
    }

    @Test fun validCustomTaskHasNoReason() {
        val text = task("1-0001", "title", 1_000)
        assertNull(TaskMerge.invalidReasonRes("tasks/1-0001.md", text))
    }

    @Test fun invalidFrontmatterHasReason() {
        assertNotNull(TaskMerge.invalidReasonRes("tasks/1-0001.md", "not a task"))
    }

    @Test fun mismatchedIdHasReason() {
        val text = task("2-0002", "title", 1_000)
        assertNotNull(TaskMerge.invalidReasonRes("tasks/1-0001.md", text))
    }
}

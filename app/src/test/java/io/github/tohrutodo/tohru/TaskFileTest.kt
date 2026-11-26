package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskFile
import io.github.tohrutodo.tohru.task.TaskState

class TaskFileTest {
    @Test fun roundTripKeepsFields() {
        val task = Task(
            id = "1-0001", title = "aniyt:add zero entry",
            done = true, archived = true, created = 10L, updated = 20L, body = "line1\nline2",
        )
        val loaded = TaskFile.deserialize(TaskFile.serialize(task), TaskState(done = true, archived = true))
        assertEquals(task, loaded)
    }

    @Test fun malformedOrIncompleteFrontmatterIsRejected() {
        val incomplete = """
            ---
            id: 2-0002
            title: plain title
            done: false
            updated: 6
            ---
            body
        """.trimIndent()
        assertNull(TaskFile.deserialize(incomplete))
    }

    @Test fun serializationOmitsStateFields() {
        val text = TaskFile.serialize(
            Task(id = "3-0003", title = "x", done = true, archived = true),
        )
        assertFalse(text.contains("done:"))
        assertFalse(text.contains("archived:"))
    }

    @Test fun stateComesFromThePath() {
        assertEquals(TaskState(), TaskFile.stateOf("g/t/1.md"))
        assertEquals(TaskState(done = true), TaskFile.stateOf("_done/g/t/1.md"))
        assertEquals(TaskState(archived = true), TaskFile.stateOf("_archived/g/t/1.md"))
        assertEquals(
            TaskState(done = true, archived = true),
            TaskFile.stateOf("_archived/_done/g/t/1.md"),
        )
    }

    @Test fun slugKeepsUnicodeAndIsBounded() {
        val slug = TaskFile.slug("Nhóm Công Việc: Báo cáo tuần")
        assertEquals("nhóm_công_việc_báo_cáo_tuần", slug)
        assertTrue(slug.length <= 48)
    }

    @Test fun slugNormalizesToNfc() {
        val composed = Normalizer.normalize("Nhóm Công Việc", Normalizer.Form.NFC)
        val decomposed = Normalizer.normalize("Nhóm Công Việc", Normalizer.Form.NFD)
        assertTrue(composed != decomposed)
        assertEquals(TaskFile.slug(composed), TaskFile.slug(decomposed))
    }

    @Test fun fileNameUsesIdAndSlug() {
        val task = Task(id = "1-0001", title = "Nhóm Công Việc")
        assertEquals("1-0001_nhóm_công_việc.md", TaskFile.fileName(task))
    }

    @Test fun relativePathMirrorsGroupTopicAndState() {
        val open = Task(id = "1-0001", title = "Nhóm_Công_Việc(báo cáo): Việc")
        assertEquals(
            "nhóm_công_việc/báo_cáo/1-0001_nhóm_công_việc_báo_cáo_việc.md",
            TaskFile.relativePath(open),
        )
        assertEquals(
            "_done/nhóm_công_việc/báo_cáo/1-0001_nhóm_công_việc_báo_cáo_việc.md",
            TaskFile.relativePath(open.copy(done = true)),
        )
        // Archived tasks keep their done state in a nested folder.
        assertEquals(
            "_archived/_done/nhóm_công_việc/báo_cáo/1-0001_nhóm_công_việc_báo_cáo_việc.md",
            TaskFile.relativePath(open.copy(done = true, archived = true)),
        )
    }

    @Test fun relativePathLeavesUngroupedTasksAtTheRoot() {
        assertEquals("1-0001_one.md", TaskFile.relativePath(Task(id = "1-0001", title = "one")))
        assertEquals(
            "_done/1-0001_one.md",
            TaskFile.relativePath(Task(id = "1-0001", title = "one", done = true)),
        )
    }

    @Test fun linkIsRelativeToTheSourceDirectory() {
        assertEquals(
            "../other/2-0002_two.md",
            TaskFile.link("group/1-0001_one.md", "other/2-0002_two.md"),
        )
        assertEquals(
            "_done/2-0002_two.md",
            TaskFile.link("group/topic/1-0001_one.md", "group/topic/_done/2-0002_two.md"),
        )
        assertEquals(
            "../../../2-0002_two.md",
            TaskFile.link("group/topic/_done/1-0001_one.md", "2-0002_two.md"),
        )
        assertEquals(
            "2-0002_two.md",
            TaskFile.link("1-0001_one.md", "2-0002_two.md"),
        )
    }

    @Test fun malformedReturnsNull() {
        assertNull(TaskFile.deserialize("no frontmatter"))
        assertNull(TaskFile.deserialize("---\ntitle: x\n"))
    }
}

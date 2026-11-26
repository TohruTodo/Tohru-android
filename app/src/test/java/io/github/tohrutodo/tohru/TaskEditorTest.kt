package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskEditor

class TaskEditorTest {
    @Test fun unchangedDraftIsNotDirty() {
        val loaded = Task(id = "x", title = "a", body = "b")
        assertFalse(TaskEditor.needsSave(loaded, "a", "b"))
        assertFalse(TaskEditor.needsSave(loaded, " a ", "b"))
        assertTrue(TaskEditor.needsSave(loaded, "a2", "b"))
        assertTrue(TaskEditor.needsSave(loaded, "a", "b\nc"))
        assertFalse(TaskEditor.needsSave(null, "a", "b"))
    }

    @Test fun draftTrimsTitleOnly() {
        val loaded = Task(id = "x", title = "a")
        val draft = TaskEditor.draft(loaded, "  new title  ", "body\n")
        assertEquals("new title", draft.title)
        assertEquals("body\n", draft.body)
    }
}

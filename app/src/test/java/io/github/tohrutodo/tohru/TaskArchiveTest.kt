package io.github.tohrutodo.tohru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.tohrutodo.tohru.task.OrderFile
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskArchive
import io.github.tohrutodo.tohru.task.TaskFile
import io.github.tohrutodo.tohru.task.TaskOrder
import java.io.File
import java.util.zip.ZipFile

class TaskArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun exportAndReadRoundTrip() {
        val tasks = folder.newFolder("tasks")
        File(tasks, "1-0001_one.md").writeText(TaskFile.serialize(Task(id = "1-0001", title = "one")))
        File(tasks, "_order.json").writeText(OrderFile.serialize(TaskOrder(groups = listOf("one"))))
        val zip = File(folder.root, "out.zip")
        assertEquals(1, TaskArchive.export(tasks, zip))
        val imported = TaskArchive.read(zip)
        assertEquals("1-0001", imported.tasks.single().id)
        assertTrue(imported.orderText!!.contains("one"))
    }

    @Test fun exportMirrorsTheFolderLayout() {
        val tasks = folder.newFolder("tree-tasks")
        val name = "1-0001_nhóm_công_việc_báo_cáo_việc.md"
        val dir = File(tasks, "nhóm_công_việc/báo_cáo/_done").apply { mkdirs() }
        File(dir, name).writeText(
            TaskFile.serialize(
                Task(id = "1-0001", title = "Nhóm Công Việc(báo cáo): Việc", done = true),
            ),
        )
        val zip = File(folder.root, "tree.zip")
        assertEquals(1, TaskArchive.export(tasks, zip))
        ZipFile(zip).use { entries ->
            assertNotNull(entries.getEntry("tasks/nhóm_công_việc/báo_cáo/_done/$name"))
        }
    }
}

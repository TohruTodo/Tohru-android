package io.github.tohrutodo.tohru

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.tohrutodo.tohru.io.AtomicFiles
import io.github.tohrutodo.tohru.store.TaskStore
import io.github.tohrutodo.tohru.sync.GitSync
import io.github.tohrutodo.tohru.sync.ConflictChoice
import io.github.tohrutodo.tohru.sync.ConflictResolution
import io.github.tohrutodo.tohru.sync.ConflictSession
import io.github.tohrutodo.tohru.sync.PullOutcome
import io.github.tohrutodo.tohru.sync.SyncState
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskOrder
import io.github.tohrutodo.tohru.task.TaskArchive
import io.github.tohrutodo.tohru.task.TaskFile
import io.github.tohrutodo.tohru.task.TaskTree
import io.github.tohrutodo.tohru.task.TreeRow

class TaskStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var tasksDir: java.io.File
    private lateinit var repoDir: java.io.File

    private fun store(withExisting: Boolean = false): TaskStore {
        tasksDir = folder.newFolder("tasks")
        repoDir = folder.newFolder("repo")
        val store = TaskStore(tasksDir)
        if (withExisting) {
            runBlocking { store.save(Task(id = "1-0001", title = "aniyt:one")) }
        }
        return store
    }

    private fun levelKeys(store: TaskStore, siblingKey: String): List<String> =
        TaskTree.rows(store.tasks.value, store.order.value)
            .filter { it.siblingKey == siblingKey }
            .map { it.rowKey }

    @Test fun refreshReportsInvalidFile() = runBlocking {
        val dir = folder.newFolder("invalid-tasks")
        val store = TaskStore(dir)
        val file = java.io.File(dir, "bad.md").apply { writeText("not frontmatter") }
        val event = async(start = CoroutineStart.UNDISPATCHED) { store.events.first() }

        store.refresh()

        val reported = event.await()
        assertTrue(reported is io.github.tohrutodo.tohru.store.TaskStoreEvent.InvalidFile)
        assertTrue(file.exists())
        assertTrue(store.tasks.value.isEmpty())
    }

    @Test fun refreshIgnoresBlankTaskInPlace() = runBlocking {
        val store = store()
        val file = java.io.File(tasksDir, "blank.md")
        file.writeText(TaskFile.serialize(Task(id = "blank-1", title = "   ")))
        store.refresh()
        assertTrue(store.tasks.value.isEmpty())
        assertTrue(file.exists())
    }

    @Test fun renamingUpdatesRelativeLinksAndIndex() = runBlocking {
        val store = store()
        store.save(Task(id = "target-1", title = "Target"))
        store.save(Task(id = "source-1", title = "Source", body = "See [Target](target-1_target.md)"))
        assertEquals("target-1_target.md", store.links.value.path("target-1"))

        store.save(Task(id = "target-1", title = "Renamed"))

        assertFalse(java.io.File(tasksDir, "target-1_target.md").exists())
        assertTrue(java.io.File(tasksDir, "target-1_renamed.md").isFile)
        assertEquals(
            "See [Renamed](target-1_renamed.md)",
            store.tasks.value.first { it.id == "source-1" }.body,
        )
        assertEquals("target-1", store.links.value.idFor("target-1_renamed.md"))
        assertEquals(null, store.links.value.idFor("target-1_target.md"))
    }

    @Test fun savingNotifiesChangedCallback() = runBlocking {
        val dir = folder.newFolder("notify-tasks")
        var changes = 0
        val store = TaskStore(dir, onChanged = { changes++ })
        store.save(Task(id = "notify-1", title = "one"))
        assertEquals(1, changes)
        store.save(store.tasks.value.single().copy(body = "changed"))
        assertEquals(2, changes)
        store.save(store.tasks.value.single())
        assertEquals(2, changes)
    }

    @Test fun savingBlankTitleDoesNotWrite() = runBlocking {
        val store = store()
        store.save(Task(id = "blank-save", title = "   "))
        assertTrue(store.tasks.value.isEmpty())
        assertTrue(tasksDir.listFiles().orEmpty().none { it.name.startsWith("blank-save") })
    }

    @Test fun refreshOverwritesDuplicateIdsWithNewest() = runBlocking {
        val store = store()
        val older = java.io.File(tasksDir, "dup-1_older.md")
        val newer = java.io.File(tasksDir, "dup-1_newer.md")
        older.writeText(TaskFile.serialize(Task(id = "dup-1", title = "older", updated = 100)))
        newer.writeText(TaskFile.serialize(Task(id = "dup-1", title = "newer", updated = 200)))
        store.refresh()
        assertEquals("newer", store.tasks.value.single().title)
        assertFalse(older.exists())
        assertTrue(java.io.File(tasksDir, "dup-1_newer.md").isFile)
    }

    @Test fun savingIdenticalContentDoesNotTouchTimestamp() = runBlocking {
        val store = store()
        store.save(Task(id = "same-1", title = "same"))
        val before = store.tasks.value.single()
        store.save(before.copy(title = before.title, body = before.body))
        assertEquals(before.updated, store.tasks.value.single().updated)
    }

    @Test fun saveWritesOneFilePerTask() = runBlocking {
        val store = store()
        store.save(Task(id = "2-0002", title = "plain"))
        assertEquals(1, store.tasks.value.size)
        assertEquals("plain", store.tasks.value.first().title)
        assertTrue(java.io.File(tasksDir, "2-0002_plain.md").isFile)
    }

    @Test fun readmesAreSkippedAndExposed() = runBlocking {
        val store = store()
        java.io.File(tasksDir, "README.md").writeText("root doc")
        java.io.File(tasksDir, "g").mkdirs()
        java.io.File(tasksDir, "g/README.md").writeText("group doc")

        store.refresh()

        assertTrue(store.tasks.value.isEmpty())
        assertEquals(
            listOf("README.md", "g/README.md"),
            store.readmes.value.map { it.path },
        )
    }

    @Test fun saveReadmeCreatesNestedFile() = runBlocking {
        val store = store()
        store.saveReadme(listOf("g", "t"), "# doc")

        assertTrue(java.io.File(tasksDir, "g/t/README.md").isFile)
        assertEquals("g/t/README.md", store.readmes.value.single().path)
    }

    @Test fun refreshRemovesAnEmptyReadme() = runBlocking {
        val store = store()
        java.io.File(tasksDir, "g").mkdirs()
        val file = java.io.File(tasksDir, "g/README.md")
        file.writeText("  \n")

        store.refresh()

        assertFalse(file.exists())
        assertTrue(store.readmes.value.isEmpty())
    }

    @Test fun blankReadmeDeletesTheFile() = runBlocking {
        val store = store()
        store.saveReadme(listOf("g"), "doc")
        val file = java.io.File(tasksDir, "g/README.md")
        assertTrue(file.isFile)

        store.saveReadme(listOf("g"), "   ")

        assertFalse(file.exists())
        assertTrue(store.readmes.value.isEmpty())
    }

    @Test fun importArchiveRestoresReadmes() = runBlocking {
        val store = store()
        val source = folder.newFolder("doc-import-source")
        java.io.File(source, "README.md").writeText("root doc")
        java.io.File(source, "g").mkdirs()
        java.io.File(source, "g/README.md").writeText("group doc")
        val zip = java.io.File(folder.root, "docs.zip")
        TaskArchive.export(source, zip)

        assertEquals(0, store.importArchive(zip, TaskArchive.MergeMode.SKIP))

        assertEquals("root doc", store.readmes.value.first { it.segments.isEmpty() }.body)
        assertEquals("group doc", store.readmes.value.first { it.segments == listOf("g") }.body)
    }

    @Test fun importArchiveAddsTasks() = runBlocking {
        val store = store()
        val source = folder.newFolder("import-source")
        java.io.File(source, "x_one.md").writeText(
            TaskFile.serialize(Task(id = "x-1", title = "imported")),
        )
        val zip = java.io.File(folder.root, "import.zip")
        TaskArchive.export(source, zip)
        assertEquals(1, store.importArchive(zip, TaskArchive.MergeMode.SKIP))
        assertEquals("imported", store.tasks.value.single().title)
        assertEquals(0, store.importArchive(zip, TaskArchive.MergeMode.SKIP))
    }

    @Test fun importArchiveSkipsBlankTitles() = runBlocking {
        val store = store()
        val source = folder.newFolder("blank-import-source")
        java.io.File(source, "blank.md").writeText(
            TaskFile.serialize(Task(id = "blank-import", title = "   ")),
        )
        val zip = java.io.File(folder.root, "blank-import.zip")
        TaskArchive.export(source, zip)
        assertEquals(0, store.importArchive(zip, TaskArchive.MergeMode.SKIP))
        assertTrue(store.tasks.value.isEmpty())
    }

    @Test fun refreshRejectsFilesOutsideTheCanonicalLayout() = runBlocking {
        val store = store()
        val flat = java.io.File(tasksDir, "legacy-1.md")
        flat.writeText(TaskFile.serialize(Task(id = "legacy-1", title = "Nhóm Công Việc")))
        store.refresh()
        assertTrue(flat.exists())
        assertTrue(store.tasks.value.isEmpty())
    }

    @Test fun savingNewTitleRemovesOldSlugFile() = runBlocking {
        val store = store()
        store.save(Task(id = "rename-1", title = "old title"))
        store.save(Task(id = "rename-1", title = "new title"))
        assertFalse(java.io.File(tasksDir, "rename-1_old_title.md").exists())
        assertTrue(java.io.File(tasksDir, "rename-1_new_title.md").isFile)
    }

    @Test fun groupedTaskUsesFolderLayoutAndFollowsState() = runBlocking {
        val store = store()
        val task = Task(id = "folder-1", title = "Nhóm_Công_Việc(báo cáo): Việc")
        store.save(task)
        val open = TaskFile.relativePath(task)
        assertTrue(open, java.io.File(tasksDir, open).isFile)

        store.toggle(task.id)
        val done = TaskFile.relativePath(task.copy(done = true))
        assertTrue(done, java.io.File(tasksDir, done).isFile)
        assertFalse(java.io.File(tasksDir, open).exists())

        store.archive(task.id)
        val archived = TaskFile.relativePath(task.copy(done = true, archived = true))
        assertTrue(archived, java.io.File(tasksDir, archived).isFile)
        assertFalse(java.io.File(tasksDir, done).exists())

        store.restore(task.id)
        assertTrue(java.io.File(tasksDir, done).isFile)
    }

    @Test fun stateChangeMovesTheStoredBytes() = runBlocking {
        val store = store()
        val task = Task(id = "move-raw", title = "raw")
        store.save(task)
        val open = java.io.File(tasksDir, TaskFile.relativePath(task))
        // The app ignores this field now; a rewrite would remove it.
        open.writeText(open.readText().replace("updated:", "archived: false\nupdated:"))
        store.refresh()

        store.toggle("move-raw")

        val done = java.io.File(tasksDir, TaskFile.relativePath(task.copy(done = true)))
        assertTrue(done.readText().contains("archived: false"))
    }

    @Test fun refreshReadsStateFromFolders() = runBlocking {
        val store = store()
        store.save(Task(id = "state-1", title = "g:state"))
        store.toggle("state-1")
        store.archive("state-1")

        val reloaded = TaskStore(tasksDir)
        reloaded.refresh()
        val task = reloaded.tasks.value.single()
        assertTrue(task.done)
        assertTrue(task.archived)

        store.restore("state-1")
        val restored = TaskFile.relativePath(Task(id = "state-1", title = "g:state", done = true))
        assertTrue(java.io.File(tasksDir, restored).isFile)
    }

    @Test fun groupedTaskRebasesItsOwnLinksOnSave() = runBlocking {
        val store = store()
        store.save(Task(id = "link-target", title = "target"))
        store.save(Task(id = "link-source", title = "g:source", body = "See [T](link-target_target.md)"))

        val body = store.tasks.value.first { it.id == "link-source" }.body
        assertEquals("See [T](../link-target_target.md)", body)
        assertEquals(
            "../link-target_target.md",
            store.links.value.linkFrom("link-source", "link-target"),
        )
    }

    @Test fun movingTheTargetRewritesReferrerPaths() = runBlocking {
        val store = store()
        store.save(Task(id = "move-target", title = "target"))
        store.save(Task(id = "move-source", title = "g:source", body = "See [T](move-target_target.md)"))

        store.toggle("move-target")

        val body = store.tasks.value.first { it.id == "move-source" }.body
        assertEquals("See [T](../_done/move-target_target.md)", body)
        assertEquals("move-target", store.links.value.idFor("../_done/move-target_target.md"))
    }

    @Test fun completingKeepsPositionAndTimestamp() = runBlocking {
        val store = store()
        store.save(Task(id = "1-0001", title = "p:a"))
        store.save(Task(id = "2-0002", title = "p:b"))
        store.save(Task(id = "3-0003", title = "p:c"))
        store.move("p:p", "k:3-0003", "k:1-0001")
        store.move("p:p", "k:1-0001", "k:2-0002")

        val before = store.tasks.value
        val openBefore = TaskTree.rows(before, store.order.value)
            .filterIsInstance<TreeRow.Item>()
            .filterNot { it.task.done }
            .map { it.task.id }
        val bUpdated = before.first { it.id == "2-0002" }.updated

        store.toggle("2-0002")

        val after = store.tasks.value
        assertEquals(bUpdated, after.first { it.id == "2-0002" }.updated)
        val open = TaskTree.rows(after, store.order.value)
            .filterIsInstance<TreeRow.Item>()
            .filterNot { it.task.done }
            .map { it.task.id }
        assertEquals(openBefore.filterNot { it == "2-0002" }, open)
        assertTrue(
            TaskTree.rows(after, store.order.value)
                .filterIsInstance<TreeRow.Item>()
                .filter { it.task.done }
                .any { it.task.id == "2-0002" },
        )

        store.toggle("2-0002")
        assertEquals(
            openBefore,
            TaskTree.rows(store.tasks.value, store.order.value)
                .filterIsInstance<TreeRow.Item>()
                .filterNot { it.task.done }
                .map { it.task.id },
        )
    }

    @Test fun bulkDoneMarksAndReopensWithoutTimestamps() = runBlocking {
        val store = store()
        store.save(Task(id = "1-0001", title = "p:a"))
        store.save(Task(id = "2-0002", title = "p:b"))
        val before = store.tasks.value.associate { it.id to it.updated }
        store.setDone(listOf("1-0001", "2-0002"), true)
        assertTrue(store.tasks.value.all { it.done })
        assertEquals(before, store.tasks.value.associate { it.id to it.updated })

        store.setDone(listOf("1-0001"), false)
        assertFalse(store.tasks.value.first { it.id == "1-0001" }.done)
        assertTrue(store.tasks.value.first { it.id == "2-0002" }.done)
        val open = TaskTree.rows(store.tasks.value, store.order.value)
            .filterIsInstance<TreeRow.Item>()
            .filterNot { it.task.done }
            .map { it.task.id }
        assertEquals(listOf("1-0001"), open)
    }

    @Test fun archiveHidesTaskFromActiveBoard() = runBlocking {
        val store = store(withExisting = true)
        store.archive("1-0001")
        assertTrue(store.tasks.value.single().archived)
    }

    @Test fun restoreBringsTaskBack() = runBlocking {
        val store = store(withExisting = true)
        store.archive("1-0001")
        store.restore("1-0001")
        assertFalse(store.tasks.value.single().archived)
    }

    @Test fun removeDeletesTaskFile() = runBlocking {
        val store = store(withExisting = true)
        store.remove("1-0001")
        assertTrue(store.tasks.value.isEmpty())
    }

    @Test fun archiveUnknownIdIsNoOp() = runBlocking {
        val store = store(withExisting = true)
        store.archive("missing")
        assertEquals(1, store.tasks.value.size)
    }

    private suspend fun seedWork(store: TaskStore) {
        store.save(Task(id = "1-0001", title = "work:one"))
        store.save(Task(id = "2-0002", title = "work:two"))
    }

    @Test fun movePutsTheNodeBeforeItsTarget() = runBlocking {
        val store = store()
        seedWork(store)
        store.move(TaskOrder.groupKey("work"), "k:2-0002", "k:1-0001")
        assertEquals(
            listOf("k:2-0002", "k:1-0001"),
            levelKeys(store, TaskOrder.groupKey("work")),
        )
    }

    @Test fun moveToTheEndAppends() = runBlocking {
        val store = store()
        seedWork(store)
        store.move(TaskOrder.groupKey("work"), "k:1-0001", null)
        assertEquals(
            listOf("k:2-0002", "k:1-0001"),
            levelKeys(store, TaskOrder.groupKey("work")),
        )
    }

    @Test fun topicHeaderCannotMoveAbovePlainTasks() = runBlocking {
        val store = store()
        store.save(Task(id = "1-0001", title = "work:one"))
        store.save(Task(id = "2-0002", title = "work(back):two"))
        store.move(TaskOrder.groupKey("work"), "t:work|back", "k:1-0001")
        // Plain tasks always render above topic blocks.
        assertEquals(
            listOf("k:1-0001", "t:work|back"),
            levelKeys(store, TaskOrder.groupKey("work")),
        )
    }

    @Test fun moveInsideATopicLevel() = runBlocking {
        val store = store()
        store.save(Task(id = "1-0001", title = "work(back):one"))
        store.save(Task(id = "2-0002", title = "work(back):two"))
        store.move(TaskOrder.topicKey("work", "back"), "k:2-0002", "k:1-0001")
        assertEquals(
            listOf("k:2-0002", "k:1-0001"),
            levelKeys(store, TaskOrder.topicKey("work", "back")),
        )
    }

    @Test fun moveWorksForATaskAddedAfterTheOrderWasSaved() = runBlocking {
        val store = store()
        seedWork(store)
        store.move(TaskOrder.groupKey("work"), "k:2-0002", "k:1-0001")
        store.save(Task(id = "3-0003", title = "work:three"))
        store.move(TaskOrder.groupKey("work"), "k:3-0003", "k:1-0001")
        val keys = levelKeys(store, TaskOrder.groupKey("work"))
        assertEquals(3, keys.size)
        assertTrue(keys.toString(), keys.indexOf("k:3-0003") < keys.indexOf("k:1-0001"))
    }

    @Test fun moveSurvivesAReload() = runBlocking {
        val store = store()
        seedWork(store)
        store.move(TaskOrder.groupKey("work"), "k:2-0002", "k:1-0001")
        val reloaded = TaskStore(tasksDir)
        reloaded.refresh()
        assertEquals(store.order.value, reloaded.order.value)
    }

}

class TaskStoreIncrementalTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store(): TaskStore {
        val dir = folder.newFolder("tasks")
        return TaskStore(dir)
    }

    @Test fun toggleUpdatesStateWithoutExternalRefresh() = runBlocking {
        val store = store()
        store.save(Task(id = "toggle-1", title = "toggle"))
        store.toggle("toggle-1")
        assertTrue(store.tasks.value.single().done)
        assertEquals(true, store.tasks.value.single().done)
    }

    @Test fun removingReferrerDropsItsLinkEntries() = runBlocking {
        val store = store()
        store.save(Task(id = "target-2", title = "target"))
        store.save(Task(id = "source-2", title = "source", body = "[t](target-2_target.md)"))
        assertEquals(setOf("source-2"), store.links.value.referrers["target-2_target.md"])
        store.removeAll(listOf("source-2"))
        assertTrue(store.tasks.value.none { it.id == "source-2" })
        assertTrue(store.links.value.referrers["target-2_target.md"].isNullOrEmpty())
    }

    @Test fun refreshPicksUpAnExternallyChangedFile() = runBlocking {
        val store = store()
        store.save(Task(id = "external-1", title = "external", body = "old"))
        val file = java.io.File(storeFileDir(), "external-1_external.md")
        file.writeText(
            TaskFile.serialize(
                Task(id = "external-1", title = "external", body = "new", updated = 9_999),
            ),
        )
        store.refresh()
        assertEquals("new", store.tasks.value.single().body)
    }

    private fun storeFileDir(): java.io.File = folder.root.listFiles()!!.first {
        it.isDirectory && it.name == "tasks"
    }

    @Test fun replacedFileWithSameSizeAndTimestampIsReloaded() = runBlocking {
        val dir = folder.newFolder("key-tasks")
        val cache = folder.newFile("key-index.bin")
        val original = Task(id = "key-1", title = "key", body = "aaaa")
        val first = TaskStore(dir, cacheFile = cache)
        first.save(original)
        first.refresh()

        val file = java.io.File(dir, TaskFile.relativePath(original))
        val modified = file.lastModified()
        val size = file.length()
        AtomicFiles.writeText(file, TaskFile.serialize(original.copy(body = "bbbb")))
        file.setLastModified(modified)
        assertEquals(size, file.length())

        val second = TaskStore(dir, cacheFile = cache)
        second.refresh()
        assertEquals("bbbb", second.tasks.value.single().body)
    }

    @Test fun nonCanonicalFileIsRejected() = runBlocking {
        val dir = folder.newFolder("strict-tasks")
        val cache = folder.newFile("strict-index.bin")
        val flat = java.io.File(dir, "old-1.md")
        flat.writeText(TaskFile.serialize(Task(id = "old-1", title = "old")))
        val first = TaskStore(dir, cacheFile = cache)
        first.refresh()
        assertTrue(first.tasks.value.isEmpty())
        assertTrue(flat.exists())

        val late = java.io.File(dir, "late-1.md")
        late.writeText(TaskFile.serialize(Task(id = "late-1", title = "late")))
        val second = TaskStore(dir, cacheFile = cache)
        second.refresh()
        assertTrue(second.tasks.value.none { it.id == "late-1" })
        assertTrue(late.exists())
    }

    @Test fun cacheSurvivesAStoreRestart() = runBlocking {
        val dir = folder.newFolder("cached-tasks")
        val cache = folder.newFile("task-index.bin")
        val task = Task(id = "cached-1", title = "cached")
        val first = TaskStore(dir, cacheFile = cache)
        first.save(task)
        first.refresh()

        val second = TaskStore(dir, cacheFile = cache)
        second.refresh()
        assertEquals("cached", second.tasks.value.single().title)

        java.io.File(dir, TaskFile.relativePath(task)).delete()
        val third = TaskStore(dir, cacheFile = cache)
        third.refresh()
        assertTrue(third.tasks.value.isEmpty())
    }
}

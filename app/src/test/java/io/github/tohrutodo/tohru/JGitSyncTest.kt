package io.github.tohrutodo.tohru

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.tohrutodo.tohru.sync.ConflictChoice
import io.github.tohrutodo.tohru.sync.ConflictResolution
import io.github.tohrutodo.tohru.sync.ConflictSession
import io.github.tohrutodo.tohru.sync.DirtyKind
import io.github.tohrutodo.tohru.sync.ConflictSide
import io.github.tohrutodo.tohru.sync.GitMaintenance
import io.github.tohrutodo.tohru.sync.JGitSync
import io.github.tohrutodo.tohru.sync.PullOutcome
import io.github.tohrutodo.tohru.sync.RemoteConfig
import io.github.tohrutodo.tohru.sync.SyncDirtyWorkTreeException
import io.github.tohrutodo.tohru.sync.SyncInvalidConflictContentException
import io.github.tohrutodo.tohru.sync.SyncSshKeyMissingException
import io.github.tohrutodo.tohru.sync.SyncState
import io.github.tohrutodo.tohru.task.Task
import io.github.tohrutodo.tohru.task.TaskFile
import java.io.File

class JGitSyncTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var remoteUrl: String
    private lateinit var repoA: File
    private lateinit var repoB: File

    companion object {
        private var hostReader: SystemReader? = null

        // The host machine's ~/.gitconfig may contain options JGit rejects
        // (for example gpg.format=ssh). Tests must not depend on it.
        @BeforeClass @JvmStatic
        fun isolateGitConfig() {
            hostReader = SystemReader.getInstance()
            SystemReader.setInstance(EmptySystemReader())
        }

        @AfterClass @JvmStatic
        fun restoreGitConfig() {
            SystemReader.setInstance(hostReader)
        }
    }

    @Before fun setUp() {
        val remote = folder.newFolder("remote.git")
        Git.init().setBare(true).setDirectory(remote).call().close()
        remoteUrl = remote.toURI().toString()
        repoA = folder.newFolder("a")
        repoB = folder.newFolder("b")
    }

    private fun sync(dir: File): JGitSync =
        JGitSync(
            dir,
            { RemoteConfig(url = remoteUrl, branch = "main") },
            confirmHostKey = { false },
        )

    @Test
    fun commitUsesConfiguredIdentity() = runBlocking {
        writeTask(repoA, "identity-1", "identity")
        val git = JGitSync(
            repoA,
            {
                RemoteConfig(
                    url = remoteUrl,
                    branch = "main",
                    authorName = "Alice",
                    authorEmail = "alice@example.com",
                )
            },
            confirmHostKey = { false },
        )
        assertSuccess(git.commit("identity"))
        Git.open(repoA).use { opened ->
            val commit = opened.log().call().first()
            assertEquals("Alice", commit.authorIdent.name)
            assertEquals("alice@example.com", commit.authorIdent.emailAddress)
        }
    }

    @Test
    fun listsBranchesAndChecksOut() = runBlocking {
        writeTask(repoA, "branch-1", "branch")
        val git = sync(repoA)
        assertSuccess(git.commit("base"))
        Git.open(repoA).use { it.branchCreate().setName("other").call() }

        val before = git.branches().getOrThrow()
        val original = before.single { it.current }.name
        assertTrue(before.any { it.name == "other" })
        assertSuccess(git.checkout("other"))
        assertEquals("other", git.branches().getOrThrow().single { it.current }.name)
        assertSuccess(git.checkout(original))
    }

    @Test
    fun createsAndChecksOutBranch() = runBlocking {
        writeTask(repoA, "create-1", "create")
        val git = sync(repoA)
        assertSuccess(git.commit("base"))
        assertSuccess(git.createBranch("feature/local"))
        assertEquals("feature/local", git.branches().getOrThrow().single { it.current }.name)
    }

    @Test
    fun deletesMergedBranchOnly() = runBlocking {
        writeTask(repoA, "delete-1", "delete")
        val git = sync(repoA)
        assertSuccess(git.commit("base"))
        val original = git.branches().getOrThrow().single { it.current }.name
        assertSuccess(git.createBranch("temporary"))
        assertSuccess(git.checkout(original))
        assertSuccess(git.deleteBranch("temporary"))
        assertTrue(git.branches().getOrThrow().none { it.name == "temporary" })
        val currentDelete = git.deleteBranch(original)
        assertTrue(currentDelete.isFailure)
    }

    @Test
    fun marksBranchWithMissingUpstreamAsStale() = runBlocking {
        writeTask(repoA, "stale-1", "stale")
        val git = sync(repoA)
        assertSuccess(git.commit("base"))
        Git.open(repoA).use { opened ->
            opened.branchCreate().setName("stale").call()
            opened.repository.config.apply {
                setString("branch", "stale", "remote", "origin")
                setString("branch", "stale", "merge", "refs/heads/stale")
                save()
            }
        }
        assertTrue(git.branches().getOrThrow().single { it.name == "stale" }.stale)
    }

    @Test
    fun maintenancePrunesOldRecoveryRefs() = runBlocking {
        writeTask(repoA, "maint-1", "maintenance")
        val git = JGitSync(
            repoA,
            { RemoteConfig(url = remoteUrl, branch = "main") },
            confirmHostKey = { false },
            maintenanceProvider = {
                GitMaintenance(recoveryKeep = 1, gcEnabled = false, exportKeep = 1)
            },
        )
        assertSuccess(git.commit("base"))
        Git.open(repoA).use { opened ->
            listOf("20260101000000_a", "20260102000000_b", "20260103000000_c").forEach { suffix ->
                val update = opened.repository.updateRef("refs/tohru/recovery/$suffix")
                update.setNewObjectId(opened.repository.resolve("HEAD"))
                update.setForceUpdate(true)
                update.update()
            }
        }
        assertSuccess(git.maintain())
        Git.open(repoA).use { opened ->
            val refs = opened.repository.refDatabase.getRefsByPrefix("refs/tohru/recovery/")
            assertEquals(1, refs.size)
            assertTrue(refs.single().name.endsWith("20260103000000_c"))
        }
    }

    @Test
    fun checkoutRejectsDirtyWorktree() = runBlocking {
        writeTask(repoA, "dirty-1", "dirty")
        val git = sync(repoA)
        assertSuccess(git.commit("base"))
        writeTask(repoA, "pending-1", "pending")
        val result = git.checkout("other")
        assertTrue(result.exceptionOrNull() is SyncDirtyWorkTreeException)
    }

    private fun assertSuccess(result: Result<*>) {
        assertTrue("failed: ${result.exceptionOrNull()}", result.isSuccess)
    }

    private fun PullOutcome.conflicts(): ConflictSession {
        assertTrue("expected conflicts, was $this", this is PullOutcome.Conflicts)
        return (this as PullOutcome.Conflicts).session
    }

    private fun writeTask(
        dir: File,
        id: String,
        title: String,
        updated: Long = 1_000,
        body: String = "",
    ) {
        val tasks = File(dir, "tasks").apply { mkdirs() }
        File(tasks, "$id.md").writeText(
            TaskFile.serialize(
                Task(id = id, title = title, updated = updated, created = updated, body = body),
            ),
        )
    }

    private fun readTask(dir: File, id: String): Task? =
        File(dir, "tasks/$id.md").takeIf { it.exists() }?.let { TaskFile.deserialize(it.readText()) }

    private fun writeOrder(dir: File, value: String) {
        val tasks = File(dir, "tasks").apply { mkdirs() }
        File(tasks, "_order.json").writeText(value)
    }

    private fun conflictCopies(dir: File, side: String? = null): List<File> =
        File(dir, ".sync-conflict").takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile && (side == null || it.path.contains("/$side/")) }
            ?.toList()
            .orEmpty()

    private suspend fun sharedBase(
        localUpdated: Long = 1_000,
        remoteUpdated: Long = 3_000,
        localTitle: String = "local",
        remoteTitle: String = "remote",
    ): Pair<JGitSync, JGitSync> {
        val a = sync(repoA)
        val b = sync(repoB)
        writeTask(repoA, "1-0001", "base", updated = 500)
        assertSuccess(a.commit("base"))
        assertSuccess(a.push())
        assertEquals(PullOutcome.Updated, b.pull().getOrThrow())

        writeTask(repoA, "1-0001", localTitle, updated = localUpdated)
        assertSuccess(a.commit("local"))

        writeTask(repoB, "1-0001", remoteTitle, updated = remoteUpdated)
        assertSuccess(b.commit("remote"))
        assertSuccess(b.push())
        return a to b
    }

    @Test fun commitCreatesHistory() = runBlocking {
        val git = sync(repoA)
        writeTask(repoA, "1-0001", "one")
        assertEquals(SyncState.Dirty(1), git.status())
        assertSuccess(git.commit("add one"))
        assertEquals(SyncState.Ahead(1), git.status())
        Git.open(repoA).use { assertEquals(1, it.log().call().count()) }
    }

    @Test fun pullAndPushRejectDirtyWorktree() = runBlocking {
        val git = sync(repoA)
        writeTask(repoA, "1-0001", "dirty")
        assertTrue(git.pull().exceptionOrNull() is SyncDirtyWorkTreeException)
        assertTrue(git.push().exceptionOrNull() is SyncDirtyWorkTreeException)
    }

    @Test fun snapshotAndHistoryExposeCommitData() = runBlocking {
        val git = sync(repoA)
        writeTask(repoA, "1-0001", "one")
        assertSuccess(git.commit("add one"))

        val snapshot = git.snapshot()
        assertTrue(snapshot.remoteConfigured)
        assertEquals("main", snapshot.branch)
        assertTrue(snapshot.dirtyFiles.isEmpty())
        assertEquals(1, snapshot.ahead)
        assertEquals("add one", snapshot.lastCommit?.subject)

        val history = git.history(limit = 10).getOrThrow()
        assertEquals("add one", history.first().subject)
        assertTrue(history.first().refs.any { it == "HEAD" })

        val detail = git.commitDetail(history.first().hash).getOrThrow()
        assertEquals("add one", detail?.message)
        assertTrue(detail!!.files.any { it.path.endsWith("1-0001.md") })
    }

    @Test fun snapshotClassifiesDirtyFiles() = runBlocking {
        val git = sync(repoA)
        writeTask(repoA, "1-0001", "one")
        assertSuccess(git.commit("add one"))
        File(repoA, "tasks/1-0001.md").appendText("\nchanged")
        writeTask(repoA, "2-0002", "two")

        val snapshot = git.snapshot()

        assertEquals(DirtyKind.MODIFIED, snapshot.dirtyKinds["tasks/1-0001.md"])
        assertEquals(DirtyKind.UNTRACKED, snapshot.dirtyKinds["tasks/2-0002.md"])
        assertEquals(setOf("tasks/1-0001.md", "tasks/2-0002.md"), snapshot.dirtyFiles.toSet())
    }

    @Test fun snapshotPairsAMovedTask() = runBlocking {
        val git = sync(repoA)
        writeTask(repoA, "1-0001", "one")
        assertSuccess(git.commit("add one"))
        val tasks = File(repoA, "tasks")
        val movedDir = File(tasks, "_done").apply { mkdirs() }
        assertTrue(File(tasks, "1-0001.md").renameTo(File(movedDir, "1-0001_one.md")))

        val snapshot = git.snapshot()

        assertEquals(listOf("tasks/_done/1-0001_one.md"), snapshot.dirtyFiles)
        assertEquals(DirtyKind.MOVED, snapshot.dirtyKinds["tasks/_done/1-0001_one.md"])
    }

    @Test fun pendingCountsAMovedTaskOnceAcrossUnpushedCommits() = runBlocking {
        val git = sync(repoA)
        writeTask(repoA, "1-0001", "one")
        assertSuccess(git.commit("add one"))
        assertSuccess(git.push())

        writeTask(repoA, "1-0001", "one", updated = 2_000, body = "changed")
        assertSuccess(git.autoCommit())
        val tasks = File(repoA, "tasks")
        val done = File(tasks, "_done").apply { mkdirs() }
        assertTrue(File(tasks, "1-0001.md").renameTo(File(done, "1-0001_one.md")))

        val snapshot = git.snapshot()

        assertEquals(1, snapshot.dirtyFiles.size)
        assertEquals(1, snapshot.pendingFiles)
        assertEquals("tasks/_done/1-0001_one.md", snapshot.pendingFileName)
    }

    @Test fun pushThenPullCopiesTasks() = runBlocking {
        val a = sync(repoA)
        val b = sync(repoB)
        writeTask(repoA, "1-0001", "one")
        assertSuccess(a.commit("add one"))
        assertSuccess(a.push())
        assertEquals(PullOutcome.Updated, b.pull().getOrThrow())
        assertEquals("one", readTask(repoB, "1-0001")?.title)
    }

    @Test fun pullBringsRemoteEdits() = runBlocking {
        val a = sync(repoA)
        val b = sync(repoB)
        writeTask(repoA, "1-0001", "one", updated = 1_000)
        assertSuccess(a.commit("add"))
        assertSuccess(a.push())
        assertEquals(PullOutcome.Updated, b.pull().getOrThrow())

        writeTask(repoB, "1-0001", "two", updated = 2_000)
        assertSuccess(b.commit("edit"))
        assertSuccess(b.push())
        assertEquals(PullOutcome.Updated, a.pull().getOrThrow())
        assertEquals("two", readTask(repoA, "1-0001")?.title)
    }

    @Test fun conflictTakesNewerVersionAndKeepsTheOtherCopy() = runBlocking {
        val (a, _) = sharedBase(localUpdated = 2_000, remoteUpdated = 3_000)

        val session = a.pull().getOrThrow().conflicts()

        assertEquals(1, session.files.size)
        val file = session.files.single()
        assertEquals(ConflictSide.REMOTE, file.suggested)
        assertSuccess(a.applySession(ConflictResolution.suggested(session.files)))
        assertEquals("remote", readTask(repoA, "1-0001")?.title)
        val copies = conflictCopies(repoA, "local")
        assertEquals(1, copies.size)
        assertTrue(copies.single().readText().contains("local"))
        assertTrue(a.status() is SyncState.Ahead)
    }

    @Test fun resolutionCanPickTheLocalVersion() = runBlocking {
        val (a, _) = sharedBase(localUpdated = 4_000, remoteUpdated = 3_000)

        val session = a.pull().getOrThrow().conflicts()
        val path = session.files.single().path
        assertSuccess(
            a.applySession(
                ConflictResolution(mapOf(path to ConflictChoice.Keep(ConflictSide.LOCAL))),
            ),
        )

        assertEquals(ConflictSide.LOCAL, session.files.single().suggested)
        assertEquals("local", readTask(repoA, "1-0001")?.title)
        assertTrue(conflictCopies(repoA, "remote").single().readText().contains("remote"))
    }

    @Test fun customResolutionWritesEditedContent() = runBlocking {
        val (a, _) = sharedBase()
        val session = a.pull().getOrThrow().conflicts()
        val path = session.files.single().path
        val edited = TaskFile.serialize(
            Task(id = "1-0001", title = "edited", updated = 5_000, created = 500),
        )

        assertSuccess(
            a.applySession(
                ConflictResolution(mapOf(path to ConflictChoice.Custom(edited))),
            ),
        )

        assertEquals("edited", readTask(repoA, "1-0001")?.title)
    }

    @Test fun invalidCustomTaskIsRejected() = runBlocking {
        val (a, _) = sharedBase()
        val session = a.pull().getOrThrow().conflicts()
        val path = session.files.single().path

        val result = a.applySession(
            ConflictResolution(mapOf(path to ConflictChoice.Custom("not a task"))),
        )

        assertTrue(result.exceptionOrNull() is SyncInvalidConflictContentException)
        assertNotNull(a.pendingSession())
        assertSuccess(a.discardSession())
        assertEquals("local", readTask(repoA, "1-0001")?.title)
    }

    @Test fun discardLeavesLocalStateUntouched() = runBlocking {
        val (a, _) = sharedBase()

        val session = a.pull().getOrThrow().conflicts()
        assertEquals(1, session.files.size)
        assertSuccess(a.discardSession())

        assertEquals("local", readTask(repoA, "1-0001")?.title)
        assertFalse(File(repoA, ".git/MERGE_HEAD").exists())
        assertNull(a.pendingSession())
    }

    @Test fun pendingSessionSurvivesRestart() = runBlocking {
        val (a, _) = sharedBase()
        val first = a.pull().getOrThrow().conflicts()

        val reopened = sync(repoA)
        val pending = reopened.pendingSession()
        assertNotNull(pending)
        assertEquals(first.files.map { it.path }, pending!!.files.map { it.path })
        assertSuccess(reopened.applySession(ConflictResolution.suggested(pending.files)))
        assertEquals("remote", readTask(repoA, "1-0001")?.title)
    }

    @Test fun draftsSurviveRestart() = runBlocking {
        val (a, _) = sharedBase()
        val session = a.pull().getOrThrow().conflicts()
        val path = session.files.single().path
        val edited = TaskFile.serialize(
            Task(id = "1-0001", title = "draft title", updated = 9_000, created = 500),
        )
        assertSuccess(a.saveDrafts(mapOf(path to ConflictChoice.Custom(edited))))

        val reopened = sync(repoA)
        val pending = reopened.pendingSession()
        assertNotNull(pending)
        assertEquals(ConflictChoice.Custom(edited), pending!!.drafts[path])
        assertSuccess(reopened.applySession(ConflictResolution(pending.drafts)))
        assertEquals("draft title", readTask(repoA, "1-0001")?.title)
    }

    @Test fun orderFileKeepsLocalSide() = runBlocking {
        val a = sync(repoA)
        val b = sync(repoB)
        writeTask(repoA, "1-0001", "base", updated = 500)
        writeOrder(repoA, "local-base")
        assertSuccess(a.commit("base"))
        assertSuccess(a.push())
        assertEquals(PullOutcome.Updated, b.pull().getOrThrow())

        writeOrder(repoA, "local-order")
        assertSuccess(a.commit("local order"))
        writeOrder(repoB, "remote-order")
        assertSuccess(b.commit("remote order"))
        assertSuccess(b.push())

        val result = a.pull().getOrThrow()
        assertEquals(PullOutcome.Updated, result)
        assertNull(a.pendingSession())

        assertEquals("local-order", File(repoA, "tasks/_order.json").readText())
        val remoteCopy = conflictCopies(repoA, "remote").single { it.path.endsWith("_order.json") }
        assertEquals("remote-order", remoteCopy.readText())
    }

    @Test fun deleteVersusEditKeepsEditedFile() = runBlocking {
        val a = sync(repoA)
        val b = sync(repoB)
        writeTask(repoA, "1-0001", "base", updated = 500)
        assertSuccess(a.commit("base"))
        assertSuccess(a.push())
        assertEquals(PullOutcome.Updated, b.pull().getOrThrow())

        File(repoA, "tasks/1-0001.md").delete()
        assertSuccess(a.commit("remove"))

        writeTask(repoB, "1-0001", "edited", updated = 2_000)
        assertSuccess(b.commit("edit"))
        assertSuccess(b.push())

        val session = a.pull().getOrThrow().conflicts()
        assertEquals(ConflictSide.REMOTE, session.files.single().suggested)
        assertSuccess(a.applySession(ConflictResolution.suggested(session.files)))
        assertEquals("edited", readTask(repoA, "1-0001")?.title)
    }

    @Test fun rejectedPushReportsFailure() = runBlocking {
        val a = sync(repoA)
        val b = sync(repoB)
        writeTask(repoA, "1-0001", "one")
        assertSuccess(a.commit("one"))
        assertSuccess(a.push())
        assertEquals(PullOutcome.Updated, b.pull().getOrThrow())

        writeTask(repoA, "1-0002", "other")
        assertSuccess(a.commit("other"))
        assertSuccess(a.push())

        writeTask(repoB, "1-0003", "third")
        assertSuccess(b.commit("third"))
        assertTrue(b.push().isFailure)
    }

    @Test fun pullWithoutRemoteCommitsIsUpToDate() = runBlocking {
        assertEquals(PullOutcome.UpToDate, sync(repoA).pull().getOrThrow())
    }

    @Test fun emptyRepositoryAdoptsTheRemoteBranchName() = runBlocking {
        val a = JGitSync(repoA, { RemoteConfig(url = remoteUrl, branch = "master") }, confirmHostKey = { false })
        writeTask(repoA, "1-0001", "one")
        assertSuccess(a.commit("add"))
        assertSuccess(a.push())

        val b = JGitSync(repoB, { RemoteConfig(url = remoteUrl) }, confirmHostKey = { false })
        assertEquals(PullOutcome.Updated, b.pull().getOrThrow())
        assertEquals("one", readTask(repoB, "1-0001")?.title)
        Git.open(repoB).use { assertEquals("master", it.repository.branch) }
    }

    @Test fun sshRemoteWithoutKeyFailsBeforeConnecting() = runBlocking {
        val git = JGitSync(repoA, { RemoteConfig(url = "git@github.com:user/repo.git") }, confirmHostKey = { false })
        assertTrue(git.pull().exceptionOrNull() is SyncSshKeyMissingException)
        assertTrue(git.push().exceptionOrNull() is SyncSshKeyMissingException)
    }

    private suspend fun unrelatedLocalRepository(
        localTaskId: String = "2-0002",
        remoteExtra: Boolean = false,
    ): JGitSync {
        val a = sync(repoA)
        writeTask(repoA, "1-0001", "remote", updated = 1_000)
        if (remoteExtra) writeTask(repoA, "1-0002", "remote extra", updated = 1_100)
        assertSuccess(a.commit("remote"))
        assertSuccess(a.push())

        val b = sync(repoB)
        writeTask(repoB, localTaskId, "local", updated = 2_000)
        assertSuccess(b.commit("local"))
        return b
    }

    @Test fun unrelatedHistoriesMergeDisjointTasks() = runBlocking {
        val b = unrelatedLocalRepository()

        val result = b.pull().getOrThrow()

        assertEquals(PullOutcome.Updated, result)
        assertEquals("local", readTask(repoB, "2-0002")?.title)
        assertEquals("remote", readTask(repoB, "1-0001")?.title)
        assertFalse(File(repoB, ".git/MERGE_HEAD").exists())
        assertSuccess(b.push())
    }

    @Test fun unrelatedHistoriesResolveSameTaskByChoice() = runBlocking {
        val b = unrelatedLocalRepository(localTaskId = "1-0001")

        val session = b.pull().getOrThrow().conflicts()
        assertEquals(1, session.files.size)
        assertSuccess(
            b.applySession(
                ConflictResolution(
                    session.files.associate { it.path to ConflictChoice.Keep(ConflictSide.REMOTE) },
                ),
            ),
        )

        assertEquals("remote", readTask(repoB, "1-0001")?.title)
        assertTrue(conflictCopies(repoB, "local").single().readText().contains("local"))
    }

    @Test fun unrelatedDiscardDoesNotAddRemoteFiles() = runBlocking {
        val b = unrelatedLocalRepository(localTaskId = "1-0001", remoteExtra = true)

        val session = b.pull().getOrThrow().conflicts()
        assertEquals(1, session.files.size)
        assertSuccess(b.discardSession())

        assertEquals("local", readTask(repoB, "1-0001")?.title)
        assertNull(readTask(repoB, "1-0002"))
        assertFalse(File(repoB, ".git/MERGE_HEAD").exists())
    }

    @Test fun cleanDivergentMergeCommitsWithoutDialog() = runBlocking {
        val a = sync(repoA)
        val b = sync(repoB)
        val lines = (1..30).joinToString("\n") { "line $it" }
        writeTask(repoA, "1-0001", "base", updated = 1_000, body = lines)
        assertSuccess(a.commit("base"))
        assertSuccess(a.push())
        assertEquals(PullOutcome.Updated, b.pull().getOrThrow())

        writeTask(repoA, "1-0001", "base", updated = 1_000, body = "local head\n$lines")
        assertSuccess(a.commit("local head"))

        writeTask(repoB, "1-0001", "base", updated = 1_000, body = "$lines\nremote tail")
        assertSuccess(b.commit("remote tail"))
        assertSuccess(b.push())

        val result = a.pull().getOrThrow()

        assertEquals(PullOutcome.Updated, result)
        val merged = File(repoA, "tasks/1-0001.md").readText()
        assertTrue(merged.contains("local head"))
        assertTrue(merged.contains("remote tail"))
        assertFalse(File(repoA, ".git/MERGE_HEAD").exists())
    }

    @Test fun autoCommitSquashesTaskHistory() = runBlocking {
        val a = sync(repoA)
        writeTask(repoA, "1-0001", "base")
        assertSuccess(a.commit("base"))
        assertSuccess(a.push())

        writeTask(repoA, "1-0001", "one")
        assertSuccess(a.autoCommit())

        val first = a.snapshot()
        assertEquals(0, first.dirtyFiles.size)
        assertEquals(1, first.unpushedCommits)
        assertEquals(1, first.pendingFiles)
        assertEquals("chore(tasks): autosave 1-0001.md", first.lastCommit?.subject)

        writeTask(repoA, "1-0001", "two")
        assertSuccess(a.autoCommit())
        assertEquals(2, a.snapshot().unpushedCommits)
        assertEquals(1, a.snapshot().pendingFiles)

        assertSuccess(a.commit("squash autosaves"))

        val squashed = a.snapshot()
        assertEquals(1, squashed.unpushedCommits)
        assertEquals(1, squashed.pendingFiles)
        assertEquals("squash autosaves", squashed.lastCommit?.subject)
        Git.open(repoA).use { git -> assertEquals(2, git.log().call().count()) }

        assertSuccess(a.push())
        assertEquals(0, a.snapshot().unpushedCommits)
    }

    @Test fun rebasePullKeepsLinearHistory() = runBlocking {
        val a = sync(repoA)
        val b = sync(repoB)
        writeTask(repoA, "1-0001", "base", updated = 1_000)
        assertSuccess(a.commit("base"))
        assertSuccess(a.push())
        assertEquals(PullOutcome.Updated, b.pull().getOrThrow())

        writeTask(repoA, "2-0002", "local", updated = 2_000)
        assertSuccess(a.commit("local"))
        writeTask(repoB, "3-0003", "remote", updated = 3_000)
        assertSuccess(b.commit("remote"))
        assertSuccess(b.push())

        assertEquals(PullOutcome.Updated, a.pull(rebase = true).getOrThrow())
        assertNotNull(readTask(repoA, "2-0002"))
        assertNotNull(readTask(repoA, "3-0003"))
        Git.open(repoA).use { git ->
            assertFalse(git.log().call().any { it.shortMessage.startsWith("merge:") })
        }
    }

    @Test fun squashDropsAutosavesWithNoNetChange() = runBlocking {
        val a = sync(repoA)
        writeTask(repoA, "1-0001", "base")
        assertSuccess(a.commit("base"))
        assertSuccess(a.push())
        writeTask(repoA, "1-0001", "base", body = "changed")
        assertSuccess(a.autoCommit())
        writeTask(repoA, "1-0001", "base")
        assertSuccess(a.autoCommit())
        assertEquals(2, a.snapshot().unpushedCommits)

        assertSuccess(a.commit("noop squash"))

        assertEquals(0, a.snapshot().unpushedCommits)
        Git.open(repoA).use { git ->
            val commits = git.log().call().toList()
            assertEquals(1, commits.size)
            assertEquals("base", commits.single().shortMessage)
        }
    }

    @Test fun autoCommitLeavesNonTaskChangesAlone() = runBlocking {
        val a = sync(repoA)
        File(repoA, "notes.txt").writeText("untracked")

        assertSuccess(a.autoCommit())

        val snapshot = a.snapshot()
        assertEquals(0, snapshot.unpushedCommits)
        assertTrue("notes.txt" in snapshot.dirtyFiles)
    }

    @Test fun keepsOnlyRecentConflictSessions() = runBlocking {
        val (a, _) = sharedBase()
        a.pull().getOrThrow().conflicts()
        val root = File(repoA, ".sync-conflict")
        val real = root.listFiles()!!.first { it.isDirectory }.name
        val fake = (1..12).map { "20000101-000000-%03d".format(it) }
        fake.forEach { File(root, it).mkdirs() }

        assertSuccess(a.discardSession())

        val remaining = root.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet()
        assertTrue(real in remaining)
        assertEquals(10, remaining.size)
        assertTrue(fake.last() in remaining)
        assertFalse(fake.first() in remaining)
    }
}

private class EmptySystemReader : SystemReader() {
    override fun getHostname(): String = "localhost"

    override fun getenv(variable: String?): String? = variable?.let(System::getenv)

    override fun getProperty(key: String?): String? = key?.let(System::getProperty)

    override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig = emptyConfig(parent, fs)

    override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig = emptyConfig(parent, fs)

    override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig = emptyConfig(parent, fs)

    override fun getCurrentTime(): Long = System.currentTimeMillis()

    override fun getTimezone(whenValue: Long): Int = 0

    private fun emptyConfig(parent: Config?, fs: FS?): FileBasedConfig =
        object : FileBasedConfig(parent, null, fs ?: FS.DETECTED) {
            override fun load() = Unit

            override fun isOutdated(): Boolean = false
        }
}

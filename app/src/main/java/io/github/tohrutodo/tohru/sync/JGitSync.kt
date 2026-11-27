package io.github.tohrutodo.tohru.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import io.github.tohrutodo.tohru.io.AtomicFiles
import io.github.tohrutodo.tohru.task.TaskFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// File-backed Git engine. All repository work is serialized on one mutex so
// task writes and an explicit commit cannot interleave with a pull.
class JGitSync(
    private val repoDir: File,
    private val configProvider: () -> RemoteConfig?,
    private val onError: (Throwable) -> Unit = {},
    private val confirmHostKey: suspend (String) -> Boolean = { false },
    private val maintenanceProvider: () -> GitMaintenance = { GitMaintenance() },
) : GitSync {
    private val mutex = Mutex()

    override suspend fun status(): SyncState = locked {
        try {
            ensureRepository()
            Git.open(repoDir).use { git -> readStatus(git) }
        } catch (error: Exception) {
            onError(error)
            SyncState.Error(syncFailure(error))
        }
    }

    override suspend fun commit(message: String): Result<Unit> = locked {
        runCatching {
            ensureRepository()
            if (readPending() != null) return@runCatching
            Git.open(repoDir).use { git -> commitWithSquash(git, message) }
        }
    }

    override suspend fun autoCommit(): Result<Unit> = locked {
        runCatching {
            ensureRepository()
            if (readPending() != null) return@runCatching
            Git.open(repoDir).use { git -> autoCommitWorktree(git) }
        }
    }

    override suspend fun pull(rebase: Boolean): Result<PullOutcome> = locked {
        runCatching {
            ensureRepository()
            readPending()?.let { plan ->
                val session = loadSession(plan)
                return@runCatching if (session.files.isEmpty()) {
                    Git.open(repoDir).use { git -> finishSession(git, plan) }
                } else {
                    PullOutcome.Conflicts(session)
                }
            }
            val remote = requireRemote()
            Git.open(repoDir).use { git ->
                if (!isClean(git.status().call())) throw SyncDirtyWorkTreeException()
                configureRemote(git, remote)
                prepareTransport(remote)
                fetch(git, remote)
                pullRemote(git, remote, rebase)
            }
        }
    }

    override suspend fun pendingSession(): ConflictSession? = locked {
        try {
            ensureRepository()
            val plan = readPending() ?: return@locked null
            val session = loadSession(plan)
            if (session.files.isEmpty()) {
                Git.open(repoDir).use { git -> finishSession(git, plan) }
                null
            } else {
                session
            }
        } catch (error: Exception) {
            onError(error)
            null
        }
    }

    override suspend fun saveDrafts(choices: Map<String, ConflictChoice>): Result<Unit> = locked {
        runCatching {
            ensureRepository()
            val plan = readPending() ?: return@runCatching
            writeDrafts(plan, choices)
        }
    }

    override suspend fun applySession(resolution: ConflictResolution): Result<PullOutcome> = locked {
        runCatching {
            ensureRepository()
            val plan = readPending() ?: throw SyncNoPendingConflictException()
            Git.open(repoDir).use { git -> applyPlan(git, plan, resolution) }
            clearPending()
            pruneSessions()
            PullOutcome.Updated
        }
    }

    override suspend fun discardSession(): Result<Unit> = locked {
        runCatching {
            ensureRepository()
            Git.open(repoDir).use { git -> abortMerge(git) }
            clearPending()
            pruneSessions()
        }
    }

    override suspend fun push(): Result<Unit> = locked {
        runCatching {
            ensureRepository()
            val remote = requireRemote()
            Git.open(repoDir).use { git ->
                configureRemote(git, remote)
                if (!isClean(git.status().call())) throw SyncDirtyWorkTreeException()
                val head = git.repository.resolve(Constants.HEAD) ?: throw SyncNoCommitException()
                prepareTransport(remote)
                pushBranch(git, remote, head)
            }
        }
    }

    override suspend fun fetch(): Result<Unit> = locked {
        runCatching {
            ensureRepository()
            val remote = requireRemote()
            Git.open(repoDir).use { git ->
                configureRemote(git, remote)
                prepareTransport(remote)
                fetch(git, remote)
            }
        }
    }

    override suspend fun maintain(): Result<Unit> = locked {
        runCatching {
            ensureRepository()
            Git.open(repoDir).use { git ->
                val maintenance = maintenanceProvider()
                pruneRecoveryRefs(git.repository, maintenance.recoveryKeep)
                maybeGc(git, maintenance)
            }
            Unit
        }
    }

    private fun pruneRecoveryRefs(repository: Repository, keep: Int) {
        val refs = repository.refDatabase
            .getRefsByPrefix("refs/tohru/recovery/")
            .sortedByDescending { it.name }
        refs.drop(keep.coerceAtLeast(1)).forEach { ref ->
            runCatching {
                val update = repository.updateRef(ref.name)
                update.setForceUpdate(true)
                update.delete()
            }.onFailure(onError)
        }
    }

    private fun maybeGc(git: Git, maintenance: GitMaintenance) {
        if (!maintenance.gcEnabled) return
        val marker = File(repoDir, ".git/tohru-gc")
        val last = marker.takeIf { it.isFile }?.readText()?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (last == 0L) {
            runCatching { AtomicFiles.writeText(marker, now.toString()) }.onFailure(onError)
            return
        }
        val interval = maintenance.gcIntervalDays.coerceAtLeast(1) * 86_400_000L
        if (now - last < interval) return
        runCatching {
            git.gc().call()
            AtomicFiles.writeText(marker, now.toString())
        }.onFailure(onError)
    }

    override suspend fun branches(): Result<List<GitBranch>> = locked {
        runCatching {
            ensureRepository()
            Git.open(repoDir).use { git ->
                val current = currentBranch(git.repository)
                val local = git.branchList().call().map { ref ->
                    val name = ref.name.removePrefix("refs/heads/")
                    GitBranch(
                        name = name,
                        current = name == current,
                        stale = isStaleBranch(git.repository, name),
                    )
                }
                val localNames = local.map { it.name }.toSet()
                val remote = git.repository.refDatabase
                    .getRefsByPrefix("refs/remotes/origin/")
                    .mapNotNull { ref ->
                        val name = ref.name.removePrefix("refs/remotes/origin/")
                        if (name == "HEAD" || name in localNames) null
                        else GitBranch(name = "origin/$name", remote = true)
                    }
                (local + remote).sortedWith(
                    compareByDescending<GitBranch> { it.current }
                        .thenBy { it.remote }
                        .thenBy { it.name },
                )
            }
        }
    }

    override suspend fun checkout(branch: String): Result<Unit> = locked {
        runCatching {
            ensureRepository()
            if (readPending() != null) throw SyncDirtyWorkTreeException()
            Git.open(repoDir).use { git ->
                if (!isClean(git.status().call())) throw SyncDirtyWorkTreeException()
                if (branch.startsWith("origin/")) {
                    val localName = branch.removePrefix("origin/")
                    if (git.repository.resolve("refs/heads/$localName") != null) {
                        git.checkout().setName(localName).call()
                    } else {
                        git.checkout()
                            .setCreateBranch(true)
                            .setName(localName)
                            .setStartPoint("refs/remotes/$branch")
                            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                            .call()
                    }
                } else {
                    git.checkout().setName(branch).call()
                }
                Unit
            }
        }
    }

    override suspend fun deleteBranch(branch: String): Result<Unit> = locked {
        runCatching {
            ensureRepository()
            if (readPending() != null) throw SyncDirtyWorkTreeException()
            Git.open(repoDir).use { git ->
                require(currentBranch(git.repository) != branch) {
                    "cannot delete the current branch"
                }
                git.branchDelete().setBranchNames(branch).call()
                Unit
            }
        }
    }

    override suspend fun createBranch(branch: String): Result<Unit> = locked {
        runCatching {
            ensureRepository()
            if (readPending() != null) throw SyncDirtyWorkTreeException()
            Git.open(repoDir).use { git ->
                if (!isClean(git.status().call())) throw SyncDirtyWorkTreeException()
                val name = branch.trim()
                require(name.isNotEmpty()) { "branch name is empty" }
                require(git.repository.findRef("refs/heads/$name") == null) {
                    "branch already exists"
                }
                git.checkout().setCreateBranch(true).setName(name).call()
                Unit
            }
        }
    }

    override suspend fun snapshot(): GitSnapshot = locked {
        try {
            ensureRepository()
            Git.open(repoDir).use { git -> readSnapshot(git) }
        } catch (error: Exception) {
            onError(error)
            GitSnapshot(error = syncFailure(error))
        }
    }

    override suspend fun history(
        limit: Int,
        allBranches: Boolean,
        query: String,
    ): Result<List<GitCommitSummary>> = locked {
        runCatching {
            ensureRepository()
            Git.open(repoDir).use { git -> readHistory(git, limit, allBranches, query) }
        }
    }

    override suspend fun commitDetail(hash: String): Result<GitCommitDetail?> = locked {
        runCatching {
            ensureRepository()
            Git.open(repoDir).use { git -> readCommitDetail(git, hash) }
        }
    }

    override suspend fun commitDiff(hash: String, path: String): Result<String> = locked {
        runCatching {
            ensureRepository()
            Git.open(repoDir).use { git -> readCommitDiff(git, hash, path) }
        }
    }

    // A path can appear in more than one status set. Later assignments win:
    // conflicting, then untracked, added, removed, modified.
    private fun rawDirtyKinds(status: Status): Map<String, DirtyKind> {
        val kinds = mutableMapOf<String, DirtyKind>()
        (status.modified + status.changed).forEach { kinds[it] = DirtyKind.MODIFIED }
        (status.removed + status.missing).forEach { kinds[it] = DirtyKind.REMOVED }
        status.added.forEach { kinds[it] = DirtyKind.ADDED }
        status.untracked.forEach { kinds[it] = DirtyKind.UNTRACKED }
        status.conflicting.forEach { kinds[it] = DirtyKind.CONFLICTING }
        return kinds
    }

    // A moved task is a missing old path plus an untracked new path. Pairing
    // them by frontmatter id keeps one entry per task instead of two.
    private fun dirtyView(repository: Repository, status: Status): Pair<List<String>, Map<String, DirtyKind>> {
        val raw = rawDirtyKinds(status).toMutableMap()
        val moved = pairedMoves(repository, status)
        if (moved.isNotEmpty()) {
            raw.keys.removeAll(moved.keys)
            raw.keys.removeAll(moved.values.toSet())
            moved.keys.forEach { raw[it] = DirtyKind.MOVED }
        }
        return raw.keys.sorted() to raw
    }

    private fun pairedMoves(repository: Repository, status: Status): Map<String, String> {
        val deleted = status.removed + status.missing
        val added = status.added + status.untracked
        if (deleted.isEmpty() || added.isEmpty()) return emptyMap()
        val deletedIds = deleted.mapNotNull { path ->
            indexTaskId(repository, path)?.let { id -> id to path }
        }.toMap()
        if (deletedIds.isEmpty()) return emptyMap()
        return added.mapNotNull { path ->
            val id = workingTaskId(path) ?: return@mapNotNull null
            deletedIds[id]?.let { old -> path to old }
        }.toMap()
    }

    private fun indexTaskId(repository: Repository, path: String): String? = runCatching {
        val entry = repository.readDirCache().getEntry(path) ?: return null
        TaskFile.deserialize(String(repository.open(entry.objectId).bytes, UTF_8))?.id
    }.getOrNull()

    private fun workingTaskId(path: String): String? = runCatching {
        val file = File(repoDir, path)
        if (!file.isFile) return null
        TaskFile.deserialize(file.readText())?.id
    }.getOrNull()

    private fun readSnapshot(git: Git): GitSnapshot {
        val repository = git.repository
        val pending = readPending()
        val status = git.status().call()
        val conflicts = (pending?.conflicts.orEmpty() + status.conflicting).distinct().sorted()
        val branch = currentBranch(repository) ?: DEFAULT_BRANCH
        val head = repository.resolve(Constants.HEAD)
        val remote = configProvider()
        val configured = !remote?.url.isNullOrBlank()
        val configuredUpstream = upstreamName(repository, branch)
        val fallbackUpstream = "refs/remotes/origin/$branch"
        val upstream = configuredUpstream
            ?: fallbackUpstream.takeIf { configured && repository.resolve(it) != null }
        val (ahead, behind) = when {
            head == null -> 0 to 0
            upstream != null -> {
                val upstreamId = repository.resolve(upstream)
                if (upstreamId == null) 0 to 0 else relationCounts(repository, head, upstreamId)
            }
            configured -> countCommits(repository, head, null) to 0
            else -> 0 to 0
        }
        val unpushed = unpushedCommits(repository)
        val (dirtyFiles, dirtyKinds) = dirtyView(repository, status)
        val pendingPaths = pendingPaths(repository, unpushed, dirtyFiles)
        return GitSnapshot(
            branch = branch,
            upstream = upstream?.removePrefix("refs/remotes/"),
            remoteConfigured = configured,
            dirtyFiles = dirtyFiles,
            dirtyKinds = dirtyKinds,
            unpushedCommits = unpushed.size,
            pendingFiles = pendingPaths.size,
            pendingFileName = pendingPaths.singleOrNull(),
            ahead = ahead,
            behind = behind,
            conflicts = conflicts,
            lastCommit = head?.let { summary(repository, it, refsFor(repository, head)) },
        )
    }

    private fun readHistory(
        git: Git,
        limit: Int,
        allBranches: Boolean,
        query: String,
    ): List<GitCommitSummary> {
        val repository = git.repository
        val head = repository.resolve(Constants.HEAD) ?: return emptyList()
        val starts = if (allBranches) {
            repository.refDatabase.getRefsByPrefix("refs/heads/")
                .mapNotNull { it.objectId }
                .plus(repository.refDatabase.getRefsByPrefix("refs/remotes/").mapNotNull { it.objectId })
                .distinct()
        } else {
            listOf(head)
        }
        if (starts.isEmpty()) return emptyList()
        val refs = refsByObject(repository, head)
        val needle = query.trim().lowercase()
        val result = ArrayList<GitCommitSummary>()
        RevWalk(repository).use { walk ->
            walk.sort(RevSort.TOPO, true)
            walk.sort(RevSort.COMMIT_TIME_DESC, true)
            starts.forEach { id -> runCatching { walk.markStart(walk.parseCommit(id)) } }
            while (result.size < limit) {
                val commit = walk.next() ?: break
                val subject = commit.shortMessage
                val author = commit.authorIdent.name
                val hash = commit.name
                if (needle.isNotEmpty() &&
                    !subject.lowercase().contains(needle) &&
                    !author.lowercase().contains(needle) &&
                    !hash.lowercase().contains(needle)
                ) {
                    continue
                }
                result += summary(commit, refs[commit.id] ?: emptyList())
            }
        }
        return result
    }

    private fun readCommitDetail(git: Git, hash: String): GitCommitDetail? {
        val repository = git.repository
        val commit = runCatching {
            RevWalk(repository).use { walk -> walk.parseCommit(repository.resolve(hash) ?: return null) }
        }.getOrNull() ?: return null
        val parent = if (commit.parentCount > 0) {
            RevWalk(repository).use { walk -> walk.parseCommit(commit.getParent(0)) }
        } else {
            null
        }
        val files = diffEntries(repository, parent?.tree, commit.tree).map { entry ->
            val path = entry.newPath.takeUnless { it == DiffEntry.DEV_NULL } ?: entry.oldPath
            val (additions, deletions, binary) = diffStats(repository, entry)
            GitFileChange(
                path = path,
                status = entry.changeType.name,
                additions = additions,
                deletions = deletions,
                binary = binary,
            )
        }
        return GitCommitDetail(
            hash = commit.name,
            shortHash = commit.name.take(7),
            message = commit.fullMessage.trim(),
            author = commit.authorIdent.name,
            authorEmail = commit.authorIdent.emailAddress,
            time = commit.commitTime * 1000L,
            parents = commit.parents.map { it.name },
            files = files,
        )
    }

    private fun readCommitDiff(git: Git, hash: String, path: String): String {
        val repository = git.repository
        val commit = RevWalk(repository).use { walk ->
            walk.parseCommit(repository.resolve(hash) ?: throw SyncNoCommitException())
        }
        val parent = if (commit.parentCount > 0) {
            RevWalk(repository).use { walk -> walk.parseCommit(commit.getParent(0)) }
        } else {
            null
        }
        val entry = diffEntries(repository, parent?.tree, commit.tree)
            .firstOrNull { it.newPath == path || it.oldPath == path }
            ?: return ""
        return formatDiff(repository, entry).take(120_000)
    }

    private fun diffEntries(
        repository: Repository,
        oldTree: org.eclipse.jgit.lib.AnyObjectId?,
        newTree: org.eclipse.jgit.lib.AnyObjectId,
    ): List<DiffEntry> {
        DiffFormatter(ByteArrayOutputStream()).use { formatter ->
            formatter.setRepository(repository)
            formatter.setDetectRenames(true)
            return if (oldTree == null) {
                formatter.scan(EmptyTreeIterator(), org.eclipse.jgit.treewalk.CanonicalTreeParser().apply {
                    val reader = repository.newObjectReader()
                    reset(reader, newTree)
                })
            } else {
                formatter.scan(oldTree, newTree)
            }
        }
    }

    private fun formatDiff(repository: Repository, entry: DiffEntry): String {
        val output = ByteArrayOutputStream()
        DiffFormatter(output).use { formatter ->
            formatter.setRepository(repository)
            formatter.setQuotePaths(false)
            formatter.format(entry)
        }
        return output.toString(UTF_8.name())
    }

    private fun diffStats(repository: Repository, entry: DiffEntry): Triple<Int, Int, Boolean> {
        val text = formatDiff(repository, entry)
        if (text.contains("Binary files") || text.contains("GIT binary patch")) {
            return Triple(0, 0, true)
        }
        val lines = text.lines()
        return Triple(
            lines.count { it.startsWith("+") && !it.startsWith("+++") },
            lines.count { it.startsWith("-") && !it.startsWith("---") },
            false,
        )
    }

    private fun summary(commit: org.eclipse.jgit.revwalk.RevCommit, refs: List<String>) = GitCommitSummary(
        hash = commit.name,
        shortHash = commit.name.take(7),
        subject = commit.shortMessage,
        author = commit.authorIdent.name,
        time = commit.commitTime * 1000L,
        refs = refs,
    )

    private fun summary(repository: Repository, id: ObjectId, refs: List<String>): GitCommitSummary =
        RevWalk(repository).use { walk -> summary(walk.parseCommit(id), refs) }

    private fun refsByObject(repository: Repository, head: ObjectId): Map<ObjectId, List<String>> {
        val result = linkedMapOf<ObjectId, MutableList<String>>()
        repository.refDatabase.getRefsByPrefix("refs/").forEach { ref ->
            val id = ref.objectId ?: return@forEach
            val name = when {
                ref.name.startsWith("refs/heads/") -> ref.name.removePrefix("refs/heads/")
                ref.name.startsWith("refs/remotes/origin/") ->
                    "origin/" + ref.name.removePrefix("refs/remotes/origin/")
                ref.name.startsWith("refs/tags/") -> ref.name.removePrefix("refs/tags/")
                else -> return@forEach
            }
            result.getOrPut(id) { mutableListOf() } += name
        }
        result.getOrPut(head) { mutableListOf() } += "HEAD"
        return result.mapValues { it.value.distinct() }
    }

    private fun refsFor(repository: Repository, head: ObjectId): List<String> =
        refsByObject(repository, head)[head].orEmpty()

    private fun upstreamName(repository: Repository, branch: String): String? {
        val config = repository.config
        val remote = config.getString("branch", branch, "remote") ?: return null
        val merge = config.getString("branch", branch, "merge") ?: return null
        val remoteBranch = merge.removePrefix("refs/heads/")
        return "refs/remotes/$remote/$remoteBranch"
    }

    private fun relationCounts(repository: Repository, local: ObjectId, remote: ObjectId): Pair<Int, Int> {
        if (local == remote) return 0 to 0
        RevWalk(repository).use { walk ->
            val localCommit = walk.parseCommit(local)
            val remoteCommit = walk.parseCommit(remote)
            val localInRemote = walk.isMergedInto(localCommit, remoteCommit)
            val remoteInLocal = walk.isMergedInto(remoteCommit, localCommit)
            return when {
                remoteInLocal -> countCommits(repository, local, remote) to 0
                localInRemote -> 0 to countCommits(repository, remote, local)
                else -> countCommits(repository, local, null) to countCommits(repository, remote, null)
            }
        }
    }

    private fun readStatus(git: Git): SyncState {
        readPending()?.let { return SyncState.Conflict(it.conflicts.sorted()) }
        val status = git.status().call()
        if (status.conflicting.isNotEmpty()) return SyncState.Conflict(status.conflicting.sorted())
        val dirty = status.uncommittedChanges.size + status.untracked.size
        if (dirty > 0) return SyncState.Dirty(dirty)

        val remoteUrl = configProvider()?.url
        if (remoteUrl.isNullOrBlank()) return SyncState.Idle
        val head = git.repository.resolve(Constants.HEAD) ?: return SyncState.Synced
        val branch = currentBranch(git.repository) ?: DEFAULT_BRANCH
        val upstream = upstreamRef(git.repository, branch)
            ?: git.repository.resolve("refs/remotes/origin/$branch")
            ?: return SyncState.Ahead(countCommits(git.repository, head, null))
        return relationOf(git.repository, head, upstream)
    }

    private fun relationOf(repository: Repository, local: ObjectId, remote: ObjectId): SyncState {
        if (local == remote) return SyncState.Synced
        RevWalk(repository).use { walk ->
            val localCommit = walk.parseCommit(local)
            val remoteCommit = walk.parseCommit(remote)
            val localInRemote = walk.isMergedInto(localCommit, remoteCommit)
            val remoteInLocal = walk.isMergedInto(remoteCommit, localCommit)
            return when {
                localInRemote && remoteInLocal -> SyncState.Synced
                remoteInLocal -> SyncState.Ahead(countCommits(repository, local, remote))
                localInRemote -> SyncState.Behind(countCommits(repository, remote, local))
                else -> SyncState.Diverged(
                    ahead = countCommits(repository, local, remote),
                    behind = countCommits(repository, remote, local),
                )
            }
        }
    }

    private fun countCommits(repository: Repository, include: ObjectId, exclude: ObjectId?): Int {
        RevWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(include))
            exclude?.let { walk.markUninteresting(walk.parseCommit(it)) }
            var count = 0
            while (walk.next() != null) count++
            return count
        }
    }

    private suspend fun pullRemote(git: Git, remote: RemoteConfig, rebase: Boolean): PullOutcome {
        val branch = remoteBranch(git.repository, remote) ?: return PullOutcome.UpToDate
        val remoteRef = "refs/remotes/origin/$branch"
        val remoteHead = git.repository.resolve(remoteRef) ?: return PullOutcome.UpToDate
        val localHead = git.repository.resolve(Constants.HEAD)
        if (localHead == null) {
            adoptRemoteBranch(git, remoteRef, branch)
            return PullOutcome.Updated
        }
        return when {
            localHead == remoteHead -> PullOutcome.UpToDate
            isAncestor(git.repository, localHead, remoteHead) -> {
                fastForward(git, remoteRef, branch)
                PullOutcome.Updated
            }
            isAncestor(git.repository, remoteHead, localHead) -> PullOutcome.UpToDate
            rebase -> rebaseOnto(git, localHead, branch, remoteHead)
                ?: mergeRemote(git, localHead, branch, remoteRef, remoteHead)
            else -> mergeRemote(git, localHead, branch, remoteRef, remoteHead)
        }
    }

    private fun rebaseOnto(
        git: Git,
        localHead: ObjectId,
        branch: String,
        remoteHead: ObjectId,
    ): PullOutcome? {
        val result = runCatching {
            git.rebase().setUpstream(remoteHead).call()
        }
        val status = result.getOrNull()?.status
        if (result.isSuccess &&
            (status == org.eclipse.jgit.api.RebaseResult.Status.OK ||
                status == org.eclipse.jgit.api.RebaseResult.Status.FAST_FORWARD ||
                status == org.eclipse.jgit.api.RebaseResult.Status.UP_TO_DATE ||
                status == org.eclipse.jgit.api.RebaseResult.Status.NOTHING_TO_COMMIT)
        ) {
            setUpstream(git.repository, branch, branch)
            return PullOutcome.Updated
        }
        runCatching { git.rebase().setOperation(RebaseCommand.Operation.ABORT).call() }
        if (git.repository.resolve(Constants.HEAD) != localHead) {
            runCatching {
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(localHead.name).call()
            }
        }
        return null
    }

    private fun isAncestor(repository: Repository, ancestor: ObjectId, descendant: ObjectId): Boolean =
        RevWalk(repository).use { walk ->
            walk.isMergedInto(walk.parseCommit(ancestor), walk.parseCommit(descendant))
        }

    private fun fastForward(git: Git, remoteRef: String, remoteBranch: String) {
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteRef).call()
        val localBranch = currentBranch(git.repository) ?: remoteBranch
        setUpstream(git.repository, localBranch, remoteBranch)
    }

    // A fresh app repository starts on main. When the remote only has master,
    // follow the remote name instead of creating a second branch on push.
    private fun adoptRemoteBranch(git: Git, remoteRef: String, branch: String) {
        val current = currentBranch(git.repository)
        if (current != null && current != branch) {
            git.checkout()
                .setCreateBranch(true)
                .setName(branch)
                .setStartPoint(remoteRef)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .call()
        }
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteRef).call()
        setUpstream(git.repository, branch, branch)
    }

    private fun mergeRemote(
        git: Git,
        localHead: ObjectId,
        branch: String,
        remoteRef: String,
        remoteHead: ObjectId,
    ): PullOutcome {
        if (!hasMergeBase(git.repository, localHead, remoteHead)) {
            return mergeUnrelated(git, localHead, remoteHead, branch)
        }
        val result = git.merge()
            .include(remoteHead)
            .setFastForward(MergeCommand.FastForwardMode.NO_FF)
            .setCommit(false)
            .setMessage("merge: origin/$branch")
            .call()

        when (result.mergeStatus) {
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> return PullOutcome.UpToDate
            MergeResult.MergeStatus.FAST_FORWARD,
            MergeResult.MergeStatus.MERGED,
            MergeResult.MergeStatus.MERGED_NOT_COMMITTED,
            -> {
                commitMerge(git, branch)
                setUpstream(git.repository, branch, branch)
                return PullOutcome.Updated
            }
            MergeResult.MergeStatus.CONFLICTING -> Unit
            else -> {
                abortMerge(git)
                throw SyncMergeFailedException()
            }
        }

        val conflicts = collectIndexConflicts(git.repository)
        if (conflicts.isEmpty()) {
            abortMerge(git)
            throw SyncMergeFailedException()
        }
        val session = beginSession(git, branch, remoteHead, conflicts, emptyList())
        if (session.files.isEmpty()) {
            val plan = readPending() ?: throw SyncMergeFailedException()
            return finishSession(git, plan)
        }
        return PullOutcome.Conflicts(session)
    }

    // A local repository created before the remote was connected has no shared
    // commit with it. Git refuses such merges; for file tasks the union of both
    // trees is well defined, with same-path files going to the conflict screen.
    private fun mergeUnrelated(
        git: Git,
        localHead: ObjectId,
        remoteHead: ObjectId,
        branch: String,
    ): PullOutcome {
        val repository = git.repository
        val localFiles = treeFiles(repository, localHead)
        val remoteFiles = treeFiles(repository, remoteHead)
        val conflicts = localFiles.keys.intersect(remoteFiles.keys)
            .filter { path -> localFiles.getValue(path) != remoteFiles.getValue(path) }
            .sorted()
            .map { path ->
                ConflictEntry(
                    path = path,
                    local = readBytes(repository, localFiles.getValue(path)),
                    remote = readBytes(repository, remoteFiles.getValue(path)),
                )
            }
        val adds = (remoteFiles.keys - localFiles.keys).sorted()
        if (conflicts.isNotEmpty()) {
            val session = beginSession(git, branch, remoteHead, conflicts, adds)
            if (session.files.isEmpty()) {
                val plan = readPending() ?: throw SyncMergeFailedException()
                return finishSession(git, plan)
            }
            return PullOutcome.Conflicts(session)
        }

        adds.forEach { path ->
            writeAndStage(git, path, readBytes(repository, remoteFiles.getValue(path)))
        }
        repository.writeMergeHeads(listOf(remoteHead))
        repository.writeMergeCommitMsg("merge: origin/$branch (unrelated histories)")
        commitMerge(git, branch)
        val localBranch = currentBranch(repository) ?: branch
        setUpstream(repository, localBranch, branch)
        return PullOutcome.Updated
    }

    private fun beginSession(
        git: Git,
        branch: String,
        remoteHead: ObjectId,
        conflicts: List<ConflictEntry>,
        adds: List<String>,
    ): ConflictSession {
        val stamp = conflictStamp()
        val directory = sessionDirectory(stamp)
        directory.mkdirs()
        conflicts.forEach { conflict ->
            conflict.local?.let { writeSessionFile(directory, LOCAL_DIR, conflict.path, it) }
            conflict.remote?.let { writeSessionFile(directory, REMOTE_DIR, conflict.path, it) }
        }
        val plan = PendingPlan(
            stamp = stamp,
            branch = branch,
            adds = adds.sorted(),
            conflicts = conflicts.map { it.path }.sorted(),
        )
        writePending(plan)
        val repository = git.repository
        if (repository.repositoryState == RepositoryState.SAFE) {
            repository.writeMergeHeads(listOf(remoteHead))
            repository.writeMergeCommitMsg("merge: origin/$branch")
        }
        return loadSession(plan)
    }

    private fun finishSession(git: Git, plan: PendingPlan): PullOutcome {
        applyPlan(git, plan, ConflictResolution(emptyMap()))
        clearPending()
        pruneSessions()
        return PullOutcome.Updated
    }

    private fun applyPlan(git: Git, plan: PendingPlan, resolution: ConflictResolution) {
        val directory = sessionDirectory(plan.stamp)
        plan.conflicts.forEach { path ->
            val entry = ConflictEntry(
                path = path,
                local = readSessionFile(directory, LOCAL_DIR, path),
                remote = readSessionFile(directory, REMOTE_DIR, path),
            )
            if (entry.local == null && entry.remote == null) {
                throw SyncMergeFailedException()
            }
            val choice = resolution.choices[path] ?: ConflictChoice.Keep(entry.suggested())
            val bytes = when (choice) {
                is ConflictChoice.Keep ->
                    if (choice.side == ConflictSide.LOCAL) entry.local else entry.remote
                is ConflictChoice.Custom -> {
                    if (isTaskFile(path) && TaskMerge.invalidReasonRes(path, choice.text) != null) {
                        throw SyncInvalidConflictContentException()
                    }
                    choice.text.toByteArray(UTF_8)
                }
            }
            writeChosen(git, path, bytes)
        }
        plan.adds.forEach { path ->
            readSessionFile(directory, REMOTE_DIR, path)?.let { writeAndStage(git, path, it) }
        }
        if (git.status().call().conflicting.isNotEmpty()) throw SyncMergeFailedException()
        commitMerge(git, plan.branch)
        val localBranch = currentBranch(git.repository) ?: plan.branch
        setUpstream(git.repository, localBranch, plan.branch)
    }

    private fun writeChosen(git: Git, path: String, bytes: ByteArray?) {
        val target = File(repoDir, path)
        if (bytes == null) {
            target.delete()
            git.add().setUpdate(true).addFilepattern(path).call()
        } else {
            target.parentFile?.mkdirs()
            AtomicFiles.writeBytes(target, bytes)
            git.add().addFilepattern(path).call()
        }
    }

    private fun writeAndStage(git: Git, path: String, bytes: ByteArray) {
        val target = File(repoDir, path)
        target.parentFile?.mkdirs()
        AtomicFiles.writeBytes(target, bytes)
        git.add().addFilepattern(path).call()
    }

    private fun loadSession(plan: PendingPlan): ConflictSession {
        val directory = sessionDirectory(plan.stamp)
        val drafts = readDrafts(plan)
        val files = plan.conflicts.filter { isTaskFile(it) }.sorted().mapNotNull { path ->
            val entry = ConflictEntry(
                path = path,
                local = readSessionFile(directory, LOCAL_DIR, path),
                remote = readSessionFile(directory, REMOTE_DIR, path),
            )
            if (entry.local == null && entry.remote == null) null else entry.toConflictFile()
        }
        return ConflictSession(files, drafts)
    }

    private fun draftFile(plan: PendingPlan): File =
        File(sessionDirectory(plan.stamp), "$DRAFT_DIR/$DRAFT_FILE")

    private fun readDrafts(plan: PendingPlan): Map<String, ConflictChoice> {
        val file = draftFile(plan)
        if (!file.isFile) return emptyMap()
        val allowed = plan.conflicts.toSet()
        val result = linkedMapOf<String, ConflictChoice>()
        runCatching { file.readLines() }.getOrElse { return emptyMap() }
            .drop(1)
            .forEach { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size != 2 || parts[1] !in allowed) return@forEach
                when (parts[0]) {
                    DRAFT_LOCAL -> result[parts[1]] = ConflictChoice.Keep(ConflictSide.LOCAL)
                    DRAFT_REMOTE -> result[parts[1]] = ConflictChoice.Keep(ConflictSide.REMOTE)
                    DRAFT_CUSTOM -> {
                        val custom = File(sessionDirectory(plan.stamp), "$DRAFT_DIR/$DRAFT_CUSTOM_DIR/${parts[1]}")
                        if (custom.isFile) result[parts[1]] = ConflictChoice.Custom(custom.readText())
                    }
                }
            }
        return result
    }

    private fun writeDrafts(plan: PendingPlan, choices: Map<String, ConflictChoice>) {
        val directory = File(sessionDirectory(plan.stamp), DRAFT_DIR)
        directory.mkdirs()
        File(directory, DRAFT_CUSTOM_DIR).mkdirs()
        val lines = mutableListOf(DRAFT_VERSION)
        plan.conflicts.forEach { path ->
            when (val choice = choices[path]) {
                is ConflictChoice.Keep -> {
                    val token = if (choice.side == ConflictSide.LOCAL) DRAFT_LOCAL else DRAFT_REMOTE
                    lines += "$token\t$path"
                }
                is ConflictChoice.Custom -> {
                    val target = File(directory, "$DRAFT_CUSTOM_DIR/$path")
                    target.parentFile?.mkdirs()
                    AtomicFiles.writeText(target, choice.text)
                    lines += "$DRAFT_CUSTOM\t$path"
                }
                null -> Unit
            }
        }
        AtomicFiles.writeText(
            draftFile(plan),
            lines.joinToString(separator = "\n", postfix = "\n"),
        )
    }

    private fun collectIndexConflicts(repository: Repository): List<ConflictEntry> {
        val cache = repository.readDirCache()
        val stages = linkedMapOf<String, MutableMap<Int, ObjectId>>()
        for (index in 0 until cache.entryCount) {
            val entry = cache.getEntry(index)
            if (entry.stage == 0) continue
            stages.getOrPut(entry.pathString) { mutableMapOf() }[entry.stage] = entry.objectId
        }
        return stages.map { (path, ids) ->
            ConflictEntry(
                path = path,
                local = ids[2]?.let { readBytes(repository, it) },
                remote = ids[3]?.let { readBytes(repository, it) },
            )
        }
    }

    private fun readBytes(repository: Repository, id: ObjectId): ByteArray {
        val loader: ObjectLoader = repository.open(id)
        return loader.bytes
    }

    private fun commitMerge(git: Git, branch: String) {
        val identity = commitIdentity()
        git.commit()
            .setMessage("merge: origin/$branch")
            .setAuthor(identity)
            .setCommitter(identity)
            .call()
    }

    // PersonIdent captures its timestamp on construction, so every commit
    // needs a fresh identity.
    private fun commitIdentity(): PersonIdent {
        val config = configProvider()
        val name = config?.authorName?.takeIf { it.isNotBlank() } ?: AUTHOR_NAME
        val email = config?.authorEmail?.takeIf { it.isNotBlank() } ?: AUTHOR_EMAIL
        return PersonIdent(name, email)
    }

    private fun hasMergeBase(repository: Repository, local: ObjectId, remote: ObjectId): Boolean =
        RevWalk(repository).use { walk ->
            walk.revFilter = RevFilter.MERGE_BASE
            walk.markStart(walk.parseCommit(local))
            walk.markStart(walk.parseCommit(remote))
            walk.next() != null
        }

    private fun treeFiles(repository: Repository, commitId: ObjectId): Map<String, ObjectId> {
        RevWalk(repository).use { walk ->
            val commit = walk.parseCommit(commitId)
            val files = linkedMapOf<String, ObjectId>()
            TreeWalk(repository).use { tree ->
                tree.addTree(commit.tree)
                tree.isRecursive = true
                while (tree.next()) {
                    if (tree.isSubtree) continue
                    files[tree.pathString] = tree.getObjectId(0)
                }
            }
            return files
        }
    }

    private fun abortMerge(git: Git) {
        val repository = git.repository
        repository.writeMergeHeads(null)
        repository.writeMergeCommitMsg(null)
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
    }

    private fun pushBranch(git: Git, remote: RemoteConfig, head: ObjectId) {
        val localBranch = currentBranch(git.repository)
            ?: remote.branch?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_BRANCH
        val remoteBranch = remote.branch?.trim()?.takeIf { it.isNotEmpty() } ?: localBranch
        val results = git.push()
            .setRemote(ORIGIN)
            .setRefSpecs(RefSpec("refs/heads/$localBranch:refs/heads/$remoteBranch"))
            .setCredentialsProvider(credentials(remote))
            .setTimeout(NETWORK_TIMEOUT_SECONDS)
            .call()
        val update = results.asSequence()
            .flatMap { it.remoteUpdates.asSequence() }
            .firstOrNull { it.remoteName == "refs/heads/$remoteBranch" }
        if (update == null || update.status !in ACCEPTED_PUSH_STATES) {
            throw SyncPushRejectedException()
        }
        setUpstream(git.repository, localBranch, remoteBranch)
        updateTrackingRef(git.repository, remoteBranch, head)
    }

    private fun updateTrackingRef(repository: Repository, branch: String, head: ObjectId) {
        val update = repository.updateRef("refs/remotes/origin/$branch")
        update.setNewObjectId(head)
        update.setForceUpdate(true)
        update.update()
    }

    private fun prepareTransport(remote: RemoteConfig) {
        if (!isSsh(remote)) return
        SshSessionFactory.setInstance(
            TohruSshFactory(
                keyFile = remote.sshKeyFile,
                passphrase = remote.sshPassphrase,
                knownHosts = remote.knownHostsFile,
            ),
        )
    }

    private fun credentials(remote: RemoteConfig): CredentialsProvider? {
        if (isSsh(remote)) {
            return SshCredentialsProvider(confirmHostKey, remote.sshPassphrase)
        }
        val token = remote.token?.takeIf { it.isNotBlank() } ?: return null
        val username = remote.username?.takeIf { it.isNotBlank() } ?: DEFAULT_HTTP_USER
        return UsernamePasswordCredentialsProvider(username, token)
    }

    private fun isSsh(remote: RemoteConfig): Boolean = RemoteUrls.isSsh(remote.url)

    private fun fetch(git: Git, remote: RemoteConfig) {
        git.fetch()
            .setRemote(ORIGIN)
            .setRemoveDeletedRefs(true)
            .setCredentialsProvider(credentials(remote))
            .setTimeout(NETWORK_TIMEOUT_SECONDS)
            .call()
    }

    private fun isStaleBranch(repository: Repository, branch: String): Boolean {
        val config = repository.config
        val remote = config.getString("branch", branch, "remote") ?: return false
        val merge = config.getString("branch", branch, "merge") ?: return false
        val upstream = if (remote == ".") {
            merge
        } else {
            "refs/remotes/$remote/${merge.removePrefix("refs/heads/")}"
        }
        return repository.exactRef(upstream) == null
    }

    private fun remoteBranch(repository: Repository, remote: RemoteConfig): String? {
        remote.branch?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val current = currentBranch(repository)
        if (current != null) {
            if (repository.exactRef("refs/remotes/origin/$current") != null) return current
            // A new local branch has no remote counterpart yet. Do not pull
            // the default branch into it.
            if (current != DEFAULT_BRANCH && current != "main" && current != "master") {
                return null
            }
        }
        val originHead = repository.exactRef("refs/remotes/origin/HEAD")
        val symbolicTarget = originHead?.takeIf { it.isSymbolic }?.target?.name
        symbolicTarget?.removePrefix("refs/remotes/origin/")
            ?.takeIf { it.isNotEmpty() && it != "HEAD" }
            ?.let { return it }
        for (candidate in listOf("main", "master")) {
            val ref = "refs/remotes/origin/$candidate"
            if (repository.exactRef(ref) != null) return candidate
        }
        return repository.refDatabase.getRefsByPrefix("refs/remotes/origin/")
            .asSequence()
            .map { it.name.removePrefix("refs/remotes/origin/") }
            .firstOrNull { it.isNotEmpty() && it != "HEAD" }
    }

    private fun currentBranch(repository: Repository): String? =
        repository.branch?.takeIf { it.isNotBlank() }

    private fun setUpstream(repository: Repository, localBranch: String, remoteBranch: String) {
        val config = repository.config
        config.setString("branch", localBranch, "remote", ORIGIN)
        config.setString("branch", localBranch, "merge", "refs/heads/$remoteBranch")
        config.save()
    }

    private fun upstreamRef(repository: Repository, localBranch: String): ObjectId? {
        val config = repository.config
        val remote = config.getString("branch", localBranch, "remote") ?: return null
        val merge = config.getString("branch", localBranch, "merge") ?: return null
        val remoteBranch = merge.removePrefix("refs/heads/")
        return repository.resolve("refs/remotes/$remote/$remoteBranch")
    }

    private fun configureRemote(git: Git, remote: RemoteConfig) {
        val config = git.repository.config
        var changed = false
        fun put(key: String, value: String) {
            if (config.getString("remote", ORIGIN, key) == value) return
            config.setString("remote", ORIGIN, key, value)
            changed = true
        }
        put("url", remote.url)
        put("fetch", "+refs/heads/*:refs/remotes/origin/*")
        if (changed) config.save()
    }

    private fun requireRemote(): RemoteConfig {
        val remote = configProvider()?.takeIf { it.url.isNotBlank() } ?: throw SyncNotConfiguredException()
        // Tokens only authenticate HTTPS; SSH needs a key before any connection.
        if (RemoteUrls.isSsh(remote.url) && remote.sshKeyFile == null) {
            throw SyncSshKeyMissingException()
        }
        return remote
    }

    private fun commitAll(git: Git, message: String) {
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
        if (isClean(git.status().call())) return
        val repository = git.repository
        val head = repository.resolve(Constants.HEAD)
        val headCommit = head?.let { id ->
            RevWalk(repository).use { walk -> walk.parseCommit(id) }
        }
        val identity = commitIdentity()
        val branch = currentBranch(repository) ?: DEFAULT_BRANCH
        repository.newObjectInserter().use { inserter ->
            val tree = treeWithReusedBlobs(repository, headCommit?.tree, inserter)
            if (headCommit != null && tree == headCommit.tree) {
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(headCommit.name).call()
                return
            }
            val builder = CommitBuilder().apply {
                setTreeId(tree)
                setMessage(message)
                setAuthor(identity)
                setCommitter(identity)
                headCommit?.let { addParentId(it) }
            }
            val id = inserter.insert(builder)
            inserter.flush()
            val update = repository.updateRef("refs/heads/$branch")
            update.setNewObjectId(id)
            update.setForceUpdate(true)
            val result = update.update()
            if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NEW) {
                error("cannot update branch after commit")
            }
        }
        git.reset().setMode(ResetCommand.ResetType.HARD).call()
    }

    private fun commitWithSquash(git: Git, message: String) {
        val repository = git.repository
        val head = repository.resolve(Constants.HEAD)
        val commits = unpushedCommits(repository)
        val base = unpushedBase(repository)
        if (commits.isEmpty() || base == null) {
            commitAll(git, message)
            return
        }
        head?.let { writeRecoveryRef(repository, it) }

        // Stage the current worktree, then build one commit from its tree.
        // Every commit above the upstream is folded into this commit.
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
        repository.newObjectInserter().use { inserter ->
            val tree = treeWithReusedBlobs(repository, base.tree, inserter)
            if (tree == base.tree) {
                // The local commits cancel each other out. Drop them without
                // creating an empty commit.
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(base.name).call()
                return@use
            }
            val identity = commitIdentity()
            val builder = CommitBuilder().apply {
                setTreeId(tree)
                setMessage(message)
                setAuthor(identity)
                setCommitter(identity)
                addParentId(base)
            }
            val id = inserter.insert(builder)
            inserter.flush()
            val branch = currentBranch(repository) ?: DEFAULT_BRANCH
            val update = repository.updateRef("refs/heads/$branch")
            update.setNewObjectId(id)
            update.setForceUpdate(true)
            val result = update.update()
            if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NEW) {
                error("cannot update branch after squash")
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(id.name).call()
        }
    }

    // `updated` is part of the task file, so reverting an edit leaves a diff
    // that only moves that line. Reuse the reference blob for those files so a
    // revert cancels out instead of creating a timestamp-only commit.
    private fun treeWithReusedBlobs(
        repository: Repository,
        reference: RevTree?,
        inserter: ObjectInserter,
    ): ObjectId {
        val index = repository.readDirCache()
        if (reference == null) return index.writeTree(inserter)
        val target = DirCache.newInCore()
        val builder = target.builder()
        for (position in 0 until index.entryCount) {
            val entry = index.getEntry(position)
            val path = entry.pathString
            val referenceId = if (entry.stage == 0 && isTaskFile(path)) {
                treeBlobId(repository, reference, path)
            } else {
                null
            }
            if (referenceId != null && referenceId != entry.objectId &&
                sameTaskContent(repository, entry.objectId, referenceId)
            ) {
                val reused = DirCacheEntry(entry)
                reused.setObjectId(referenceId)
                builder.add(reused)
            } else {
                builder.add(entry)
            }
        }
        builder.finish()
        return target.writeTree(inserter)
    }

    private fun sameTaskContent(repository: Repository, first: ObjectId, second: ObjectId): Boolean =
        runCatching {
            timestampFreeText(repository.open(first).bytes) ==
                timestampFreeText(repository.open(second).bytes)
        }.getOrDefault(false)

    private fun timestampFreeText(bytes: ByteArray): String {
        val text = String(bytes, UTF_8)
        val lines = text.lines()
        if (lines.firstOrNull() != "---") return text
        val end = lines.drop(1).indexOf("---").let { if (it < 0) return text else it + 1 }
        return lines
            .filterIndexed { index, line -> index !in 1 until end || !line.startsWith("updated:") }
            .joinToString("\n")
    }

    private fun treeBlobId(repository: Repository, tree: RevTree, path: String): ObjectId? =
        runCatching {
            TreeWalk.forPath(repository, path, tree)?.use { walk -> walk.getObjectId(0) }
        }.getOrNull()

    private fun autoCommitWorktree(git: Git) {
        val status = git.status().call()
        val taskChanges = (status.uncommittedChanges + status.untracked)
            .filter(::isTaskPath)
        if (taskChanges.isEmpty()) return
        val stagedOutside = (status.added + status.changed + status.removed)
            .filterNot(::isTaskPath)
        if (stagedOutside.isNotEmpty()) {
            error("unrelated staged changes present")
        }
        git.add().addFilepattern("tasks").call()
        git.add().setUpdate(true).addFilepattern("tasks").call()
        val staged = git.status().call()
        if (staged.added.isEmpty() && staged.changed.isEmpty() && staged.removed.isEmpty()) return
        val paths = (staged.added + staged.changed + staged.removed).distinct().sorted()
        val count = paths.size
        val subject = if (count == 1) {
            "chore(tasks): autosave ${paths.single().substringAfterLast('/')}"
        } else {
            "chore(tasks): autosave $count files"
        }
        val identity = commitIdentity()
        git.commit()
            .setMessage(subject)
            .setAuthor(identity)
            .setCommitter(identity)
            .call()
    }

    private fun unpushedBase(repository: Repository): RevCommit? {
        val head = repository.resolve(Constants.HEAD) ?: return null
        val branch = currentBranch(repository) ?: return null
        val upstream = upstreamName(repository, branch)
            ?: "refs/remotes/origin/$branch".takeIf { repository.resolve(it) != null }
            ?: return null
        val baseId = repository.resolve(upstream) ?: return null
        if (!isAncestor(repository, baseId, head)) return null
        return repository.parseCommit(baseId)
    }

    private fun unpushedCommits(repository: Repository): List<RevCommit> {
        val head = repository.resolve(Constants.HEAD) ?: return emptyList()
        val base = unpushedBase(repository) ?: return emptyList()
        RevWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(head))
            walk.markUninteresting(walk.parseCommit(base))
            val result = mutableListOf<RevCommit>()
            while (true) {
                result += walk.next() ?: break
            }
            return result
        }
    }

    // A squash folds every unpushed commit into one commit over the current
    // worktree. Count that final set: one entry per task id, so a task moved
    // through several layouts stays one file instead of delete plus add.
    private fun pendingPaths(
        repository: Repository,
        commits: List<RevCommit>,
        dirtyFiles: List<String>,
    ): List<String> {
        val taskPaths = linkedMapOf<String, String>()
        val otherPaths = linkedSetOf<String>()

        fun add(path: String, id: String?) {
            if (id == null) otherPaths += path else if (id !in taskPaths) taskPaths[id] = path
        }

        dirtyFiles.forEach { path ->
            add(path, workingTaskId(path) ?: indexTaskId(repository, path))
        }
        commits.forEach { commit ->
            val parent = if (commit.parentCount == 0) {
                null
            } else {
                RevWalk(repository).use { walk -> walk.parseCommit(commit.getParent(0)) }
            }
            diffEntries(repository, parent?.tree, commit.tree).forEach { entry ->
                val deleted = entry.newPath == DiffEntry.DEV_NULL
                val path = if (deleted) entry.oldPath else entry.newPath
                val tree = if (deleted) parent?.tree else commit.tree
                add(path, treeTaskId(repository, tree, path))
            }
        }
        val base = unpushedBase(repository)
        return (otherPaths + taskPaths.values)
            .filterNot { path -> base != null && onlyTimestampChanged(repository, base, path) }
            .sorted()
    }

    // A task that matches the upstream file apart from the updated line is
    // dropped by the squash, so it does not count as a pending change.
    private fun onlyTimestampChanged(repository: Repository, base: RevCommit, path: String): Boolean {
        if (!isTaskFile(path)) return false
        val baseId = treeBlobId(repository, base.tree, path) ?: return false
        val file = File(repoDir, path)
        if (!file.isFile) return false
        val current = runCatching { file.readBytes() }.getOrNull() ?: return false
        val previous = runCatching { repository.open(baseId).bytes }.getOrNull() ?: return false
        return timestampFreeText(current) == timestampFreeText(previous)
    }

    private fun treeTaskId(repository: Repository, tree: org.eclipse.jgit.lib.AnyObjectId?, path: String): String? =
        runCatching {
            if (tree == null) return null
            TreeWalk.forPath(repository, path, tree)?.use { walk ->
                val bytes = repository.open(walk.getObjectId(0)).bytes
                TaskFile.deserialize(String(bytes, UTF_8))?.id
            }
        }.getOrNull()

    private fun writeRecoveryRef(repository: Repository, head: ObjectId) {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date())
        val refName = "refs/tohru/recovery/${stamp}_${head.name.take(8)}"
        val update = repository.updateRef(refName)
        update.setNewObjectId(head)
        update.setForceUpdate(true)
        val result = update.update()
        if (result != RefUpdate.Result.NEW &&
            result != RefUpdate.Result.FORCED &&
            result != RefUpdate.Result.NO_CHANGE
        ) {
            error("cannot write recovery ref $refName")
        }
    }

    private fun isTaskPath(path: String): Boolean =
        path == "tasks" || path.startsWith("tasks/")

    private fun isClean(status: Status): Boolean =
        status.added.isEmpty() &&
            status.changed.isEmpty() &&
            status.removed.isEmpty() &&
            status.missing.isEmpty() &&
            status.modified.isEmpty() &&
            status.untracked.isEmpty()

    private fun ensureRepository() {
        if (!repoDir.exists()) repoDir.mkdirs()
        if (!File(repoDir, ".git").exists()) {
            Git.init().setDirectory(repoDir).setInitialBranch(DEFAULT_BRANCH).call().use { }
        }
        updateExcludes()
        Git.open(repoDir).use { git ->
            keepUnicodePaths(git.repository)
            val pending = readPending()
            val state = git.repository.repositoryState
            when {
                state == RepositoryState.MERGING || state == RepositoryState.MERGING_RESOLVED ->
                    if (pending == null) abortMerge(git)
                pending != null -> clearPending()
            }
        }
    }

    // Patches and CLI output quote non-ASCII path bytes by default. The repo
    // stores names as UTF-8, so disable quoting for this clone.
    private fun keepUnicodePaths(repository: Repository) {
        val config = repository.config
        if (!config.getBoolean("core", null, "quotePath", true)) return
        config.setBoolean("core", null, "quotePath", false)
        config.save()
    }

    private fun updateExcludes() {
        val exclude = File(repoDir, ".git/info/exclude")
        val current = if (exclude.exists()) exclude.readLines().toMutableList() else mutableListOf()
        val missing = EXCLUDES.filter { it !in current }
        if (missing.isEmpty()) return
        exclude.parentFile?.mkdirs()
        exclude.appendText(missing.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun isTaskFile(path: String): Boolean =
        path.startsWith("tasks/") && path.endsWith(".md")

    private fun pendingFile(): File = File(repoDir, "$CONFLICT_DIR/$PENDING_FILE")

    private fun sessionDirectory(stamp: String): File = File(repoDir, "$CONFLICT_DIR/$stamp")

    private fun writeSessionFile(directory: File, side: String, path: String, bytes: ByteArray) {
        val target = File(directory, "$side/$path")
        target.parentFile?.mkdirs()
        AtomicFiles.writeBytes(target, bytes)
    }

    private fun readSessionFile(directory: File, side: String, path: String): ByteArray? {
        val source = File(directory, "$side/$path")
        return if (source.isFile) source.readBytes() else null
    }

    private fun readPending(): PendingPlan? {
        val file = pendingFile()
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrElse { return null }
        if (lines.firstOrNull() != PENDING_VERSION) {
            file.delete()
            return null
        }
        var stamp: String? = null
        var branch: String? = null
        val adds = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        lines.drop(1).forEach { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) return@forEach
            when (parts[0]) {
                PENDING_STAMP -> stamp = parts[1]
                PENDING_BRANCH -> branch = parts[1]
                PENDING_ADD -> adds += parts[1]
                PENDING_CONFLICT -> conflicts += parts[1]
            }
        }
        if (stamp.isNullOrBlank() || branch.isNullOrBlank()) {
            file.delete()
            return null
        }
        if (!sessionDirectory(stamp).isDirectory) {
            file.delete()
            return null
        }
        return PendingPlan(stamp, branch, adds.sorted(), conflicts.sorted())
    }

    private fun writePending(plan: PendingPlan) {
        val file = pendingFile()
        file.parentFile?.mkdirs()
        val content = buildString {
            appendLine(PENDING_VERSION)
            appendLine("$PENDING_STAMP\t${plan.stamp}")
            appendLine("$PENDING_BRANCH\t${plan.branch}")
            plan.adds.forEach { appendLine("$PENDING_ADD\t$it") }
            plan.conflicts.forEach { appendLine("$PENDING_CONFLICT\t$it") }
        }
        AtomicFiles.writeText(file, content)
    }

    private fun clearPending() {
        pendingFile().delete()
    }

    private fun pruneSessions() {
        val root = File(repoDir, CONFLICT_DIR)
        val pending = readPending()?.stamp
        val sessions = root.listFiles { file -> file.isDirectory && STAMP_PATTERN.matches(file.name) }
            ?.sortedByDescending { it.name }
            .orEmpty()
        sessions.drop(MAX_CONFLICT_SESSIONS)
            .filter { it.name != pending }
            .forEach { it.deleteRecursively() }
        if (root.isDirectory && root.listFiles().isNullOrEmpty()) root.delete()
    }

    private fun conflictStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())

    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private data class PendingPlan(
        val stamp: String,
        val branch: String,
        val adds: List<String>,
        val conflicts: List<String>,
    )

    private data class ConflictEntry(
        val path: String,
        val local: ByteArray?,
        val remote: ByteArray?,
    ) {
        fun localText(): String? = local?.let { String(it, UTF_8) }
        fun remoteText(): String? = remote?.let { String(it, UTF_8) }
        fun suggested(): ConflictSide = TaskMerge.suggest(localText(), remoteText())
        fun toConflictFile(): ConflictFile = ConflictFile(
            path = path,
            local = TaskMerge.describe(localText()),
            remote = TaskMerge.describe(remoteText()),
            suggested = suggested(),
        )
    }

    companion object {
        private const val DEFAULT_BRANCH = "main"
        private const val ORIGIN = "origin"
        private const val AUTHOR_NAME = "Tohru"
        private const val AUTHOR_EMAIL = "tohru@localhost"
        private const val DEFAULT_HTTP_USER = "oauth2"
        private const val NETWORK_TIMEOUT_SECONDS = 60
        private val EXCLUDES = listOf(".sync-conflict/")
        private val ACCEPTED_PUSH_STATES = setOf(
            RemoteRefUpdate.Status.OK,
            RemoteRefUpdate.Status.UP_TO_DATE,
        )

        private const val CONFLICT_DIR = ".sync-conflict"
        private const val PENDING_FILE = "pending.txt"
        private const val PENDING_VERSION = "v1"
        private const val PENDING_STAMP = "stamp"
        private const val PENDING_BRANCH = "branch"
        private const val PENDING_ADD = "add"
        private const val PENDING_CONFLICT = "conflict"
        private const val LOCAL_DIR = "local"
        private const val REMOTE_DIR = "remote"
        private const val MAX_CONFLICT_SESSIONS = 10
        private const val DRAFT_DIR = "draft"
        private const val DRAFT_FILE = "choices.txt"
        private const val DRAFT_VERSION = "v1"
        private const val DRAFT_LOCAL = "local"
        private const val DRAFT_REMOTE = "remote"
        private const val DRAFT_CUSTOM = "custom"
        private const val DRAFT_CUSTOM_DIR = "custom"
        private val STAMP_PATTERN = Regex("\\d{8}-\\d{6}-\\d{3}")
    }
}

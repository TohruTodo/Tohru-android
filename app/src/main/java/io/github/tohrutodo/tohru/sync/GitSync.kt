package io.github.tohrutodo.tohru.sync

import java.io.File

// Repository state as seen by the UI. Remote relations are only known when a
// remote is configured; Idle means local-only history.
sealed interface SyncState {
    data object Idle : SyncState
    data object Synced : SyncState
    data class Dirty(val files: Int) : SyncState
    data class Conflict(val files: List<String>) : SyncState
    data class Ahead(val commits: Int) : SyncState
    data class Behind(val commits: Int) : SyncState
    data class Diverged(val ahead: Int, val behind: Int) : SyncState
    data class Error(val failure: SyncFailure) : SyncState
}

// Remote settings are read for every operation, so saving the form takes
// effect without rebuilding the engine.
data class RemoteConfig(
    val url: String,
    val username: String? = null,
    val token: String? = null,
    val branch: String? = null,
    val sshKeyFile: File? = null,
    val sshPassphrase: String? = null,
    val knownHostsFile: File? = null,
    val authorName: String? = null,
    val authorEmail: String? = null,
)

enum class ConflictSide { LOCAL, REMOTE }

// One side of a conflicted file, described from the parsed task when possible.
// `text` is the full content so the conflict screen can show a diff and edit.
data class ConflictVersion(
    val exists: Boolean,
    val title: String? = null,
    val updated: Long? = null,
    val preview: String = "",
    val text: String = "",
    val lineCount: Int = 0,
)

data class ConflictFile(
    val path: String,
    val local: ConflictVersion,
    val remote: ConflictVersion,
    val suggested: ConflictSide,
)

// A merge left open for the user. The engine keeps the merge state on disk
// until the screen commits or discards it. `drafts` restores choices made
// before the app was closed, including text saved from the editor.
data class ConflictSession(
    val files: List<ConflictFile>,
    val drafts: Map<String, ConflictChoice> = emptyMap(),
)

sealed interface ConflictChoice {
    data class Keep(val side: ConflictSide) : ConflictChoice
    data class Custom(val text: String) : ConflictChoice
}

data class ConflictResolution(val choices: Map<String, ConflictChoice>) {
    companion object {
        fun suggested(files: List<ConflictFile>): ConflictResolution = ConflictResolution(
            files.associate { it.path to ConflictChoice.Keep(it.suggested) },
        )
    }
}

sealed interface PullOutcome {
    data object UpToDate : PullOutcome
    data object Updated : PullOutcome
    data class Conflicts(val session: ConflictSession) : PullOutcome
}


data class GitCommitSummary(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val time: Long,
    val refs: List<String> = emptyList(),
)

data class GitFileChange(
    val path: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val binary: Boolean = false,
)

data class GitCommitDetail(
    val hash: String,
    val shortHash: String,
    val message: String,
    val author: String,
    val authorEmail: String,
    val time: Long,
    val parents: List<String>,
    val files: List<GitFileChange>,
)

data class GitMaintenance(
    val recoveryKeep: Int = 20,
    val gcEnabled: Boolean = true,
    val gcIntervalDays: Int = 7,
    val exportKeep: Int = 2,
)

data class GitBranch(
    val name: String,
    val current: Boolean = false,
    val remote: Boolean = false,
    val stale: Boolean = false,
)

enum class DirtyKind { MODIFIED, ADDED, REMOVED, UNTRACKED, MOVED, CONFLICTING }

data class GitSnapshot(
    val branch: String? = null,
    val upstream: String? = null,
    val remoteConfigured: Boolean = false,
    val dirtyFiles: List<String> = emptyList(),
    val dirtyKinds: Map<String, DirtyKind> = emptyMap(),
    val unpushedCommits: Int = 0,
    val pendingFiles: Int = 0,
    val pendingFileName: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val conflicts: List<String> = emptyList(),
    val lastCommit: GitCommitSummary? = null,
    val error: SyncFailure? = null,
)

interface GitSync {
    suspend fun status(): SyncState
    suspend fun commit(message: String): Result<Unit>
    suspend fun autoCommit(): Result<Unit> = Result.success(Unit)
    suspend fun pull(rebase: Boolean = false): Result<PullOutcome>
    suspend fun pendingSession(): ConflictSession?
    suspend fun saveDrafts(choices: Map<String, ConflictChoice>): Result<Unit>
    suspend fun applySession(resolution: ConflictResolution): Result<PullOutcome>
    suspend fun discardSession(): Result<Unit>
    suspend fun push(): Result<Unit>
    suspend fun fetch(): Result<Unit> = Result.success(Unit)
    suspend fun branches(): Result<List<GitBranch>> = Result.success(emptyList())
    suspend fun checkout(branch: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("checkout is not supported"))
    suspend fun createBranch(branch: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("branch creation is not supported"))
    suspend fun deleteBranch(branch: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("branch deletion is not supported"))
    suspend fun maintain(): Result<Unit> = Result.success(Unit)
    suspend fun snapshot(): GitSnapshot = GitSnapshot()
    suspend fun history(limit: Int = 50, allBranches: Boolean = true, query: String = ""): Result<List<GitCommitSummary>> =
        Result.success(emptyList())
    suspend fun commitDetail(hash: String): Result<GitCommitDetail?> = Result.success(null)
    suspend fun commitDiff(hash: String, path: String): Result<String> = Result.success("")
}

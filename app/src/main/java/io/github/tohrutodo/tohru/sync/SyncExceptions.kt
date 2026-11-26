package io.github.tohrutodo.tohru.sync

// Sync runs return failures; these types let the UI name the cause without
// matching on transport messages.
class SyncNotConfiguredException : IllegalStateException("remote url is empty")

class SyncSshKeyMissingException : IllegalStateException("ssh remote needs a private key")

class SyncNoPendingConflictException : IllegalStateException("no pending conflict session")

class SyncInvalidConflictContentException : IllegalStateException("edited task content is invalid")

class SyncNoCommitException : IllegalStateException("repository has no commit")

class SyncDirtyWorkTreeException : IllegalStateException("worktree has uncommitted changes")

class SyncPushRejectedException : IllegalStateException("remote rejected the push")

class SyncMergeFailedException : IllegalStateException("merge from remote failed")

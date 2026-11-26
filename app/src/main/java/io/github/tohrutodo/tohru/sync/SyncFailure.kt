package io.github.tohrutodo.tohru.sync

import androidx.annotation.StringRes
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.errors.TransportException
import io.github.tohrutodo.tohru.R

enum class SyncErrorCode {
    NOT_CONFIGURED,
    SSH_KEY_MISSING,
    NO_PENDING_CONFLICT,
    INVALID_CONFLICT_CONTENT,
    NO_COMMIT,
    DIRTY_WORKTREE,
    PUSH_REJECTED,
    MERGE_FAILED,
    REMOTE_NOT_FOUND,
    HOST_UNREACHABLE,
    CONNECTION_FAILED,
    TLS_FAILED,
    TRANSPORT_FAILED,
    UNKNOWN,
}

data class SyncFailure(
    val code: SyncErrorCode,
    val cause: Throwable? = null,
)

fun syncFailure(error: Throwable): SyncFailure {
    val causes = generateSequence(error) { it.cause }.toList()
    val code = when {
        error is SyncNotConfiguredException -> SyncErrorCode.NOT_CONFIGURED
        error is SyncSshKeyMissingException -> SyncErrorCode.SSH_KEY_MISSING
        error is SyncNoPendingConflictException -> SyncErrorCode.NO_PENDING_CONFLICT
        error is SyncInvalidConflictContentException -> SyncErrorCode.INVALID_CONFLICT_CONTENT
        error is SyncNoCommitException -> SyncErrorCode.NO_COMMIT
        error is SyncDirtyWorkTreeException -> SyncErrorCode.DIRTY_WORKTREE
        error is SyncPushRejectedException -> SyncErrorCode.PUSH_REJECTED
        error is SyncMergeFailedException -> SyncErrorCode.MERGE_FAILED
        causes.any { it is NoRemoteRepositoryException || it is RepositoryNotFoundException } ->
            SyncErrorCode.REMOTE_NOT_FOUND
        causes.any { it is UnknownHostException } -> SyncErrorCode.HOST_UNREACHABLE
        causes.any {
            it is ConnectException ||
                it is NoRouteToHostException ||
                it is SocketTimeoutException ||
                it is SocketException
        } -> SyncErrorCode.CONNECTION_FAILED
        causes.any { it is SSLException } -> SyncErrorCode.TLS_FAILED
        causes.any { it is TransportException } -> SyncErrorCode.TRANSPORT_FAILED
        else -> SyncErrorCode.UNKNOWN
    }
    return SyncFailure(code, error)
}

@StringRes
fun syncFailureRes(failure: SyncFailure): Int = when (failure.code) {
    SyncErrorCode.NOT_CONFIGURED -> R.string.sync_error_not_configured
    SyncErrorCode.SSH_KEY_MISSING -> R.string.sync_error_ssh_key_missing
    SyncErrorCode.NO_PENDING_CONFLICT -> R.string.sync_error_no_pending_conflict
    SyncErrorCode.INVALID_CONFLICT_CONTENT -> R.string.sync_error_invalid_conflict_content
    SyncErrorCode.NO_COMMIT -> R.string.sync_error_no_commit
    SyncErrorCode.DIRTY_WORKTREE -> R.string.sync_error_dirty_worktree
    SyncErrorCode.PUSH_REJECTED -> R.string.sync_error_push_rejected
    SyncErrorCode.MERGE_FAILED -> R.string.sync_error_merge_failed
    SyncErrorCode.REMOTE_NOT_FOUND -> R.string.sync_error_remote_not_found
    SyncErrorCode.HOST_UNREACHABLE -> R.string.sync_error_host_unreachable
    SyncErrorCode.CONNECTION_FAILED -> R.string.sync_error_connection_failed
    SyncErrorCode.TLS_FAILED -> R.string.sync_error_tls_failed
    SyncErrorCode.TRANSPORT_FAILED -> R.string.sync_error_transport_failed
    SyncErrorCode.UNKNOWN -> R.string.sync_error_unknown
}

@StringRes
fun syncFailureRes(error: Throwable): Int = syncFailureRes(syncFailure(error))

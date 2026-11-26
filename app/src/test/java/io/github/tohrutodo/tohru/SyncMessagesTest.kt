package io.github.tohrutodo.tohru

import org.eclipse.jgit.errors.TransportException
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.tohrutodo.tohru.sync.SyncInvalidConflictContentException
import io.github.tohrutodo.tohru.sync.SyncMergeFailedException
import io.github.tohrutodo.tohru.sync.SyncNoCommitException
import io.github.tohrutodo.tohru.sync.SyncNoPendingConflictException
import io.github.tohrutodo.tohru.sync.SyncNotConfiguredException
import io.github.tohrutodo.tohru.sync.SyncPushRejectedException
import io.github.tohrutodo.tohru.sync.SyncSshKeyMissingException
import io.github.tohrutodo.tohru.sync.syncFailureRes

class SyncMessagesTest {
    @Test fun failuresNameKnownCauses() {
        assertEquals(R.string.sync_error_not_configured, syncFailureRes(SyncNotConfiguredException()))
        assertEquals(R.string.sync_error_ssh_key_missing, syncFailureRes(SyncSshKeyMissingException()))
        assertEquals(R.string.sync_error_no_pending_conflict, syncFailureRes(SyncNoPendingConflictException()))
        assertEquals(
            R.string.sync_error_invalid_conflict_content,
            syncFailureRes(SyncInvalidConflictContentException()),
        )
        assertEquals(R.string.sync_error_no_commit, syncFailureRes(SyncNoCommitException()))
        assertEquals(R.string.sync_error_push_rejected, syncFailureRes(SyncPushRejectedException()))
        assertEquals(R.string.sync_error_merge_failed, syncFailureRes(SyncMergeFailedException()))
    }

    @Test fun transportFailuresMapFromWrappedCauses() {
        val auth = TransportException("Auth fail for methods 'publickey'")
        assertEquals(R.string.sync_error_transport_failed, syncFailureRes(auth))
        assertEquals(R.string.sync_error_transport_failed, syncFailureRes(RuntimeException(auth)))
        assertEquals(
            R.string.sync_error_host_unreachable,
            syncFailureRes(java.net.UnknownHostException("example.invalid")),
        )
    }

    @Test fun failureFallsBackWhenMessageMissing() {
        assertEquals(R.string.sync_error_unknown, syncFailureRes(RuntimeException()))
        assertEquals(R.string.sync_error_unknown, syncFailureRes(RuntimeException("boom")))
    }
}

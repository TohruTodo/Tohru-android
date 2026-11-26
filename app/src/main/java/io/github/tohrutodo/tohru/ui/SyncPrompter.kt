package io.github.tohrutodo.tohru.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Bridges blocking JGit credential callbacks to a Compose dialog. The engine
// waits on the deferred while the settings screen owns the visible dialog.
class SyncPrompter {
    data class HostKeyRequest(val message: String, val answer: CompletableDeferred<Boolean>)

    private val _hostKey = MutableStateFlow<HostKeyRequest?>(null)
    val hostKey: StateFlow<HostKeyRequest?> = _hostKey.asStateFlow()

    suspend fun confirmHostKey(message: String): Boolean {
        val request = HostKeyRequest(message, CompletableDeferred())
        _hostKey.value = request
        return try {
            request.answer.await()
        } finally {
            _hostKey.compareAndSet(request, null)
        }
    }

    fun answerHostKey(accepted: Boolean) {
        _hostKey.value?.answer?.complete(accepted)
    }
}

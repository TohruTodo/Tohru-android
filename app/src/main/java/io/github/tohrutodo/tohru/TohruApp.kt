package io.github.tohrutodo.tohru

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.tohrutodo.tohru.debug.Diagnostics
import io.github.tohrutodo.tohru.security.CredentialVault
import io.github.tohrutodo.tohru.security.KeystoreVault
import io.github.tohrutodo.tohru.store.TaskStore
import io.github.tohrutodo.tohru.sync.GitSync
import io.github.tohrutodo.tohru.sync.JGitSync
import io.github.tohrutodo.tohru.sync.SyncSettings
import io.github.tohrutodo.tohru.task.TaskParser
import io.github.tohrutodo.tohru.ui.SyncPrompter
import java.io.File

// App-scoped task graph shared by the activity and the full-screen overlay.
class TohruApp : Application() {
    lateinit var prefs: SharedPreferences
        private set
    lateinit var vault: CredentialVault
        private set
    lateinit var prompter: SyncPrompter
        private set
    lateinit var store: TaskStore
        private set
    lateinit var git: GitSync
        private set

    private val _collapsed = MutableStateFlow<Set<String>>(emptySet())
    val collapsed: StateFlow<Set<String>> = _collapsed.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        vault = KeystoreVault(this)
        prompter = SyncPrompter()
        _collapsed.value = prefs.getStringSet("collapsed", emptySet())?.toSet().orEmpty()
        reloadGraph()
    }

    // Used after the repository path changes. The activity is recreated, and
    // the overlay should be closed before switching paths.
    fun reloadGraph() {
        TaskParser.configureInbox(SyncSettings.readInboxPrefixes(prefs))
        val repo = File(SyncSettings.readRepoPath(prefs, filesDir))
        val sshDir = SyncSettings.sshDir(filesDir)
        git = JGitSync(
            repoDir = repo,
            configProvider = { SyncSettings.read(prefs, vault, sshDir) },
            onError = { Log.e("TohruSync", "git operation failed", it) },
            confirmHostKey = prompter::confirmHostKey,
            maintenanceProvider = { SyncSettings.readMaintenance(prefs) },
        )
        store = TaskStore(
            tasksDir = File(repo, "tasks"),
            onChanged = { git.autoCommit().getOrThrow() },
            cacheFile = File(cacheDir, "task-index.bin"),
        )
    }

    fun toggleCollapsed(key: String) {
        val updated = if (key in _collapsed.value) _collapsed.value - key else _collapsed.value + key
        _collapsed.value = updated
        prefs.edit { putStringSet("collapsed", updated) }
    }
}

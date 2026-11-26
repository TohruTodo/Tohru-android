package io.github.tohrutodo.tohru.sync

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.tohrutodo.tohru.io.AtomicFiles
import io.github.tohrutodo.tohru.security.CredentialVault
import io.github.tohrutodo.tohru.task.TaskSettings
import java.io.File

// Bridge between the sync form, SharedPreferences and the Keystore vault.
// Secrets never leave the vault; the SSH key lives in app-private storage.
internal object SyncSettings {
    const val KEY_REMOTE = "remote"
    const val KEY_USER = "git-user"
    const val KEY_BRANCH = "git-branch"
    const val KEY_INBOX_PREFIXES = "task-inbox-prefixes"
    const val KEY_NOTE_PREFIXES = "task-note-prefixes"
    const val KEY_LAST_SYNC = "git-last-sync"
    const val KEY_REPO_PATH = "repo-path"
    const val KEY_KEEP_OVERLAY = "keep-overlay"
    const val KEY_AUTHOR_NAME = "git-author-name"
    const val KEY_AUTHOR_EMAIL = "git-author-email"
    const val KEY_RECOVERY_KEEP = "git-recovery-keep"
    const val KEY_GC_ENABLED = "git-gc-enabled"
    const val KEY_GC_DAYS = "git-gc-days"
    const val KEY_EXPORT_KEEP = "git-export-keep"
    const val DEFAULT_AUTHOR_NAME = "Tohru"
    const val DEFAULT_AUTHOR_EMAIL = "tohru@localhost"

    private const val TOKEN_KEY = "git-token"
    private const val PASSPHRASE_KEY = "ssh-passphrase"
    private const val SSH_DIR = "ssh"
    private const val SSH_KEY = "id_tohru"
    private const val KNOWN_HOSTS = "known_hosts"

    fun sshDir(filesDir: File): File = File(filesDir, SSH_DIR)

    fun sshKeyFile(sshDir: File): File = File(sshDir, SSH_KEY)

    fun knownHostsFile(sshDir: File): File = File(sshDir, KNOWN_HOSTS)

    fun hasSshKey(sshDir: File): Boolean = sshKeyFile(sshDir).isFile

    fun readInboxPrefixes(prefs: SharedPreferences): Set<String> =
        prefs.getString(KEY_INBOX_PREFIXES, null)
            ?.let(TaskSettings::parseList)
            ?: setOf(TaskSettings.DEFAULT_INBOX)

    fun readNotePrefixes(prefs: SharedPreferences): Set<String> =
        prefs.getString(KEY_NOTE_PREFIXES, null)
            ?.let(TaskSettings::parseList)
            ?: setOf(TaskSettings.DEFAULT_NOTE)

    fun saveTaskPrefixes(
        prefs: SharedPreferences,
        inbox: Set<String>,
        note: Set<String>,
    ) {
        prefs.edit {
            putString(KEY_INBOX_PREFIXES, TaskSettings.encode(inbox))
            putString(KEY_NOTE_PREFIXES, TaskSettings.encode(note))
        }
    }

    fun readRepoPath(prefs: SharedPreferences, filesDir: File): String =
        prefs.getString(KEY_REPO_PATH, null)?.takeIf { it.isNotBlank() }
            ?: File(filesDir, "repo").absolutePath

    fun saveRepoPath(prefs: SharedPreferences, path: String) {
        prefs.edit { putString(KEY_REPO_PATH, path.trim()) }
    }

    fun readKeepOverlay(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_KEEP_OVERLAY, false)

    fun saveKeepOverlay(prefs: SharedPreferences, keep: Boolean) {
        prefs.edit { putBoolean(KEY_KEEP_OVERLAY, keep) }
    }

    fun readAuthorName(prefs: SharedPreferences): String =
        prefs.getString(KEY_AUTHOR_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_AUTHOR_NAME

    fun readAuthorEmail(prefs: SharedPreferences): String =
        prefs.getString(KEY_AUTHOR_EMAIL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_AUTHOR_EMAIL

    fun readMaintenance(prefs: SharedPreferences): GitMaintenance = GitMaintenance(
        recoveryKeep = prefs.getInt(KEY_RECOVERY_KEEP, 20).coerceAtLeast(1),
        gcEnabled = prefs.getBoolean(KEY_GC_ENABLED, true),
        gcIntervalDays = prefs.getInt(KEY_GC_DAYS, 7).coerceAtLeast(1),
        exportKeep = prefs.getInt(KEY_EXPORT_KEEP, 2).coerceAtLeast(1),
    )

    fun saveMaintenance(prefs: SharedPreferences, config: GitMaintenance) {
        prefs.edit {
            putInt(KEY_RECOVERY_KEEP, config.recoveryKeep.coerceAtLeast(1))
            putBoolean(KEY_GC_ENABLED, config.gcEnabled)
            putInt(KEY_GC_DAYS, config.gcIntervalDays.coerceAtLeast(1))
            putInt(KEY_EXPORT_KEEP, config.exportKeep.coerceAtLeast(1))
        }
    }

    fun readLastSync(prefs: SharedPreferences): Long = prefs.getLong(KEY_LAST_SYNC, 0L)

    fun saveLastSync(prefs: SharedPreferences, at: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(KEY_LAST_SYNC, at) }
    }

    fun read(prefs: SharedPreferences, vault: CredentialVault, sshDir: File): RemoteConfig? {
        val url = prefs.getString(KEY_REMOTE, "").orEmpty().trim()
        if (url.isEmpty()) return null
        return RemoteConfig(
            url = url,
            username = prefs.getString(KEY_USER, "").orEmpty(),
            token = vault.get(TOKEN_KEY),
            branch = prefs.getString(KEY_BRANCH, "").orEmpty(),
            sshKeyFile = sshKeyFile(sshDir).takeIf { it.isFile },
            sshPassphrase = vault.get(PASSPHRASE_KEY),
            knownHostsFile = knownHostsFile(sshDir),
            authorName = readAuthorName(prefs),
            authorEmail = readAuthorEmail(prefs),
        )
    }

    fun save(
        prefs: SharedPreferences,
        vault: CredentialVault,
        sshDir: File,
        remote: String,
        username: String,
        token: String,
        sshKey: String,
        passphrase: String,
        authorName: String = readAuthorName(prefs),
        authorEmail: String = readAuthorEmail(prefs),
    ) {
        prefs.edit {
            putString(KEY_REMOTE, remote.trim())
            putString(KEY_USER, username.trim())
            putString(KEY_AUTHOR_NAME, authorName.trim().ifBlank { DEFAULT_AUTHOR_NAME })
            putString(KEY_AUTHOR_EMAIL, authorEmail.trim().ifBlank { DEFAULT_AUTHOR_EMAIL })
        }
        if (token.isBlank()) vault.remove(TOKEN_KEY) else vault.put(TOKEN_KEY, token)
        if (passphrase.isBlank()) vault.remove(PASSPHRASE_KEY) else vault.put(PASSPHRASE_KEY, passphrase)
        if (sshKey.isNotBlank()) writeKey(sshDir, sshKey)
        ensureKnownHosts(sshDir)
    }

    private fun writeKey(sshDir: File, content: String) {
        sshDir.mkdirs()
        val key = sshKeyFile(sshDir)
        AtomicFiles.writeText(key, content.trimEnd() + "\n")
        key.setReadable(false, false)
        key.setReadable(true, true)
        key.setWritable(false, false)
        key.setWritable(true, true)
    }

    private fun ensureKnownHosts(sshDir: File) {
        sshDir.mkdirs()
        val knownHosts = knownHostsFile(sshDir)
        if (!knownHosts.exists()) knownHosts.createNewFile()
    }
}

package io.github.tohrutodo.tohru.sync

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.JschConfigSessionFactory
import org.eclipse.jgit.transport.OpenSshConfig
import org.eclipse.jgit.util.FS
import java.io.File

// Reads the pasted key from the app-private file and keeps known hosts there.
internal class TohruSshFactory(
    private val keyFile: File?,
    private val passphrase: String?,
    private val knownHosts: File?,
) : JschConfigSessionFactory() {
    override fun configure(hostConfig: OpenSshConfig.Host, session: Session) {
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig("PreferredAuthentications", "publickey")
    }

    override fun createDefaultJSch(fs: FS): JSch {
        val jsch = JSch()
        knownHosts?.takeIf { it.isFile }?.let { jsch.setKnownHosts(it.absolutePath) }
        keyFile?.takeIf { it.isFile }?.let { file ->
            if (passphrase.isNullOrEmpty()) {
                jsch.addIdentity(file.absolutePath)
            } else {
                jsch.addIdentity(file.absolutePath, passphrase.toByteArray(Charsets.UTF_8))
            }
        }
        return jsch
    }
}

// Host keys and key passphrases come from the UI; both arrive through the
// synchronous JGit credentials callbacks.
internal class SshCredentialsProvider(
    private val confirmHostKey: suspend (String) -> Boolean,
    private val passphrase: String?,
) : CredentialsProvider() {
    override fun isInteractive(): Boolean = true

    override fun supports(vararg items: CredentialItem): Boolean =
        items.all { item ->
            item is CredentialItem.YesNoType ||
                item is CredentialItem.StringType ||
                item is CredentialItem.InformationalMessage
        }

    override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
        for (item in items) {
            when (item) {
                is CredentialItem.YesNoType -> item.value = runBlocking { confirmHostKey(item.promptText) }
                is CredentialItem.StringType -> item.value = passphrase.orEmpty()
                is CredentialItem.InformationalMessage -> Unit
                else -> return false
            }
        }
        return true
    }
}

package io.github.tohrutodo.tohru.sync

import org.eclipse.jgit.transport.URIish
import java.util.Locale

// JGit parses scp-style remotes like git@host:path with no scheme, but they
// still travel over SSH.
internal object RemoteUrls {
    fun isSsh(url: String): Boolean {
        val uri = runCatching { URIish(url) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            null -> uri.host != null
            "ssh", "git+ssh", "ssh+git" -> true
            else -> false
        }
    }
}

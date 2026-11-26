package io.github.tohrutodo.tohru

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tohrutodo.tohru.sync.RemoteUrls

class RemoteUrlsTest {
    @Test fun scpStyleIsSsh() {
        assertTrue(RemoteUrls.isSsh("git@github.com:user/repo.git"))
    }

    @Test fun sshSchemesAreSsh() {
        assertTrue(RemoteUrls.isSsh("ssh://git@host/user/repo.git"))
        assertTrue(RemoteUrls.isSsh("git+ssh://host/repo.git"))
    }

    @Test fun httpsIsNotSsh() {
        assertFalse(RemoteUrls.isSsh("https://host/user/repo.git"))
        assertFalse(RemoteUrls.isSsh("http://host/user/repo.git"))
    }

    @Test fun localPathIsNotSsh() {
        assertFalse(RemoteUrls.isSsh("/tmp/repo"))
    }
}

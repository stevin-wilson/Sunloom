package com.sunloom.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The github flavor may contact GitHub for the update check, and only GitHub, over HTTPS. */
class UpdateHostsTest {
    @Test
    fun allowsOnlyTheTwoGithubHosts() {
        assertEquals(setOf("api.github.com", "github.com"), UpdateHosts.ALLOWED)
    }

    @Test
    fun acceptsHttpsToAllowedHostsOnly() {
        assertTrue(UpdateHosts.isAllowed("https://api.github.com/repos/stevin-wilson/Sunloom/releases/latest"))
        assertTrue(UpdateHosts.isAllowed("https://github.com/stevin-wilson/Sunloom/releases/tag/v2.0.0"))
        assertFalse(UpdateHosts.isAllowed("http://api.github.com/repos/x/y"))
        assertFalse(UpdateHosts.isAllowed("https://api.github.com.evil.example/x"))
        assertFalse(UpdateHosts.isAllowed("https://commons.wikimedia.org/x"))
        assertFalse(UpdateHosts.isAllowed("not a url"))
    }
}

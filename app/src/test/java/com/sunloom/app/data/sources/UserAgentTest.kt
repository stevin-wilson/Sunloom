package com.sunloom.app.data.sources

import com.sunloom.app.BuildConfig
import com.sunloom.app.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Article VI §7: sources are told who is asking, with a way to reach the project. The project's public contact is its
 * GitHub page, so no mailbox has to be watched or published.
 */
@Requirement("VI.7")
class UserAgentTest {
    @Test
    fun `the user agent names the app and version and points at the project page`() {
        assertEquals(
            "Sunloom/${BuildConfig.VERSION_NAME} (+https://github.com/stevin-wilson/Sunloom)",
            DefaultHttpsClient.USER_AGENT
        )
    }

    @Test
    fun `no email address is sent to anyone`() {
        assertFalse(DefaultHttpsClient.USER_AGENT.contains("@") || DefaultHttpsClient.USER_AGENT.contains("mailto"))
    }
}

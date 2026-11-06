package com.sunloom.app.ui

import androidx.compose.runtime.saveable.SaverScope
import com.sunloom.app.domain.model.LockWorkspacePolicy
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.ScheduleMode
import com.sunloom.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SavedUiStateTest {

    private val allowAll = SaverScope { true }

    @Test
    fun `setup draft round trips every field the setup screen edits`() {
        val base = UserPreferences()
        val draft = base.copy(
            autoChangeEnabled = false,
            scheduleMode = ScheduleMode.FOLLOW_OS_THEME,
            selectedCityName = "Tokyo, Japan",
            latitude = 35.6762,
            longitude = 139.6503,
            enabledCategories = setOf(SafeCategory.SPACE_ASTRONOMY),
            lockWorkspacePolicy = LockWorkspacePolicy.WORKSPACE_ONLY
        )
        val saver = setupDraftSaver(base)
        val saved = with(saver) { allowAll.save(draft) }
        assertNotNull(saved)
        assertEquals(draft, saver.restore(saved!!))
    }

    @Test
    fun `restoring keeps non edited fields from the current base preferences`() {
        val staleBase = UserPreferences(maxCacheBytes = 111L)
        val saver = setupDraftSaver(staleBase)
        val saved = with(saver) { allowAll.save(UserPreferences(maxCacheBytes = 999L)) }!!
        assertEquals(111L, saver.restore(saved)!!.maxCacheBytes)
    }

    @Test
    fun `an empty saved category set falls back to the base categories`() {
        val base = UserPreferences()
        val saver = setupDraftSaver(base)
        val saved = with(saver) { allowAll.save(base.copy(enabledCategories = emptySet())) }!!
        assertEquals(base.enabledCategories, saver.restore(saved)!!.enabledCategories)
    }

    @Test
    fun `corrupt saved state restores to null instead of crashing`() {
        val saver = setupDraftSaver(UserPreferences())
        assertNull(saver.restore(listOf(true, "NOT_A_MODE", "x", 0.0, 0.0, "", "SAME_ON_BOTH")))
        assertNull(saver.restore(listOf("wrong shape")))
    }
}

package com.sunloom.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollbarThumbTest {

    @Test
    fun `no thumb when content fits the viewport`() {
        assertNull(scrollbarThumb(viewport = 800f, content = 800f, offset = 0f, minThumb = 36f))
        assertNull(scrollbarThumb(viewport = 800f, content = 500f, offset = 0f, minThumb = 36f))
    }

    @Test
    fun `no thumb for unmeasured or invalid input`() {
        assertNull(scrollbarThumb(viewport = 0f, content = 1000f, offset = 0f, minThumb = 36f))
        assertNull(scrollbarThumb(viewport = 800f, content = Float.POSITIVE_INFINITY, offset = 0f, minThumb = 36f))
        assertNull(scrollbarThumb(viewport = 800f, content = 1600f, offset = Float.NaN, minThumb = 36f))
    }

    @Test
    fun `thumb height is proportional to the visible fraction`() {
        val thumb = scrollbarThumb(viewport = 800f, content = 1600f, offset = 0f, minThumb = 36f)
        assertNotNull(thumb)
        assertEquals(400f, thumb!!.height, 0.001f)
        assertEquals(0f, thumb.top, 0.001f)
    }

    @Test
    fun `thumb sits at the bottom when scrolled to the end`() {
        val thumb = scrollbarThumb(viewport = 800f, content = 1600f, offset = 800f, minThumb = 36f)!!
        assertEquals(800f, thumb.top + thumb.height, 0.001f)
    }

    @Test
    fun `thumb is centred at half scroll`() {
        val thumb = scrollbarThumb(viewport = 800f, content = 1600f, offset = 400f, minThumb = 36f)!!
        assertEquals(200f, thumb.top, 0.001f)
    }

    @Test
    fun `thumb never shrinks below the minimum and stays inside the viewport`() {
        val thumb = scrollbarThumb(viewport = 800f, content = 800_000f, offset = 799_200f, minThumb = 36f)!!
        assertEquals(36f, thumb.height, 0.001f)
        assertEquals(800f, thumb.top + thumb.height, 0.001f)
    }

    @Test
    fun `offset beyond range is clamped`() {
        val past = scrollbarThumb(viewport = 800f, content = 1600f, offset = 5_000f, minThumb = 36f)!!
        assertEquals(800f, past.top + past.height, 0.001f)
        val before = scrollbarThumb(viewport = 800f, content = 1600f, offset = -50f, minThumb = 36f)!!
        assertEquals(0f, before.top, 0.001f)
    }

    @Test
    fun `minimum thumb larger than viewport is capped to the viewport`() {
        val thumb = scrollbarThumb(viewport = 20f, content = 100f, offset = 0f, minThumb = 36f)!!
        assertEquals(20f, thumb.height, 0.001f)
    }
}

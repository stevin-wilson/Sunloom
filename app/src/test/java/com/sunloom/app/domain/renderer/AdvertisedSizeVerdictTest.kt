package com.sunloom.app.domain.renderer

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Article X §4: synthetic candidates (the shapes and sizes museums and photo archives actually offer) run through the
 * pre-download decision for common displays, so a change in what each display accepts or turns away is visible.
 */
@Requirement("X.4")
class AdvertisedSizeVerdictTest {

    private fun display(w: Int, h: Int, dpi: Int) = DisplayProfile(0, "${w}x$h", w, h, densityDpi = dpi)

    private val crops = "COMPOSITION_CROPS_TOO_MUCH"
    private val small = "INSUFFICIENT_ADVERTISED_RESOLUTION"

    private val shapes = linkedMapOf(
        "16:9 photo 4000x2250" to (4000 to 2250),
        "3:2 photo 6000x4000" to (6000 to 4000),
        "panorama 8000x2500" to (8000 to 2500),
        "portrait painting 3000x4000" to (3000 to 4000),
        "square 3000x3000" to (3000 to 3000),
        "museum web 1600x1000" to (1600 to 1000),
        "large print 3500x2500" to (3500 to 2500)
    )

    private fun verdicts(display: DisplayProfile, allowBlur: Boolean = false) =
        shapes.mapValues { (_, size) ->
            CropRenderer.advertisedSizeRejection(size.first, size.second, display, allowBlur)
        }

    @Test
    fun `an ultrawide 3440x1440 display takes panoramas and large photos but not portraits, squares or small renditions`() {
        assertEquals(
            mapOf(
                "16:9 photo 4000x2250" to null,
                "3:2 photo 6000x4000" to null,
                "panorama 8000x2500" to null,
                "portrait painting 3000x4000" to crops,
                "square 3000x3000" to crops,
                "museum web 1600x1000" to small,
                "large print 3500x2500" to null
            ),
            verdicts(display(3440, 1440, 149))
        )
    }

    @Test
    fun `a 1080p display needs only 1920 px and, being 16 to 9, keeps enough of a square but not of a portrait`() {
        assertEquals(
            mapOf(
                "16:9 photo 4000x2250" to null,
                "3:2 photo 6000x4000" to null,
                "panorama 8000x2500" to null,
                "portrait painting 3000x4000" to crops,
                "square 3000x3000" to null,
                "museum web 1600x1000" to small,
                "large print 3500x2500" to null
            ),
            verdicts(display(1920, 1080, 160))
        )
    }

    @Test
    fun `a small 1366x768 laptop takes the 1600 px museum rendition`() {
        val v = verdicts(display(1366, 768, 160))
        assertEquals(null, v["museum web 1600x1000"])
        assertEquals(crops, v["portrait painting 3000x4000"])
    }

    @Test
    fun `when both shape and size fail the shape reason is reported`() {
        // A 1000x1500 portrait is both too narrow and the wrong shape: the shape reason wins.
        assertEquals(crops, CropRenderer.advertisedSizeRejection(1000, 1500, display(3440, 1440, 149), false))
    }

    @Test
    fun `blurred frame fill accepts the shapes a crop would waste but still needs enough pixels`() {
        val display = display(3440, 1440, 149)
        val v = verdicts(display, allowBlur = true)
        assertEquals(null, v["portrait painting 3000x4000"])
        assertEquals(null, v["square 3000x3000"])
        // 1600 px fits the 1440 px height with blurred sides; below 1600 px on the long edge it does not.
        assertEquals(null, v["museum web 1600x1000"])
        assertEquals(small, CropRenderer.advertisedSizeRejection(1200, 800, display, true))
    }

    @Test
    fun `an unknown size is never rejected here`() {
        assertEquals(null, CropRenderer.advertisedSizeRejection(0, 0, display(3440, 1440, 149), false))
        assertEquals(null, CropRenderer.advertisedSizeRejection(3000, 0, display(3440, 1440, 149), false))
    }
}

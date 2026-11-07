package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.DisplayProfile

/** Common desktop and laptop panels, with the density Android typically reports for each. */
internal object CoverageDisplays {
    private fun display(name: String, w: Int, h: Int, dpi: Int) = DisplayProfile(0, name, w, h, densityDpi = dpi)

    val all: List<DisplayProfile> = listOf(
        display("1366x768", 1366, 768, 160),
        display("1920x1080", 1920, 1080, 160),
        display("1920x1200", 1920, 1200, 160),
        display("2560x1440", 2560, 1440, 160),
        display("2560x1600 hidpi", 2560, 1600, 220),
        display("3440x1440", 3440, 1440, 149),
        display("3840x2160 hidpi", 3840, 2160, 240),
        display("5120x1440", 5120, 1440, 160)
    )
}

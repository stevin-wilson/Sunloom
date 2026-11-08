package com.sunloom.app.domain.safety

import java.util.Locale

/**
 * Which NASA Image Library records may become wallpapers (Constitution v0.26, Schedule A).
 *
 * NASA's metadata does not describe the picture: a hurricane-track map with city names and a legend is
 * titled and described like a satellite photograph. So admission needs positive evidence about the record's
 * family, read from the `nasa_id` and keywords, and every admitted family is still screened for the kinds of
 * product that look like scenery in text but are not (annotated maps, radar and topography products, staff and
 * facility photographs, social-media handheld night frames).
 *
 * Pure and shared: the adapter applies it before download, and the safety gate re-applies it to stored records
 * (the id is kept in `provenance.assetId` as `nasa-<id>`).
 */
object NasaRecordPolicy {
    private enum class Family { HUBBLE_WEBB, ISS, JSC_ARCHIVE, JPL_PHOTOJOURNAL, NOT_ADMITTED }

    private val NON_WORD_REGEX = Regex("[^a-z0-9]+")
    private val HTML_TAG_REGEX = Regex("<[^>]*>")
    private val ISS_ID_REGEX = Regex("""^iss\d""")
    private val JSC_ARCHIVE_ID_REGEX = Regex("""^(sts\d|s\d\d-|as\d|art\d)""")

    /** Imaging missions whose Photojournal records are mostly pictures of a place or object. */
    private val JPL_IMAGING_MISSIONS = listOf(
        "hubble", "webb", "chandra", "spitzer", "cassini", "galileo", "voyager", "juno", "mariner", "messenger", "new horizons",
        "mars reconnaissance", "mars global surveyor", "mars exploration rover", "mars science laboratory",
        "galaxy evolution explorer", "wide field infrared", "terra", "earth observing"
    )

    /** Product types, annotations and people: matched in the title, description and keywords of every family. */
    private val ANYWHERE_REGEX = Regex(
        "overlay|\\btracks?\\b|\\bradar\\b|synthetic aperture|shaded relief|\\belevation\\b|perspective view|\\bsrtm\\b|" +
            "\\b(labeled|labelled|annotated)\\b|\\blegend\\b|" +
            "technicians?|\\bworkers?\\b|clean ?room|processing facility|" +
            "team members|tweeted photo|social media|" +
            "\\binsets?\\b|\\b(left|right|top|bottom|upper|lower|middle|center|each|two|three|four) panels?\\b|" +
            "side[- ]by[- ]side|time series|\\bsequence\\b"
    )

    /** Multi-panel products: matched in the title and keywords only, since Hubble prose says "color composite". */
    private val TITLE_AND_KEYWORDS_REGEX = Regex("\\b(composite|mosaic|engineers?|inspect(s|ed|ion)?)\\b")

    /** Dark handheld frames from the station: matched for ISS photographs only. */
    private val ISS_NIGHT_REGEX = Regex("night (pass|view|earth)|city lights|night earth")

    /**
     * Shape and size of the picture, from the dimensions NASA lists for its renditions. Thin strips (mission
     * panoramas, orbiter swaths) and thumbnail-sized originals make poor wallpapers. For a rendition smaller than
     * the original only the shape is judged. Unknown dimensions (0) are left to the download-time checks.
     */
    fun shapeRejection(width: Int, height: Int, isOriginal: Boolean): String? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toDouble() / height
        if (ratio < MIN_ASPECT_RATIO || ratio > MAX_ASPECT_RATIO) return "extreme shape ${width}x$height"
        if (isOriginal && minOf(width, height) < MIN_SHORT_EDGE_PX) return "too small ${width}x$height"
        return null
    }

    private const val MIN_SHORT_EDGE_PX = 1000
    private const val MIN_ASPECT_RATIO = 0.5
    private const val MAX_ASPECT_RATIO = 2.2

    /** Returns why the record is not admitted, or null when it may be considered further. */
    fun rejection(nasaId: String, title: String, description: String, keywords: List<String>): String? {
        val id = nasaId.trim().lowercase(Locale.ROOT)
        val family = familyOf(id, keywords)
        if (family == Family.NOT_ADMITTED) return "record family not admitted"

        val cleanTitle = HTML_TAG_REGEX.replace(title, " ").lowercase(Locale.ROOT)
        val keywordText = keywords.joinToString(" ").lowercase(Locale.ROOT)
        val all = "$cleanTitle ${description.lowercase(Locale.ROOT)} $keywordText"

        ANYWHERE_REGEX.find(all)?.let { return "annotated product or staff photograph ('${it.value}')" }
        TITLE_AND_KEYWORDS_REGEX.find("$cleanTitle $keywordText")?.let { return "multi-panel product ('${it.value}')" }
        if (family == Family.ISS) {
            ISS_NIGHT_REGEX.find(all)?.let { return "handheld night frame ('${it.value}')" }
        }
        return null
    }

    private fun familyOf(id: String, keywords: List<String>): Family = when {
        id.startsWith("hubble-") || id.startsWith("webb-") -> Family.HUBBLE_WEBB
        ISS_ID_REGEX.containsMatchIn(id) -> Family.ISS
        JSC_ARCHIVE_ID_REGEX.containsMatchIn(id) -> Family.JSC_ARCHIVE
        id.startsWith("pia") && namesImagingMission(keywords) -> Family.JPL_PHOTOJOURNAL
        else -> Family.NOT_ADMITTED
    }

    private fun namesImagingMission(keywords: List<String>): Boolean {
        val text = " " + keywords.joinToString(" ").lowercase(Locale.ROOT).replace(NON_WORD_REGEX, " ") + " "
        return JPL_IMAGING_MISSIONS.any { " $it " in text }
    }
}

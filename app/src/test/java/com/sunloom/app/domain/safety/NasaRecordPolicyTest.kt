package com.sunloom.app.domain.safety

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Records are the real NASA metadata of items reported or reviewed as thumbnails (2026-11-08). */
class NasaRecordPolicyTest {

    private fun verdict(id: String, title: String, description: String, keywords: List<String>) =
        NasaRecordPolicy.rejection(id, title, description, keywords)

    @Test
    fun `annotated maps, radar products, staff photos and composites are rejected`() {
        assertNotNull(
            verdict(
                "PIA06674", "Hurricane Rita Track<br />Radar Image with Topographic Overlay",
                "The Gulf Coast is shown in this satellite image from NASA Terra spacecraft, and the predicted storm track.",
                listOf("Earth", "Shuttle Radar Topography Mission SRTM")
            )
        )
        assertNotNull(
            verdict(
                "PIA18137", "NASA Seasat Satellite Shows Massachusetts Coast",
                "This Seasat synthetic aperture radar image from Aug. 27, 1978, shows the Massachusetts coast.",
                listOf("Earth", "Seasat")
            )
        )
        assertNotNull(
            verdict(
                "PIA04264", "New Galaxy Quest Readies for Launch",
                "In the Multi-Payload Processing Facility, workers check the deployment of the cover of the telescope.",
                listOf("Galaxy Evolution Explorer GALEX")
            )
        )
        assertNotNull(
            verdict(
                "GSFC_20171208_Archive_e002107", "Aurora Composite Image",
                "This composite image presents the three most visible elements of space weather.",
                listOf("Aurora Composite Image", "sun", "nasa")
            )
        )
    }

    @Test
    fun `handheld night shots posted to social media are rejected`() {
        assertNotNull(
            verdict(
                "iss040e008327", "Earth Observation",
                "Earth Observation taken during a night pass by the Expedition 40 crew aboard the International Space Station.",
                listOf("EARTH OBSERVATIONS (FROM SPACE) EXPEDITION 40", "TWEETED PHOTO", "SOCIAL MEDIA")
            )
        )
    }

    @Test
    fun `weather product archives and unknown record families are not admitted`() {
        assertNotNull(
            verdict("GSFC_20171208_Archive_e001442", "Satellite Shows West Coast June Gloom", "NOAA's GOES-15 satellite.", listOf("weather"))
        )
        assertNotNull(verdict("KSC-2009-1124", "KSC-2009-1124", "", listOf("galaxy")))
        assertNotNull(verdict("PIA12345", "A planet", "A plain picture.", listOf("Earth"))) // PIA without an imaging mission
    }

    @Test
    fun `real photographs from admitted families pass`() {
        assertNull(
            verdict("PIA00738", "Topography and Volcanoes on Io color", "Topography and Volcanoes on Io color", listOf("Io", "Galileo"))
        )
        assertNull(
            verdict(
                "PIA12074", "Baby Stars Finally Found in Jumbled Galactic Center",
                "This infrared image from NASA Spitzer Space Telescope shows three baby stars.", listOf("Milky Way", "Spitzer Space Telescope")
            )
        )
        assertNull(
            verdict(
                "hubble-peers-into-the-most-crowded-place-in-the-milky-way_18051161100_o", "The most crowded place in the Milky Way",
                "This new NASA/ESA Hubble Space Telescope image presents the Arches Cluster.", listOf("Arches Cluster")
            )
        )
        assertNull(
            verdict(
                "iss073e0293986", "An aurora streams across Earth's horizon",
                "An astronaut aboard the International Space Station took this photograph of an aurora.", listOf("Aurora", "Earth")
            )
        )
        assertNull(
            verdict("sts059-220-041", "Gobi Desert, China as seen from STS-59", "View across the southern Gobi Desert.", listOf("CHINA", "DESERTS"))
        )
    }

    @Test
    fun `thin strips, small images and multi-panel science products are rejected by shape and wording`() {
        // Mars orbiter strip 1024x4417, Milky Way strip 27000x6000, a 540x810 and a 819x900 thumbnail-sized record.
        assertNotNull(NasaRecordPolicy.shapeRejection(1024, 4417, isOriginal = true))
        assertNotNull(NasaRecordPolicy.shapeRejection(27000, 6000, isOriginal = true))
        assertNotNull(NasaRecordPolicy.shapeRejection(540, 810, isOriginal = true))
        assertNotNull(NasaRecordPolicy.shapeRejection(819, 900, isOriginal = true))
        // A rendition smaller than the original says nothing about size, only about shape.
        assertNull(NasaRecordPolicy.shapeRejection(640, 398, isOriginal = false))
        assertNotNull(NasaRecordPolicy.shapeRejection(640, 142, isOriginal = false))
        // Photographs seen as good: Pluto 1024x1024, shuttle 1920x1253, ISS 4256x2832, Hubble 2069x2489 portrait.
        assertNull(NasaRecordPolicy.shapeRejection(1024, 1024, isOriginal = true))
        assertNull(NasaRecordPolicy.shapeRejection(1920, 1253, isOriginal = true))
        assertNull(NasaRecordPolicy.shapeRejection(4256, 2832, isOriginal = true))
        assertNull(NasaRecordPolicy.shapeRejection(0, 0, isOriginal = true))

        assertNotNull(
            verdict(
                "PIA12074", "Baby Stars Found",
                "The two insets show close-ups of stars forming in the galactic centre.", listOf("Spitzer Space Telescope")
            )
        )
        assertNotNull(
            verdict("PIA01600", "Jupiter Aurora", "A time series of the aurora over four hours.", listOf("Galileo"))
        )
        assertNotNull(
            verdict("PIA00605", "Aurora", "The left panel is the clear filter and the right panel is red.", listOf("Galileo"))
        )
    }

    @Test
    fun `credits and everyday prose do not reject a genuine photograph`() {
        assertNull(
            verdict(
                "PIA23791", "Venus from Mariner 10",
                "The image was processed from archived Mariner 10 data by JPL engineer Kevin M. Gill.", listOf("Mariner 10", "Venus")
            )
        )
        assertNull(
            verdict(
                "sts054-152-189", "Murzuk sand sea, Libya",
                "Close inspection of this photograph shows the agricultural fields. The open panels of the vehicle are visible.",
                listOf("LIBYA", "DESERTS")
            )
        )
        // Still rejected when the word is a label of the picture itself.
        assertNotNull(verdict("PIA21937", "Signal", "See the inverted V in the lower panel.", listOf("Hubble")))
        assertNotNull(verdict("PIA00001", "Technicians inspect the camera", "A view.", listOf("Galileo")))
    }

    @Test
    fun `an imaging mission must be a whole keyword word, not part of another word`() {
        assertNotNull(verdict("PIA12345", "Terrain model", "A view.", listOf("Terrain", "topography")))
        assertNotNull(verdict("PIA12346", "Alien worlds", "A view.", listOf("extraterrestrial")))
        assertNull(verdict("PIA12347", "A view of Earth", "A view.", listOf("Terra", "Earth")))
    }
}

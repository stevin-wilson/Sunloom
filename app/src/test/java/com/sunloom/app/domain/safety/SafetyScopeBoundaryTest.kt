package com.sunloom.app.domain.safety

import com.sunloom.app.Requirement
import com.sunloom.app.domain.model.SafetyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Article VII §4 L2: where each rule looks, and where it stops. The terms and fields here are the ones the Constitution
 * names (clauses d to j), so a rule that quietly widens (rejecting honest prose) or narrows (missing a field) fails here
 * rather than in a person's wallpaper. Mutation testing showed these edges were the part of the gate no test pinned.
 */
class SafetyScopeBoundaryTest {

    private val clean = "A quiet lake at dawn"

    /** Where one text goes: each of the four fields the gate reads, the others left clean. */
    private enum class Field { TITLE, DESCRIPTION, TAG, ATTRIBUTION }

    private fun prohibited(text: String, field: Field): String? = SafetyDenylist.findProhibitedMatch(
        title = if (field == Field.TITLE) text else clean,
        description = if (field == Field.DESCRIPTION) text else "A photograph of a lake.",
        rawCategoriesAndTags = if (field == Field.TAG) listOf("landscape", text) else listOf("landscape"),
        attributionText = if (field == Field.ATTRIBUTION) text else "A. Photographer, CC BY 4.0"
    )

    // --- Tier 1 reaches every field; Tier 2 reaches only the title and the tags (clauses e and f) ---

    @Requirement("VII.4.e")
    @Test
    fun `Tier 1 terms are caught in the title, the description, a tag and the credit`() {
        listOf("odalisque", "bather", "headshot", "mugshot", "press conference", "media briefing", "news conference", "autopsy", "cadaver").forEach { term ->
            Field.entries.forEach { field ->
                assertNotNull("`$term` in the ${field.name.lowercase()} must be caught", prohibited("see the $term here", field))
            }
        }
    }

    @Requirement("VII.4.f")
    @Test
    fun `Tier 2 terms are caught in the title and in a tag, but not in prose or in a credit`() {
        val tier2 = listOf(
            "portrait", "person", "man", "woman", "girl", "boy", "child", "crowd", "face", "bust", "figurative",
            "weapon", "gun", "rifle", "pistol", "sword", "knife", "bomb", "armor", "firearm",
            "logo", "emblem", "insignia", "mission patch", "coat of arms", "skull", "anatomy", "disease"
        )
        tier2.forEach { term ->
            assertNotNull("`$term` in the title must be caught", prohibited("the $term", Field.TITLE))
            assertNotNull("`$term` in a tag must be caught", prohibited(term, Field.TAG))
            assertNull("`$term` in the description is prose and must pass", prohibited("a $term appears nearby", Field.DESCRIPTION))
            assertNull("`$term` in the credit must pass", prohibited("$term studio", Field.ATTRIBUTION))
        }
    }

    @Requirement("VII.4.e", "VII.4.f")
    @Test
    fun `a phrase is never put together from two different fields`() {
        val phrases = (SafetyDenylist.UNCONDITIONAL_TERMS + SafetyDenylist.SUBJECT_AND_TAG_TERMS).filter { ' ' in it }
        assertTrue("there should be phrases to split", phrases.size >= 5)
        phrases.forEach { phrase ->
            val words = phrase.split(' ')
            val first = words.first()
            val rest = words.drop(1).joinToString(" ")
            Field.entries.forEach { a ->
                Field.entries.filter { it != a }.forEach { b ->
                    val found = SafetyDenylist.findProhibitedMatch(
                        title = listOf(a to first, b to rest).filter { it.first == Field.TITLE }.joinToString(" ") { it.second }.ifEmpty { clean },
                        description = listOf(a to first, b to rest).filter { it.first == Field.DESCRIPTION }.joinToString(" ") { it.second }.ifEmpty { "A lake." },
                        rawCategoriesAndTags = listOf("landscape") + listOf(a to first, b to rest).filter { it.first == Field.TAG }.map { it.second },
                        attributionText = listOf(a to first, b to rest).filter { it.first == Field.ATTRIBUTION }.joinToString(" ") { it.second }.ifEmpty { "A. Photographer" }
                    )
                    assertTrue("`$phrase` was assembled from the ${a.name} and the ${b.name} (found `$found`)", found != phrase)
                }
            }
        }
    }

    // --- Normalisation edges (clause d): where plural stemming stops ---

    @Requirement("VII.4.d")
    @Test
    fun `plurals are folded to their singular, and words that only look plural are left alone`() {
        mapOf(
            "lakes" to "lake", "boxes" to "box", "beaches" to "beach", "wishes" to "wish", "glasses" to "glass",
            "cities" to "city", "flies" to "fly", "wolves" to "wolf", "knives" to "knife", "men" to "man", "children" to "child"
        ).forEach { (plural, singular) -> assertEquals(plural, singular, SafetyDenylist.stemToken(plural)) }
        // Too short to be a plural (three letters or fewer), or ending in a letter pair that is not a plural ending.
        listOf("gas", "his", "was", "bus", "glass", "cactus", "iris", "basis", "mass").forEach {
            assertEquals("`$it` is not a plural", it, SafetyDenylist.stemToken(it))
        }
        // Four-letter words ending -ies or -ves are not turned into a one-letter-stem word.
        assertEquals("tie", SafetyDenylist.stemToken("ties"))
        assertEquals("lie", SafetyDenylist.stemToken("lies"))
        assertEquals("eve", SafetyDenylist.stemToken("eves"))
    }

    /** The ordinary English plural of [word]: -ies after a consonant and y, -es after s, x, z, ch and sh, otherwise -s. */
    private fun plural(word: String): String = when {
        word.length > 1 && word.endsWith("y") && word[word.length - 2] !in "aeiou" -> word.dropLast(1) + "ies"
        word.endsWith("s") || word.endsWith("x") || word.endsWith("z") || word.endsWith("ch") || word.endsWith("sh") -> word + "es"
        else -> word + "s"
    }

    @Requirement("VII.4.d", "VII.4.e", "VII.4.f")
    @Test
    fun `every denylist term is caught in its plural too`() {
        // Adjectives have no plural, and a phrase that ends in "of" is not one noun: nothing to catch there.
        val noPlural = setOf("seductive", "provocative", "figurative", "portrait of")
        // The sets also hold the plural forms added for terms the stemmer strands ("nazis" beside "nazi"); those are plurals already.
        fun isAddedPlural(term: String, set: Set<String>) = term.endsWith("is") && term.dropLast(1) in set
        fun pluralOf(term: String): String {
            val words = term.split(' ')
            return (words.dropLast(1) + plural(words.last())).joinToString(" ")
        }
        val missed = mutableListOf<String>()
        SafetyDenylist.UNCONDITIONAL_TERMS.filter { it !in noPlural && !isAddedPlural(it, SafetyDenylist.UNCONDITIONAL_TERMS) }.forEach { term ->
            if (prohibited("the ${pluralOf(term)}", Field.DESCRIPTION) == null) missed += "Tier 1 `$term` as `${pluralOf(term)}`"
        }
        SafetyDenylist.SUBJECT_AND_TAG_TERMS.filter { it !in noPlural && !isAddedPlural(it, SafetyDenylist.SUBJECT_AND_TAG_TERMS) }.forEach { term ->
            if (prohibited("the ${pluralOf(term)}", Field.TITLE) == null) missed += "Tier 2 `$term` as `${pluralOf(term)}`"
        }
        assertTrue("terms that slip past in the plural: $missed", missed.isEmpty())
    }

    @Requirement("VII.4.d", "VII.4.e")
    @Test
    fun `the plurals of common Tier 1 words are caught`() {
        listOf("Nazis rallying", "Girls in bikinis", "Two selfies at the summit", "Paparazzis outside", "Hentais").forEach {
            assertNotNull("`$it`", prohibited(it, Field.TITLE))
        }
    }

    @Requirement("VII.4.d")
    @Test
    fun `case, accents, ligatures and punctuation make no difference to what is matched`() {
        listOf("ODALISQUE", "Odalisque!", "odalisque,", "ödalisque".replace("ö", "o"), "O-d-a-l-i-s-q-u-e".replace("-", ""), "  odalisque  ").forEach {
            assertNotNull("`$it`", prohibited(it, Field.TITLE))
        }
        assertNotNull("plural of a term", prohibited("odalisques", Field.TITLE))
        assertNotNull("accented letters are folded", prohibited("Odaliśque", Field.TITLE))
    }

    // --- Composite layouts (clause g) ---

    private fun composite(title: String = clean, description: String = "A photograph of a lake.", tags: List<String> = listOf("landscape")) =
        SafetyDenylist.findCompositeLayoutMatch(title, description, tags)

    @Requirement("VII.4.g")
    @Test
    fun `montage, photomontage and collage reject from the title or a tag, but not from prose`() {
        listOf("montage", "photomontage", "collage").forEach { word ->
            assertNotNull("`$word` in the title", composite(title = "A $word of lakes"))
            assertNotNull("`$word` in a tag", composite(tags = listOf("landscape", word)))
            assertNull("`$word` mentioned in prose is not a montage", composite(description = "This is not a $word, just one lake."))
        }
    }

    @Requirement("VII.4.g")
    @Test
    fun `a grid category rejects, and the bare words array, composite, triptych and mosaic do not`() {
        assertEquals("grid array category '3x2 arrays'", composite(tags = listOf("3x2 arrays")))
        assertNotNull(composite(tags = listOf("4 x 3 array")))
        listOf("array", "composite", "triptych", "mosaic", "3x2", "arrays of lakes").forEach {
            assertNull("`$it` alone is not a grid", composite(tags = listOf("landscape", it)))
        }
    }

    @Requirement("VII.4.g")
    @Test
    fun `a description that opens with a figure caption rejects, one that merely mentions a figure does not`() {
        assertEquals("figure caption", composite(description = "Figure 2. The lake at dawn."))
        assertNotNull(composite(description = "Fig. 3: the lake"))
        assertNotNull(composite(description = "  Fig 4 lake"))
        assertNull("a figure mentioned later", composite(description = "A lake at dawn, as in Figure 2."))
        assertNull("a word that only starts like it", composite(description = "Figure skating on the lake."))
    }

    @Requirement("VII.4.g")
    @Test
    fun `panel labels A and B reject only when they are close together`() {
        assertEquals("labelled panels (A)/(B)", composite(description = "(A) the lake at dawn and (B) the lake at dusk"))
        assertNotNull("spaces and case do not matter", composite(description = "( a ) left. ( B ) right."))
        val gap = { n: Int -> "(A)" + "x".repeat(n) + "(B)" }
        assertNotNull("400 characters between the labels is still one figure", composite(description = gap(400)))
        assertNull("401 is not", composite(description = gap(401)))
        assertNull("one label alone", composite(description = "(A) a single panel"))
    }

    // --- Not a scene (clause h): the first sentence, and the self-descriptions ---

    @Requirement("VII.4.h")
    @Test
    fun `a data product word rejects from the title, a tag or the first sentence, but not from a later sentence`() {
        listOf("map", "chart", "graph", "diagram", "illustration", "animation", "visualization", "simulation", "rendering").forEach { word ->
            assertNotNull("`$word` in the title", SafetyDenylist.findNonPhotographicMatch("A $word", "A lake at dawn.", listOf("landscape")))
            assertNotNull("`$word` in a tag", SafetyDenylist.findNonPhotographicMatch(clean, "A lake at dawn.", listOf("landscape", word)))
            assertNotNull("`$word` in the first sentence", SafetyDenylist.findNonPhotographicMatch(clean, "A $word of a lake. Taken at dawn.", listOf("landscape")))
            assertNull("`$word` only in the second sentence", SafetyDenylist.findNonPhotographicMatch(clean, "A lake at dawn. See the $word below.", listOf("landscape")))
        }
        assertNotNull("an artist's concept", SafetyDenylist.findNonPhotographicMatch("An artist's concept of a station", "", listOf("space")))
    }

    @Requirement("VII.4.h")
    @Test
    fun `a self-description rejects wherever it appears in the description`() {
        listOf("this illustration", "this map", "computer modeling", "computer generated").forEach { phrase ->
            val found = SafetyDenylist.findNonPhotographicMatch(clean, "A lake at dawn. Later on, $phrase shows the water.", listOf("landscape"))
            assertNotNull("`$phrase` in a later sentence", found)
        }
        assertNull("an ordinary description", SafetyDenylist.findNonPhotographicMatch(clean, "A lake at dawn. The water is calm.", listOf("landscape")))
    }

    @Requirement("VII.4.h")
    @Test
    fun `the not-a-scene check applies to NASA records only`() {
        val gate = SafetyGate()
        val tags = listOf("landscape", "mountains")
        val other = SafetyFixtures.candidate(sourceId = "wikimedia_featured", tags = tags).copy(title = "Map of the mountains")
        val verdict = gate.evaluateMetadata(other)
        val reason = (verdict as? SafetyVerdict.Rejected)?.reasonCode
        assertTrue("a Wikimedia picture titled 'Map' is not refused as a data product: $reason", reason != "NON_PHOTOGRAPHIC_CONTENT")
    }

    // --- Safe visual domain (clause i) ---

    @Requirement("VII.4.i")
    @Test
    fun `a safe domain word must be in the title or a tag, in any form of the word, and a generic word does not count`() {
        assertTrue(SafetyDenylist.matchesSafeDomain("Mountains at dawn", emptyList()))
        assertTrue(SafetyDenylist.matchesSafeDomain("Untitled", listOf("Lakes")))
        assertTrue("any one tag will do", SafetyDenylist.matchesSafeDomain("Untitled", listOf("featured", "photo", "glaciers")))
        assertFalse("`featured` is generic", SafetyDenylist.matchesSafeDomain("Featured picture", listOf("featured")))
        assertFalse("no tags and nothing in the title", SafetyDenylist.matchesSafeDomain("Untitled", emptyList()))
        assertFalse("the empty title", SafetyDenylist.matchesSafeDomain("", listOf("")))
    }

    // --- Benign phrases (clause j) ---

    @Requirement("VII.4.j")
    @Test
    fun `the benign phrases pass and every other use of their words is still caught`() {
        listOf("naked eye", "bikini atoll", "blood moon", "blood orange", "blood red sky").forEach { phrase ->
            assertNull("`$phrase` is benign", prohibited("seen with the $phrase", Field.DESCRIPTION))
            assertNull("`$phrase` is benign in a title", prohibited("The $phrase", Field.TITLE))
        }
        listOf("naked", "bikini", "blood").forEach { word ->
            assertNotNull("`$word` alone is still caught", prohibited("a $word", Field.DESCRIPTION))
        }
        assertNotNull("a benign phrase does not excuse the rest of the text", prohibited("the naked eye and a naked man", Field.DESCRIPTION))
    }

    // --- Informative perceptual hashes (Article VII §4 k, m) ---

    @Requirement("VII.4.m", "X.4.m")
    @Test
    fun `a perceptual hash is informative only with 10 to 54 bits set`() {
        fun hashWith(bits: Int) = ((1UL shl bits) - 1UL).toString(16).padStart(16, '0')
        assertFalse("9 bits", SafetyGate.isInformativeDHash(hashWith(9)))
        assertTrue("10 bits", SafetyGate.isInformativeDHash(hashWith(10)))
        assertTrue("54 bits", SafetyGate.isInformativeDHash(hashWith(54)))
        assertFalse("55 bits", SafetyGate.isInformativeDHash(hashWith(55)))
        assertFalse("not 16 characters", SafetyGate.isInformativeDHash("ffffffffffffff"))
        assertFalse("not hexadecimal", SafetyGate.isInformativeDHash("zzzzzzzzzzzzzzzz"))
    }
}

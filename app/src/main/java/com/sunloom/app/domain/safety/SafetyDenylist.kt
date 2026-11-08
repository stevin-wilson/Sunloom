package com.sunloom.app.domain.safety

import java.text.Normalizer
import java.util.Locale

/**
 * Article VII §§1, 2, 4 & Schedule B (Constitution v0.4):
 * Versioned, Unicode NFKD locale-normalized and stem-aware lexical safety denylist.
 *
 * Separates:
 * - Tier 1 (UNCONDITIONAL_TERMS): matched across ALL text fields (title, description,
 *   categories/tags, and attribution). Covers NSFW, nudity, classical-nude themes, gore,
 *   animals in distress, hate symbols, political campaigning, UI spoofing, document scans,
 *   and explicit portrait/event phrases.
 * - Tier 2 (SUBJECT_AND_TAG_TERMS): matched across title and source-supplied categories,
 *   tags, keywords, department, and classification fields (after scrubbing known safe
 *   geographic/astronomical idioms like "North Face", "Death Valley", "Dead Sea",
 *   "Old Man of Storr", or "supernova explosion"). Prevents false rejections when NASA
 *   descriptions say "An astronaut aboard the ISS took this photograph..." while strictly
 *   blocking portraits, people, weapons, and logos/insignia as subjects.
 * - L2c (Constitution v0.13, [findCompositeLayoutMatch]): single-scene guard that rejects
 *   montages, collages, N x M array categories and labelled multi-panel figure plates.
 */
object SafetyDenylist {

    const val DENYLIST_VERSION = "2026.11.08-v6"

    private val DIACRITICS_REGEX = Regex("\\p{M}+")
    private val NON_LETTER_DIGIT_REGEX = Regex("[^a-z0-9]+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val LIGATURE_AND_NORDIC_FOLDS = mapOf(
        '\u00E6' to "ae", // Latin small ligature ae (æ)
        '\u00F8' to "o",  // Latin small letter o with stroke (ø)
        '\u00DF' to "ss", // Latin small letter sharp s (ß)
        '\u0153' to "oe", // Latin small ligature oe (œ)
        '\u00F0' to "d",  // Latin small letter eth (ð)
        '\u00FE' to "th", // Latin small letter thorn (þ)
        '\u0142' to "l"   // Latin small letter l with stroke (ł)
    )

    private val CONFUSABLE_HOMOGLYPHS = mapOf(
        '\u0430' to 'a', // Cyrillic small a
        '\u0435' to 'e', // Cyrillic small ie
        '\u0456' to 'i', // Cyrillic small byelorussian-ukrainian i
        '\u043E' to 'o', // Cyrillic small o
        '\u0440' to 'p', // Cyrillic small er
        '\u0441' to 'c', // Cyrillic small es
        '\u0443' to 'y', // Cyrillic small u
        '\u0445' to 'x', // Cyrillic small ha
        '\u0455' to 's', // Cyrillic small dze
        '\u03B1' to 'a', // Greek small alpha
        '\u03B5' to 'e', // Greek small epsilon
        '\u03BF' to 'o'  // Greek small omicron
    )

    private val IRREGULAR_PLURALS = mapOf(
        "men" to "man",
        "women" to "woman",
        "children" to "child",
        "people" to "person",
        "knives" to "knife",
        "wives" to "wife",
        "wolves" to "wolf",
        "calves" to "calf",
        "halves" to "half",
        "leaves" to "leaf",
        "bodies" to "body",
        "armies" to "army"
    )

    /**
     * Benign geographic/scientific phrases that contain words otherwise blocked in Tier 2
     * ("face", "dead", "death", "man", "explosion") so nature/astronomy landmarks pass Schedule B §4.
     */
    private val BENIGN_LANDMARK_PHRASES = listOf(
        "north face",
        "south face",
        "east face",
        "west face",
        "northwest face",
        "northeast face",
        "southwest face",
        "southeast face",
        "rock face",
        "cliff face",
        "mountain face",
        "glacier face",
        "clock face",
        "dead sea",
        "dead tree",
        "dead trees",
        "dead wood",
        "death valley",
        "old man of",
        "isle of man",
        "no mans land",
        "supernova explosion",
        "stellar explosion",
        "solar explosion",
        "volcanic explosion"
    )

    /**
     * Fixed phrases whose words (`naked`, `bikini`, `blood`) are harmless there: astronomy, a Pacific atoll,
     * a lunar eclipse. They are removed before the Tier 1 scan only; any other occurrence of the same words
     * in the field is still matched, and every other term is unaffected (Article VII, v0.14).
     */
    private val UNCONDITIONAL_BENIGN_PHRASES = listOf(
        "naked eye",
        "bikini atoll",
        "blood moon",
        "blood orange",
        "blood red sky"
    )

    /**
     * Tier 1: Matched across title, description, tags/categories, and attribution.
     * Stored in canonical normalized/stemmed form.
     */
    val UNCONDITIONAL_TERMS: Set<String> = listOf(
        // NSFW / Adult / Nudity / Classical Nude (Article VII §1)
        "nsfw", "nude", "nudity", "naked", "erotic", "erotica", "adult content",
        "adult film", "adult only", "adult video", "porn", "pornography", "xxx",
        "sex", "sexual", "sensual", "boudoir", "lingerie", "bikini", "swimsuit",
        "swimwear", "underwear", "cleavage", "fetish", "pinup", "seductive",
        "strip club", "striptease", "topless", "uncensored", "hentai", "ecchi",
        "lewd", "provocative", "intimate", "odalisque", "bather",
        "aktmodel", "naakt", "nogen", "nogenmodel",
        // Graphic Violence / Gore / Disturbing Medical (Article VII §2)
        "violence", "violent", "gore", "gory", "blood", "bloody", "corpse",
        "cadaver", "autopsy", "mutilation", "torture", "execution", "massacre",
        "gunshot", "slaughterhouse", "battlefield",
        // Animals in Distress (Article VII §2)
        "animal cruelty", "animal in distress", "animals in distress", "poaching",
        "roadkill", "injured animal", "trapped animal",
        // Unambiguous Portrait / Event Phrases in Prose (Article VII §2)
        "selfie", "headshot", "mugshot", "fashion model", "celebrity portrait",
        "paparazzi", "close up portrait", "portrait of", "self portrait",
        "selvportraet", "zelfportret",
        "group portrait", "official portrait", "astronaut portrait", "crew portrait",
        "press conference", "ribbon cutting", "award ceremony",
        // Press and staff events on agency photo sites, whose keywords still read as scenery (Earth, Cloud)
        "media day", "media interview", "media briefing", "science briefing", "news conference",
        "from left to right",
        // Text-Dominated Images & Document Scans (Article VII §2)
        "document scan", "manuscript page", "newspaper page", "infographic",
        "motivational quote", "text heavy", "meme",
        // Hate / Extremism / Political Campaigning (Article VII §2)
        "swastika", "nazi", "confederate flag", "extremist", "propaganda",
        "election campaign", "campaign rally", "vote for", "political party",
        // Misleading System Prompts / Lock-Screen Spoofing (Article VII §2)
        "system error", "fake lock screen", "warning prompt", "security alert",
        "ransomware", "bluescreen", "kernel panic", "password prompt"
    ).map { normalizePhrase(it) }.toSet()

    /**
     * Tier 2: Matched against title and structured category/tag/classification/department fields.
     */
    val SUBJECT_AND_TAG_TERMS: Set<String> = listOf(
        // Portraiture & Identifiable People (Article VII §2)
        "portrait", "portraiture", "portraet", "portret", "person", "people",
        "man", "woman", "girl", "boy", "mand", "kvinde", "pige", "dreng",
        "child", "children", "crowd", "model", "face", "human figure", "figurative",
        "statue", "sculpture", "skulptur", "figurine", "bust", "buste", "astronaut", "crew", "ceremony",
        // Weapons & Military (Article VII §2)
        "weapon", "vaben", "firearm", "handgun", "rifle", "assault rifle", "shotgun",
        "pistol", "gun", "ammunition", "submachine gun", "bayonet", "sword",
        "knife", "dagger", "bomb", "explosion", "war", "military", "soldier",
        "troop", "combatant", "arms and armor", "armor", "rustning",
        // Medical / Mortality / Pathology (Article VII §2)
        "dead", "death", "skull", "skeleton", "anatomy", "anatomical", "disease",
        "surgery", "surgical", "injury", "wound", "pathology", "dodsmaske",
        // Logos / Insignia / Patches (Article VII §2)
        "logo", "corporate logo", "brand logo", "trademark logo", "emblem",
        "company emblem", "insignia", "mission patch", "patch", "coat of arms",
        "heraldry", "badge"
    ).map { normalizePhrase(it) }.toSet()

    /**
     * L2c (Constitution v0.13): single-scene guard. Terms matched against the title and the
     * source-supplied categories only (never prose), so a description that merely mentions
     * "montage" does not reject. Bare `array`, `composite`, `triptych`, `mosaic` and `figure` are
     * deliberately absent: radio-telescope arrays, NASA composites, triptych paintings, mosaic art
     * and "human figure" are legitimate wallpapers.
     */
    val COMPOSITE_LAYOUT_TERMS: Set<String> = listOf(
        "montage", "photomontage", "collage"
    ).map { normalizePhrase(it) }.toSet()

    /**
     * Non-photographic content (Constitution v0.25): data maps, charts, diagrams and artist or computer
     * renderings are not scenes. Matched against the title, the source-supplied categories and the first sentence
     * of the description only, so a later mention ("... compared with a map of the region") does not reject.
     */
    val NON_PHOTOGRAPHIC_TERMS: Set<String> = listOf(
        "map", "chart", "graph", "diagram", "illustration", "animation", "visualization",
        "simulation", "rendering", "artist concept", "artist s concept", "artist s impression"
    ).map { normalizePhrase(it) }.toSet()

    fun findNonPhotographicMatch(
        title: String,
        description: String,
        rawCategoriesAndTags: List<String>
    ): String? {
        val firstSentence = description.trim().split(FIRST_SENTENCE_END_REGEX, limit = 2).first()
        val tokens = buildList {
            addAll(tokenizeAndStem(title))
            add("|")
            for (tag in rawCategoriesAndTags) {
                addAll(tokenizeAndStem(tag))
                add("|")
            }
            addAll(tokenizeAndStem(firstSentence))
        }
        return findMatchInTokenStream(tokens, NON_PHOTOGRAPHIC_TERMS)
    }

    private val FIRST_SENTENCE_END_REGEX = Regex("""[.!?](?=\s)""")

    /** Grid categories such as "3x2 arrays" or "4 x 3 arrays" (normalized: "3x2 array"). */
    private val GRID_ARRAY_REGEX = Regex("""(?:^| )\d+ ?x ?\d+ array(?: |$)""")

    /** Figure-plate caption at the start of a description: "Figure 2.", "Fig. 3:". */
    private val FIGURE_CAPTION_REGEX = Regex("""^\s*fig(?:ure|\.)?\s*\d""", RegexOption.IGNORE_CASE)

    /** Panel labels "(A) ... (B)" within one description. */
    private val PANEL_LABELS_REGEX = Regex("""\(\s*a\s*\)[\s\S]{0,400}\(\s*b\s*\)""", RegexOption.IGNORE_CASE)

    /**
     * Returns a description of the first composite / multi-panel signal found (montage, collage,
     * N x M array category, or a labelled figure plate), or null for a single-scene image.
     */
    fun findCompositeLayoutMatch(
        title: String,
        description: String,
        rawCategoriesAndTags: List<String>
    ): String? {
        val tokens = buildList {
            addAll(tokenizeAndStem(title))
            add("|")
            for (tag in rawCategoriesAndTags) {
                addAll(tokenizeAndStem(tag))
                add("|")
            }
        }
        findMatchInTokenStream(tokens, COMPOSITE_LAYOUT_TERMS)?.let { return it }

        for (tag in rawCategoriesAndTags) {
            if (GRID_ARRAY_REGEX.containsMatchIn(normalizePhrase(tag))) return "grid array category '$tag'"
        }

        if (FIGURE_CAPTION_REGEX.containsMatchIn(description)) return "figure caption"
        if (PANEL_LABELS_REGEX.containsMatchIn(description)) return "labelled panels (A)/(B)"
        return null
    }

    /**
     * Exact normalized & stemmed safe domain tokens for L2b verification.
     * Note: "featured" is intentionally excluded so generic curation tags do not bypass L2b.
     */
    val SAFE_DOMAIN_KEYWORDS: Set<String> = listOf(
        "landscape", "nature", "mountain", "alpine", "peak", "ridge", "forest",
        "tree", "woodland", "ocean", "sea", "seascape", "water", "wave", "sky",
        "cloud", "astronomy", "space", "nebula", "galaxy", "star", "planet",
        "cosmos", "orbital", "earth", "architecture", "architectural", "building",
        "bridge", "castle", "cathedral", "pavilion", "courtyard", "garden",
        "cityscape", "skyline", "tower", "arch", "abstract", "pattern",
        "geometry", "geometric", "texture", "gradient", "mineral", "crystal",
        "scenic", "valley", "desert", "dune", "sandstone", "fjord", "glacier",
        "ice", "snow", "sunrise", "sunset", "dusk", "twilight", "dawn", "aurora",
        "river", "lake", "waterfall", "canyon", "cliff", "rock", "coast",
        "coastal", "shore", "island", "horizon", "botanical", "flora", "meadow",
        "field", "hill", "flower", "blossom", "petal", "leaf", "foliage",
        "orchard", "fruit", "succulent", "aerial", "satellite"
    ).map { stemToken(normalizeRaw(it)) }.toSet()

    fun normalizeRaw(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD)
        val withoutMarks = DIACRITICS_REGEX.replace(decomposed, "")
        val folded = buildString(withoutMarks.length + 8) {
            for (ch in withoutMarks.lowercase(Locale.ROOT)) {
                val ligature = LIGATURE_AND_NORDIC_FOLDS[ch]
                if (ligature != null) {
                    append(ligature)
                } else {
                    append(CONFUSABLE_HOMOGLYPHS[ch] ?: ch)
                }
            }
        }
        return folded
            .replace(NON_LETTER_DIGIT_REGEX, " ")
            .trim()
            .replace(WHITESPACE_REGEX, " ")
    }

    fun tokenizeAndStem(input: String): List<String> {
        val raw = normalizeRaw(input)
        if (raw.isEmpty()) return emptyList()
        return raw.split(' ').filter { it.isNotBlank() }.map { stemToken(it) }
    }

    fun normalizePhrase(input: String): String {
        return tokenizeAndStem(input).joinToString(" ")
    }

    fun stemToken(token: String): String {
        IRREGULAR_PLURALS[token]?.let { return it }
        if (token.length <= 3) return token
        return when {
            token.endsWith("ies") && token.length > 4 -> token.dropLast(3) + "y"
            token.endsWith("ches") || token.endsWith("shes") || token.endsWith("xes") ||
                token.endsWith("zes") || token.endsWith("sses") -> token.dropLast(2)
            token.endsWith("ves") && token.length > 4 -> token.dropLast(3) + "f"
            token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us") && !token.endsWith("is") ->
                token.dropLast(1)
            else -> token
        }
    }

    /**
     * Checks all fields against Tier 1 (unconditional) and title + tags against Tier 2 (subject/tags).
     * Returns the first matched prohibited term, or null if clean.
     */
    fun findProhibitedMatch(
        title: String,
        description: String,
        rawCategoriesAndTags: List<String>,
        attributionText: String
    ): String? {
        val allFieldsTokens = buildList {
            addAll(tokenizeAndStem(scrub(title, UNCONDITIONAL_BENIGN_PHRASES)))
            add("|")
            addAll(tokenizeAndStem(scrub(description, UNCONDITIONAL_BENIGN_PHRASES)))
            add("|")
            for (tag in rawCategoriesAndTags) {
                addAll(tokenizeAndStem(scrub(tag, UNCONDITIONAL_BENIGN_PHRASES)))
                add("|")
            }
            addAll(tokenizeAndStem(scrub(attributionText, UNCONDITIONAL_BENIGN_PHRASES)))
        }

        findMatchInTokenStream(allFieldsTokens, UNCONDITIONAL_TERMS)?.let { return it }

        val scrubbedTitle = scrubBenignLandmarks(title)
        val scrubbedTags = rawCategoriesAndTags.map { scrubBenignLandmarks(it) }
        val subjectTokens = buildList {
            addAll(tokenizeAndStem(scrubbedTitle))
            add("|")
            for (tag in scrubbedTags) {
                addAll(tokenizeAndStem(tag))
                add("|")
            }
        }

        return findMatchInTokenStream(subjectTokens, SUBJECT_AND_TAG_TERMS)
    }

    /**
     * Checks whether normalized tokens from [title] and [rawCategoriesAndTags] match
     * at least one safe visual domain token.
     */
    fun matchesSafeDomain(title: String, rawCategoriesAndTags: List<String>): Boolean {
        if (tokenizeAndStem(title).any { it in SAFE_DOMAIN_KEYWORDS }) return true
        return rawCategoriesAndTags.any { tag ->
            tokenizeAndStem(tag).any { it in SAFE_DOMAIN_KEYWORDS }
        }
    }

    private fun scrubBenignLandmarks(input: String): String = scrub(input, BENIGN_LANDMARK_PHRASES)

    private fun scrub(input: String, phrases: List<String>): String {
        var norm = " ${normalizeRaw(input)} "
        for (phrase in phrases) {
            norm = norm.replace(" $phrase ", " ")
        }
        return norm.trim()
    }

    private fun findMatchInTokenStream(stream: List<String>, phrases: Set<String>): String? {
        if (stream.isEmpty()) return null
        val singleTokenSet = stream.filter { it != "|" }.toSet()
        val joinedStream = " " + stream.joinToString(" ") + " "

        for (prohibited in phrases) {
            if (prohibited.isEmpty()) continue
            val matched = if (' ' in prohibited) {
                joinedStream.contains(" $prohibited ")
            } else {
                prohibited in singleTokenSet
            }
            if (matched) return prohibited
        }
        return null
    }
}

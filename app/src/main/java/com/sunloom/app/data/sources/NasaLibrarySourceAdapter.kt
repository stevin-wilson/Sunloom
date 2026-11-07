package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.safety.SafetyGate
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Keyless Source #2: NASA Image and Video Library API Adapter.
 * Uses https://images-api.nasa.gov (100% keyless, US Government Public Domain)
 * to retrieve bright daytime orbital Earth imagery and deep-space photography.
 *
 * Enforces:
 * - Rejection of NASA HQ (`center == "HQ"`) event/press photography.
 * - Strict third-party copyright and credit checks (©, copyright, Getty, AP, Reuters, non-NASA courtesy credits).
 * - Resolution upgrade via `collection.json` manifest or `~orig.jpg` (falling back to `~large.jpg`).
 * - Zero injected safety tags and zero fabricated dimensions.
 */
class NasaLibrarySourceAdapter(
    private val httpClient: HttpClient = DefaultHttpsClient.shared
) : WallpaperSourceAdapter {

    override val sourceId: String = "nasa_library"
    override val sourceName: String = "NASA Space & Earth Image Library"
    override val licenseSummary: String = "Public Domain (NASA / US Government Works)"

    override fun withHttpClient(client: HttpClient): WallpaperSourceAdapter =
        NasaLibrarySourceAdapter(client)

    override suspend fun fetchCandidates(
        enabledCategories: Set<SafeCategory>,
        limit: Int,
        offset: Int,
        targetWidthPx: Int
    ): List<CandidateAsset> {
        if (SafeCategory.SPACE_ASTRONOMY !in enabledCategories &&
            SafeCategory.LANDSCAPES_NATURE !in enabledCategories &&
            SafeCategory.AERIAL_EARTH !in enabledCategories) {
            return emptyList()
        }
        val page = ((offset / limit.coerceAtLeast(1)) % NASA_PAGE_SPAN) + 1
        val results = ArrayList<CandidateAsset>()
        val spacingMs = SourceRegistry.get(sourceId)?.minRequestSpacingMs ?: 150L

        // NASA's search needs every word to match, so queries stay at one or two words (the earlier four- and
        // five-word queries returned nothing). Each category rotates through a few as its fetch window advances.
        val variant = (offset / limit.coerceAtLeast(1)) / NASA_PAGE_SPAN
        fun termFor(category: SafeCategory): String {
            val options = QUERY_TERMS.getValue(category)
            return options[variant % options.size].replace(" ", "%20")
        }
        val queries = buildList {
            if (SafeCategory.AERIAL_EARTH in enabledCategories) {
                add(termFor(SafeCategory.AERIAL_EARTH) to SafeCategory.AERIAL_EARTH)
            }
            if (SafeCategory.LANDSCAPES_NATURE in enabledCategories) {
                add(termFor(SafeCategory.LANDSCAPES_NATURE) to SafeCategory.LANDSCAPES_NATURE)
            }
            if (SafeCategory.SPACE_ASTRONOMY in enabledCategories) {
                add(termFor(SafeCategory.SPACE_ASTRONOMY) to SafeCategory.SPACE_ASTRONOMY)
            }
        }.ifEmpty { listOf("nebula" to SafeCategory.SPACE_ASTRONOMY) }

        // Ask for a wide page: many records (ISS photographs) have no keywords and the gate rejects those.
        val perQueryLimit = NASA_PAGE_SIZE
        for ((idx, pair) in queries.withIndex()) {
            if (idx > 0 && spacingMs > 0L) {
                delay(spacingMs)
            }
            val (query, category) = pair
            val apiUrl = "https://images-api.nasa.gov/search?q=$query&media_type=image&page_size=$perQueryLimit&page=$page"
            val jsonText = httpClient.fetchTextOrNull(apiUrl) {
                return results.take(limit)
            } ?: continue
            val parsed = parseResponse(jsonText, category).filter { it.rawCategoriesAndTags.isNotEmpty() }
            for (candidate in parsed) {
                if (results.size >= limit) break
                results.add(candidate)
            }
        }
        return results.take(limit)
    }

    fun parseResponse(
        jsonText: String,
        defaultCategory: SafeCategory = SafeCategory.SPACE_ASTRONOMY
    ): List<CandidateAsset> {
        val out = ArrayList<CandidateAsset>()
        val root = parseSourceObject("NASA", jsonText)
        val items = root.obj("collection")?.arr("items") ?: return emptyList()

        for (i in 0 until items.size) {
            val item = items.obj(i) ?: continue
            val dataArr = item.arr("data") ?: continue
            if (dataArr.size == 0) continue
            val data = dataArr.obj(0) ?: continue

            val nasaId = data.str("nasa_id", "").trim().ifBlank { continue }
            val center = data.str("center", "NASA").trim()
            // Reject NASA HQ event, portrait, and press-conference photography (Phase 0.3)
            if (center.equals("HQ", ignoreCase = true)) {
                continue
            }

            val title = data.str("title", "").trim().ifBlank { continue }
            val description = data.str("description", title).trim()
            val photographer = data.str("photographer", "").trim()
            val secondaryCreator = data.str("secondary_creator", "").trim()

            // Schedule A & Article VI §5: Reject any third-party copyright or non-NASA agency credit
            if (hasThirdPartyCopyrightOrCredit(description, photographer, secondaryCreator)) {
                continue
            }

            val resolvedCreator = photographer.ifBlank {
                secondaryCreator.ifBlank { "$center Science Team (NASA)" }
            }

            // Zero injected tags: only include keywords returned by NASA
            val keywordsArr = data.arr("keywords")
            val tags = ArrayList<String>()
            if (keywordsArr != null) {
                for (k in 0 until keywordsArr.size) {
                    val kw = keywordsArr.str(k, "").trim()
                    if (kw.isNotBlank()) {
                        tags.add(kw)
                    }
                }
            }

            val linksArr = item.arr("links")
            var downloadUrl = ""
            if (linksArr != null) {
                for (l in 0 until linksArr.size) {
                    val linkObj = linksArr.obj(l) ?: continue
                    val href = linkObj.str("href", "").trim()
                    if (href.startsWith("https://") && (href.endsWith(".jpg") || href.endsWith(".png"))) {
                        // Prefer ~orig.jpg first; LocalLibraryRepository automatically falls back to ~large.jpg if ~orig.jpg is a .tif
                        downloadUrl = href
                            .replace("~thumb.jpg", "~orig.jpg")
                            .replace("~medium.jpg", "~orig.jpg")
                            .replace("~small.jpg", "~orig.jpg")
                        break
                    }
                }
            }
            if (downloadUrl.isBlank()) continue
            val fallbackUrls = buildList {
                val largeUrl = downloadUrl.replace("~orig.jpg", "~large.jpg")
                if (largeUrl != downloadUrl) add(largeUrl)
            }

            out.add(
                CandidateAsset(
                    id = "nasa-$nasaId",
                    title = title,
                    description = description,
                    category = defaultCategory,
                    rawCategoriesAndTags = tags,
                    downloadUrl = downloadUrl,
                    fallbackDownloadUrls = fallbackUrls,
                    provenance = ProvenanceRecord(
                        sourceId = sourceId,
                        sourceName = sourceName,
                        assetId = "nasa-$nasaId",
                        originalPageUrl = "https://images.nasa.gov/details/$nasaId",
                        creatorName = resolvedCreator,
                        creatorPageUrl = "",
                        licenseType = LicenseType.PUBLIC_DOMAIN,
                        attributionText = "Courtesy of $resolvedCreator / NASA (Public Domain)",
                        retrievedAtEpochMs = System.currentTimeMillis(),
                        originalWidth = 0,
                        originalHeight = 0,
                        renditionWidth = 0,
                        renditionHeight = 0,
                        safetyClassification = SafetyClassification.SAFE,
                        safetyClassificationSource = "NASA Public Domain Catalog ($center)",
                        sha256Checksum = "",
                        licenseUrl = LicenseType.PUBLIC_DOMAIN.defaultUrl,
                        licenseVersion = "1.0",
                        adapterVersion = SourceRegistry.get(sourceId)?.adapterVersion ?: ProvenanceRecord.DEFAULT_ADAPTER_VERSION,
                        safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION
                    )
                )
            )
        }
        return out
    }

    /**
     * Parses a NASA asset manifest (`collection.json`) array of URLs and returns the best
     * high-resolution HTTPS `.jpg` or `.png` URL (`~orig` preferred, then `~large`).
     */
    fun selectBestRenditionFromManifest(manifestJson: String): String? {
        val arr = SimpleJson.parseArray(manifestJson) ?: return null
        val urls = (0 until arr.size).map { arr.str(it, "").trim() }
            .filter { it.startsWith("https://") && (it.endsWith(".jpg", ignoreCase = true) || it.endsWith(".png", ignoreCase = true)) }
        return urls.firstOrNull { it.contains("~orig.", ignoreCase = true) }
            ?: urls.firstOrNull { it.contains("~large.", ignoreCase = true) }
            ?: urls.firstOrNull()
    }

    fun hasThirdPartyCopyrightOrCredit(
        description: String,
        photographer: String,
        secondaryCreator: String
    ): Boolean {
        val combined = "$description | $photographer | $secondaryCreator".lowercase(Locale.ROOT)
            .replace("u.s.", "us") // keep "U.S. Geological Survey" in one piece for the credit clause below
        if (combined.contains("©") ||
            combined.contains("copyright") ||
            combined.contains("all rights reserved") ||
            combined.contains("getty") ||
            combined.contains("reuters") ||
            combined.contains("associated press") ||
            combined.contains("ap photo") ||
            combined.contains("alamy") ||
            combined.contains("shutterstock")
        ) {
            return true
        }

        // Reject standalone ESA credit if NASA is not credited alongside it
        if (ESA_WORD_REGEX.containsMatchIn(combined) && !combined.contains("nasa")) {
            return true
        }

        // Check "courtesy of <entity>" or "image credit: <entity>" naming a third party other than NASA/JPL/STScI/USGS/NOAA
        for (match in CREDIT_CLAUSE_REGEX.findAll(combined)) {
            val credited = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (credited.isNotEmpty() && !namesGovernmentSource(credited)) {
                return true
            }
        }
        return false
    }

    /** Whole words only: "arc" must not match inside "Marcus", nor "jsc" inside a surname. */
    private fun namesGovernmentSource(credited: String): Boolean {
        if (ALLOWED_US_GOV_PHRASES.any { credited.contains(it) }) return true
        return credited.split(NON_WORD_REGEX).any { it in ALLOWED_US_GOV_CREDITS }
    }

    companion object {
        private val NON_WORD_REGEX = Regex("[^a-z0-9]+")
        private val ALLOWED_US_GOV_PHRASES = listOf("geological survey", "public domain")

        /** NASA search pages walked before wrapping; deep enough that reinstalls and syncs differ. */
        private const val NASA_PAGE_SPAN = 20

        /**
         * Short queries, each checked against the live API on 2026-11-07. The fetch window pages up to
         * NASA_PAGE_SPAN x NASA_PAGE_SIZE = 400 results, so every term has at least 400 hits (and most have
         * well over 1000); a term with fewer would return empty pages near the end of its cycle.
         */
        private val QUERY_TERMS: Map<SafeCategory, List<String>> = mapOf(
            SafeCategory.AERIAL_EARTH to listOf("earth horizon", "earth night", "coast"),
            SafeCategory.LANDSCAPES_NATURE to listOf("earth clouds", "volcano", "desert", "lake"),
            SafeCategory.SPACE_ASTRONOMY to listOf("galaxy", "aurora", "milky way")
        )
        private const val NASA_PAGE_SIZE = 20

        private val ESA_WORD_REGEX = Regex("\\besa\\b")
        private val CREDIT_CLAUSE_REGEX = Regex("""(?:courtesy of|image credit:|photo credit:|credit:)\s*([^.;,\n|]+)""")
        private val ALLOWED_US_GOV_CREDITS = setOf(
            "nasa",
            "jpl",
            "caltech",
            "stsci",
            "gsfc",
            "jsc",
            "msfc",
            "arc",
            "ksc",
            "usgs",
            "noaa"
        )
    }
}

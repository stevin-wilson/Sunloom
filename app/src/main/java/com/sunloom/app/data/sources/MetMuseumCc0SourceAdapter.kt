package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.safety.SafetyGate
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.util.Locale

/**
 * Admitted Schedule A Source Adapter for The Metropolitan Museum of Art Open Access API.
 *
 * Uses the public, keyless REST API (`collectionapi.metmuseum.org/public/collection/v1`),
 * enforces `isPublicDomain == true` (CC0 1.0 Universal), prefers `primaryImage` (full-resolution),
 * enforces a strict department & classification allowlist, and injects zero fake safety tags.
 */
class MetMuseumCc0SourceAdapter(
    private val httpClient: HttpClient = DefaultHttpsClient.shared
) : WallpaperSourceAdapter {

    override val sourceId: String = "met_museum_cc0"
    override val sourceName: String = "The Met Open Access (CC0)"
    override val licenseSummary: String = "CC0 1.0 Public Domain (The Metropolitan Museum of Art)"

    override fun withHttpClient(client: HttpClient): WallpaperSourceAdapter =
        MetMuseumCc0SourceAdapter(client)

    override suspend fun fetchCandidates(
        enabledCategories: Set<SafeCategory>,
        limit: Int,
        offset: Int,
        targetWidthPx: Int
    ): List<CandidateAsset> {
        val targetCategories = enabledCategories.intersect(
            setOf(
                SafeCategory.SCENIC_FINE_ART,
                SafeCategory.LANDSCAPES_NATURE,
                SafeCategory.ARCHITECTURE_CITYSCAPES,
                SafeCategory.BOTANICAL_FLORA
            )
        )
        if (targetCategories.isEmpty()) return emptyList()

        // Each search term carries the category its results are stored under, so a record is only ever
        // filed under a category the user enabled (WallpaperMatcher drops everything else).
        val searchTerms = buildList {
            if (SafeCategory.BOTANICAL_FLORA in targetCategories) {
                add("botanical flowers garden flora" to SafeCategory.BOTANICAL_FLORA)
            }
            if (SafeCategory.SCENIC_FINE_ART in targetCategories || SafeCategory.LANDSCAPES_NATURE in targetCategories) {
                val landscapeCategory = if (SafeCategory.SCENIC_FINE_ART in targetCategories) {
                    SafeCategory.SCENIC_FINE_ART
                } else {
                    SafeCategory.LANDSCAPES_NATURE
                }
                add("landscape mountains" to landscapeCategory)
                add("seascape ocean horizon" to landscapeCategory)
                add("sunset valley river" to landscapeCategory)
            }
            if (SafeCategory.ARCHITECTURE_CITYSCAPES in targetCategories) {
                add("architecture garden courtyard" to SafeCategory.ARCHITECTURE_CITYSCAPES)
            }
        }
        val maxBudget = SourceRegistry.get(sourceId)?.maxRequestsPerSync ?: 7
        val spacingMs = SourceRegistry.get(sourceId)?.minRequestSpacingMs ?: 150L
        val maxLookups = (limit * 2).coerceIn(2, ((maxBudget - 1) / 2).coerceAtLeast(2))

        // One search window holds SEARCH_WINDOW ids and each sync examines maxLookups of them, so a window is
        // worth ceil(SEARCH_WINDOW / maxLookups) consecutive syncs: finish it, then move to the next query,
        // then the next window.
        val syncsPerWindow = (SEARCH_WINDOW + maxLookups - 1) / maxLookups
        val cursorStep = offset.coerceAtLeast(0) / CURSOR_STEP
        val windowIndex = cursorStep / syncsPerWindow
        val (term, termCategory) = searchTerms[windowIndex % searchTerms.size]
        val encodedTerm = URLEncoder.encode(term, "UTF-8")

        // The Met retired /v1/search on 2026-10-01 (HTTP 410). /v1.1/search is paginated: ask for a window of
        // ids at the cursor, and for the first window when the cursor is past the end of a small result set.
        fun searchUrl(start: Int) = "https://collectionapi.metmuseum.org/public/collection/v1.1/search" +
            "?isPublicDomain=true&hasImages=true&q=$encodedTerm&offset=$start&limit=$SEARCH_WINDOW"

        val windowStart = (windowIndex / searchTerms.size * SEARCH_WINDOW) % MAX_WINDOW_START
        var objectIds = httpClient.fetchTextOrNull(searchUrl(windowStart))
            ?.let { SimpleJson.parseObject(it) }?.arr("objectIDs")
        if ((objectIds == null || objectIds.size == 0) && windowStart > 0) {
            objectIds = httpClient.fetchTextOrNull(searchUrl(0))
                ?.let { SimpleJson.parseObject(it) }?.arr("objectIDs")
        }
        if (objectIds == null || objectIds.size == 0) return emptyList()

        val results = ArrayList<CandidateAsset>()
        // The cursor moves in steps of 6 inside one window; advance the lookups by one batch per step so
        // consecutive syncs examine different objects instead of the same few positions.
        val startIndex = ((cursorStep % syncsPerWindow) * maxLookups) % objectIds.size

        for (step in 0 until minOf(objectIds.size, maxLookups)) {
            if (results.size >= limit) break
            val objId = objectIds.int((startIndex + step) % objectIds.size, -1)
            if (objId <= 0) continue

            if (step > 0 && spacingMs > 0L) {
                delay(spacingMs)
            }
            val objectUrl = "https://collectionapi.metmuseum.org/public/collection/v1/objects/$objId"
            val objRaw = httpClient.fetchTextOrNull(objectUrl) {
                return results
            } ?: continue
            parseObjectResponse(objRaw, termCategory)?.let { results.add(it) }
        }
        return results
    }

    fun parseObjectResponse(
        jsonString: String,
        category: SafeCategory = SafeCategory.SCENIC_FINE_ART
    ): CandidateAsset? {
        val obj = SimpleJson.parseObject(jsonString) ?: return null
        if (!obj.bool("isPublicDomain", false)) return null

        val objectId = obj.int("objectID", -1)
        if (objectId <= 0) return null

        val title = obj.str("title", "").trim()
        if (title.isEmpty()) return null

        val department = obj.str("department", "").trim()
        val classification = obj.str("classification", "").trim()
        if (!isDepartmentAndClassificationAllowed(department, classification)) {
            return null
        }

        // Prefer full-resolution primaryImage over primaryImageSmall (Phase 0.1)
        val primaryImageLarge = obj.str("primaryImage", "").trim()
        val primaryImageSmall = obj.str("primaryImageSmall", "").trim()
        val downloadUrl = when {
            primaryImageLarge.startsWith("https://") -> primaryImageLarge
            primaryImageSmall.startsWith("https://") -> primaryImageSmall
            else -> return null
        }

        val artist = obj.str("artistDisplayName", "").trim()
            .ifEmpty { "Unknown Artist (The Met Open Access)" }
        val creatorUrl = obj.str("artistULAN_URL", "").trim()
            .takeIf { it.startsWith("https://") }
            .orEmpty()
        val medium = obj.str("medium", "").trim()
        val objectPageUrl = obj.str(
            "objectURL",
            "https://www.metmuseum.org/art/collection/search/$objectId"
        ).trim().let {
            if (it.startsWith("https://")) it else "https://www.metmuseum.org/art/collection/search/$objectId"
        }

        // Zero injected tags: only use department, classification, and tags[].term from The Met API
        val tags = ArrayList<String>()
        if (department.isNotEmpty()) tags.add(department)
        if (classification.isNotEmpty()) tags.add(classification)

        val tagsArray = obj.arr("tags")
        if (tagsArray != null) {
            for (i in 0 until tagsArray.size) {
                val tagObj = tagsArray.obj(i) ?: continue
                val term = tagObj.str("term", "").trim()
                if (term.isNotEmpty()) {
                    tags.add(term)
                }
            }
        }

        return CandidateAsset(
            id = "met-$objectId",
            title = title,
            description = listOf(medium, classification, department).filter { it.isNotBlank() }.joinToString(" • ")
                .ifEmpty { "Open-access public-domain artwork from The Metropolitan Museum of Art" },
            category = category,
            rawCategoriesAndTags = tags,
            downloadUrl = downloadUrl,
            provenance = ProvenanceRecord(
                sourceId = sourceId,
                sourceName = sourceName,
                assetId = "met-$objectId",
                originalPageUrl = objectPageUrl,
                creatorName = artist,
                creatorPageUrl = creatorUrl,
                licenseType = LicenseType.CC0,
                attributionText = "$artist — Public Domain (CC0 1.0) via The Metropolitan Museum of Art",
                retrievedAtEpochMs = System.currentTimeMillis(),
                originalWidth = 0,
                originalHeight = 0,
                renditionWidth = 0,
                renditionHeight = 0,
                safetyClassification = SafetyClassification.SAFE,
                safetyClassificationSource = "The Met Open Access CC0 Department/Classification Filter (${listOf(department, classification).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "Open Access" }})",
                sha256Checksum = "",
                licenseUrl = LicenseType.CC0.defaultUrl,
                licenseVersion = "1.0",
                adapterVersion = SourceRegistry.get(sourceId)?.adapterVersion ?: ProvenanceRecord.DEFAULT_ADAPTER_VERSION,
                safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION
            )
        )
    }

    private fun isDepartmentAndClassificationAllowed(department: String, classification: String): Boolean {
        val depLower = department.lowercase(Locale.ROOT)
        val clsLower = classification.lowercase(Locale.ROOT)

        if (PROHIBITED_MET_DEPARTMENTS_OR_CLASSES.any { depLower.contains(it) || clsLower.contains(it) }) {
            return false
        }
        val classOk = clsLower.isEmpty() || ALLOWED_MET_CLASSIFICATIONS.any { clsLower.contains(it) }
        val depOk = depLower.isEmpty() || ALLOWED_MET_DEPARTMENTS.any { depLower.contains(it) }
        return classOk && depOk
    }

    companion object {
        /** Object ids requested per search window (/v1.1/search is paginated by offset and limit). */
        private const val SEARCH_WINDOW = 12

        /** Size of one fetch-cursor step (see LocalLibraryRepository). */
        private const val CURSOR_STEP = 6


        /** Deepest window start before wrapping; most themed queries have thousands of public-domain hits. */
        private const val MAX_WINDOW_START = 600

        private val ALLOWED_MET_CLASSIFICATIONS = listOf(
            "painting",
            "drawing",
            "print",
            "photograph",
            "scroll",
            "screen",
            "watercolor",
            "landscape"
        )

        private val ALLOWED_MET_DEPARTMENTS = listOf(
            "european paintings",
            "american wing",
            "asian art",
            "drawings and prints",
            "photographs",
            "modern and contemporary art",
            "robert lehman collection",
            "islamic art"
        )

        private val PROHIBITED_MET_DEPARTMENTS_OR_CLASSES = listOf(
            "arms and armor",
            "armor",
            "weapon",
            "firearm",
            "sword",
            "sculpture",
            "statue",
            "figurine",
            "costume",
            "musical instrument",
            "jewelry",
            "coin",
            "medal"
        )
    }
}

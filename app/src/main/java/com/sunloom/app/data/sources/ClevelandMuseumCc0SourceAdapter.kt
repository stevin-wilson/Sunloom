package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.safety.SafetyGate
import java.net.URLEncoder
import java.util.Locale

/**
 * Schedule A Admitted Source Adapter: Cleveland Museum of Art Open Access API (`cma_cc0`).
 *
 * Uses the public, keyless single-GET REST API (`openaccess-api.clevelandart.org/api/artworks/`),
 * enforces fail-closed `share_license_status == "CC0"`, filters to 2D visual art types
 * (excluding Sculpture, Arms and Armor, Coins, Portraits), prefers `images.print` JPEG renditions
 * with `images.web` fallback while skipping `.tif` files, and injects zero synthetic tags.
 */
class ClevelandMuseumCc0SourceAdapter(
    private val httpClient: HttpClient = DefaultHttpsClient.shared
) : WallpaperSourceAdapter {

    override val sourceId: String = "cma_cc0"
    override val sourceName: String = "Cleveland Museum of Art Open Access (CC0)"
    override val licenseSummary: String = "CC0 1.0 Universal (Public Domain Dedication)"

    override fun withHttpClient(client: HttpClient): WallpaperSourceAdapter =
        ClevelandMuseumCc0SourceAdapter(client)

    override suspend fun fetchCandidates(
        enabledCategories: Set<SafeCategory>,
        limit: Int,
        offset: Int,
        targetWidthPx: Int
    ): List<CandidateAsset> {
        val supported = enabledCategories.intersect(
            setOf(
                SafeCategory.SCENIC_FINE_ART,
                SafeCategory.LANDSCAPES_NATURE,
                SafeCategory.ARCHITECTURE_CITYSCAPES,
                SafeCategory.ABSTRACT_PATTERNS,
                SafeCategory.BOTANICAL_FLORA
            )
        )
        if (supported.isEmpty()) return emptyList()

        val querySpecs = buildList {
            if (SafeCategory.BOTANICAL_FLORA in supported) {
                add("botanical garden flowers fruit" to SafeCategory.BOTANICAL_FLORA)
            }
            if (SafeCategory.SCENIC_FINE_ART in supported || SafeCategory.LANDSCAPES_NATURE in supported) {
                add("landscape mountain river" to SafeCategory.SCENIC_FINE_ART)
                add("seascape coast ocean" to SafeCategory.SCENIC_FINE_ART)
                add("forest valley sunset" to SafeCategory.SCENIC_FINE_ART)
                if (SafeCategory.BOTANICAL_FLORA !in supported) {
                    add("botanical garden meadow" to SafeCategory.LANDSCAPES_NATURE)
                }
            }
            if (SafeCategory.ARCHITECTURE_CITYSCAPES in supported) {
                add("architecture courtyard bridge" to SafeCategory.ARCHITECTURE_CITYSCAPES)
            }
            if (SafeCategory.ABSTRACT_PATTERNS in supported) {
                add("geometric pattern textile" to SafeCategory.ABSTRACT_PATTERNS)
            }
        }.ifEmpty { listOf("landscape mountain river" to SafeCategory.SCENIC_FINE_ART) }

        val (query, targetCategory) = querySpecs[(offset / 4).coerceAtLeast(0) % querySpecs.size]
        val skip = (offset % SKIP_SPAN).coerceAtLeast(0)
        val requestLimit = (limit * 2).coerceIn(4, 16)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val apiUrl = "https://openaccess-api.clevelandart.org/api/artworks/" +
            "?cc0=1&has_image=1&q=$encodedQuery&limit=$requestLimit&skip=$skip"

        val jsonText = httpClient.fetchTextOrNull(apiUrl) ?: return emptyList()

        return parseResponse(jsonText, targetCategory, targetWidthPx).take(limit)
    }

    fun parseResponse(
        jsonText: String,
        targetCategory: SafeCategory = SafeCategory.SCENIC_FINE_ART,
        targetWidthPx: Int = 2560
    ): List<CandidateAsset> {
        val out = ArrayList<CandidateAsset>()
        val root = parseSourceObject("Cleveland", jsonText)
        val dataArr = root.arr("data") ?: return emptyList()

        for (i in 0 until dataArr.size) {
            dataArr.obj(i)?.let { item -> parseArtwork(item, targetCategory, targetWidthPx)?.let(out::add) }
        }
        return out
    }

    private fun parseArtwork(
        item: SimpleJson.JsonObj,
        targetCategory: SafeCategory,
        targetWidthPx: Int
    ): CandidateAsset? {
        val id = item.int("id", -1)
        if (id <= 0) return null

        // Fail-closed: literal license field must be "CC0"
        val licenseStatus = item.str("share_license_status", "").trim()
        if (!licenseStatus.equals("CC0", ignoreCase = true)) return null

        val copyrightField = item.str("copyright", "").trim()
        if (isCopyrightRestricted(copyrightField)) return null

        val title = item.str("title").cleanedOrNull() ?: return null

        val type = item.str("type", "").trim()
        val department = item.str("department", "").trim()
        if (!isTypeAndDepartmentAllowed(type = type, department = department)) {
            return null
        }

        val images = chooseImages(item.obj("images") ?: return null, targetWidthPx) ?: return null

        val creator = extractCreator(item)
        val canonicalPageUrl = canonicalPageUrl(item, id)

        // Zero synthetic tags: use only genuine CMA classification/medium/culture fields.
        // Note: Do not copy combined department strings like "American Painting and Sculpture"
        // into tags when `type` is "Painting", so Tier 2 "sculpture" doesn't false-reject paintings.
        val tags = ArrayList<String>()
        type.cleanedOrNull()?.let(tags::add)
        item.str("collection").cleanedOrNull()?.let(tags::add)
        item.str("technique").cleanedOrNull()?.let(tags::add)
        item.arr("culture")?.let { tags.addAll(it.cleanStrings()) }

        val description = item.str("tombstone").cleanedOrNull()
            ?: "$title — $creator (Cleveland Museum of Art Open Access CC0)"

        val resolvedCategory = when {
            type.contains("textile", ignoreCase = true) && targetCategory == SafeCategory.ABSTRACT_PATTERNS ->
                SafeCategory.ABSTRACT_PATTERNS
            else -> targetCategory
        }

        return CandidateAsset(
            id = "cma-$id",
            title = title,
            description = description,
            category = resolvedCategory,
            rawCategoriesAndTags = tags,
            downloadUrl = images.primaryUrl,
            fallbackDownloadUrls = images.fallbackUrls,
            provenance = ProvenanceRecord(
                sourceId = sourceId,
                sourceName = sourceName,
                assetId = "cma-$id",
                originalPageUrl = canonicalPageUrl,
                creatorName = creator,
                creatorPageUrl = "",
                licenseType = LicenseType.CC0,
                attributionText = "$creator — $title (CC0 1.0 Public Domain, Cleveland Museum of Art)",
                retrievedAtEpochMs = System.currentTimeMillis(),
                originalWidth = images.advertisedWidth,
                originalHeight = images.advertisedHeight,
                renditionWidth = 0,
                renditionHeight = 0,
                safetyClassification = SafetyClassification.SAFE,
                safetyClassificationSource = "CMA Open Access CC0 Type Filter (${type.ifBlank { "2D Artwork" }})",
                sha256Checksum = "",
                licenseUrl = LicenseType.CC0.defaultUrl,
                licenseVersion = "1.0",
                adapterVersion = SourceRegistry.get(sourceId)?.adapterVersion ?: ProvenanceRecord.DEFAULT_ADAPTER_VERSION,
                safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION
            )
        )
    }

    /** The renditions to download, best first, and the pixel size the catalog states for the first (0 when it states none). */
    private class ImageChoice(
        val primaryUrl: String,
        val fallbackUrls: List<String>,
        val advertisedWidth: Int,
        val advertisedHeight: Int
    )

    private fun chooseImages(imagesObj: SimpleJson.JsonObj, targetWidthPx: Int): ImageChoice? {
        val printUrl = imagesObj.obj("print")?.str("url", "")?.trim().orEmpty()
        val webUrl = imagesObj.obj("web")?.str("url", "")?.trim().orEmpty()
        val fullObj = imagesObj.obj("full")
        val fullUrl = fullObj?.str("url", "")?.trim().orEmpty()
        val fullW = fullObj?.int("width", 0) ?: 0

        val validUrls = buildList {
            if (targetWidthPx > 3000) {
                if (isSupportedHttpsRasterUrl(fullUrl) && (fullW in 1..8192)) add(fullUrl)
                if (isSupportedHttpsRasterUrl(printUrl)) add(printUrl)
            } else {
                if (isSupportedHttpsRasterUrl(printUrl)) add(printUrl)
                if (isSupportedHttpsRasterUrl(fullUrl) && (fullW in 1..8192)) add(fullUrl)
            }
            if (isSupportedHttpsRasterUrl(webUrl)) add(webUrl)
        }.distinct()

        if (validUrls.isEmpty()) return null
        val primaryDownloadUrl = validUrls.first()
        // The catalog states each rendition's pixel size: pass on that of the one we will download, so a
        // portrait work or a rendition too small for the display is skipped before it costs a download.
        val primaryRendition = listOf(
            printUrl to imagesObj.obj("print"),
            fullUrl to fullObj,
            webUrl to imagesObj.obj("web")
        ).firstOrNull { it.first == primaryDownloadUrl }?.second
        val advertisedW = primaryRendition?.int("width", 0) ?: 0
        val advertisedH = primaryRendition?.int("height", 0) ?: 0
        val hasAdvertisedSize = advertisedW > 0 && advertisedH > 0
        return ImageChoice(
            primaryUrl = primaryDownloadUrl,
            fallbackUrls = validUrls.drop(1),
            advertisedWidth = if (hasAdvertisedSize) advertisedW else 0,
            advertisedHeight = if (hasAdvertisedSize) advertisedH else 0
        )
    }

    private fun canonicalPageUrl(item: SimpleJson.JsonObj, id: Int): String {
        val accession = item.str("accession_number", "").trim()
        val rawPageUrl = item.str("url", "").trim()
        return if (rawPageUrl.startsWith("https://") &&
            SourceRegistry.isHostAllowed(extractHost(rawPageUrl), sourceId)
        ) {
            rawPageUrl
        } else {
            "https://www.clevelandart.org/art/${accession.ifEmpty { id.toString() }}"
        }
    }

    /** A stated copyright that is not a public-domain or CC0 dedication. An empty field or the text "null" is no statement. */
    private fun isCopyrightRestricted(copyrightField: String): Boolean {
        if (copyrightField.isEmpty() || copyrightField.equals("null", ignoreCase = true)) return false
        return !copyrightField.contains("public domain", ignoreCase = true) && !copyrightField.contains("cc0", ignoreCase = true)
    }

    private fun extractCreator(item: SimpleJson.JsonObj): String {
        val creators = item.arr("creators")
        if (creators != null) {
            for (i in 0 until creators.size) {
                creators.obj(i)?.str("description")?.cleanedOrNull()?.let { return it }
            }
        }
        return "Unknown Artist (Cleveland Museum of Art)"
    }

    private fun isTypeAndDepartmentAllowed(type: String, department: String): Boolean {
        val typeLower = type.lowercase(Locale.ROOT)
        val depLower = department.lowercase(Locale.ROOT)
        if (typeLower.isEmpty()) return false
        if (PROHIBITED_DEPARTMENTS.any { depLower.contains(it) }) return false
        if (PROHIBITED_TYPES.any { typeLower.contains(it) }) return false
        return ALLOWED_TYPES.any { typeLower.contains(it) }
    }

    private fun isSupportedHttpsRasterUrl(url: String): Boolean =
        SourceAdapterUtils.isSupportedHttpsRasterUrl(url, sourceId)

    private fun extractHost(url: String): String =
        SourceAdapterUtils.extractHost(url)

    companion object {
        /** Result window walked before wrapping (the catalog holds thousands of CC0 works). */
        private const val SKIP_SPAN = 240

        private val ALLOWED_TYPES = listOf(
            "painting",
            "drawing",
            "print",
            "photograph",
            "textile",
            "scroll",
            "screen",
            "watercolor",
            "album"
        )

        private val PROHIBITED_DEPARTMENTS = listOf(
            "arms and armor"
        )

        private val PROHIBITED_TYPES = listOf(
            "sculpture",
            "statue",
            "figurine",
            "bust",
            "armor",
            "arms",
            "weapon",
            "sword",
            "firearm",
            "coin",
            "medal",
            "amulet",
            "jewelry",
            "vessel",
            "ceramic",
            "furniture",
            "garment",
            "costume",
            "mask",
            "portrait",
            "miniature"
        )
    }
}

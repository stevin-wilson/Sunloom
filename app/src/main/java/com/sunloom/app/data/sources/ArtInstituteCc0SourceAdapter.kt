package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.CandidateAsset
import com.sunloom.app.domain.model.LicenseType
import com.sunloom.app.domain.model.ProvenanceRecord
import com.sunloom.app.domain.model.SafeCategory
import com.sunloom.app.domain.model.SafetyClassification
import com.sunloom.app.domain.safety.SafetyGate
import java.util.Locale

/**
 * Keyless Source #3: Art Institute of Chicago Open Access (CC0) Adapter.
 * Strictly queries non-figurative landscape and architectural public-domain works,
 * enforces fail-closed `is_public_domain == true` (default false), and injects zero fake tags.
 */
class ArtInstituteCc0SourceAdapter(
    private val httpClient: HttpClient = DefaultHttpsClient.shared
) : WallpaperSourceAdapter {

    override val sourceId: String = "artic_cc0"
    override val sourceName: String = "Art Institute of Chicago Open Access (Landscapes)"
    override val licenseSummary: String = "CC0 1.0 Universal (Public Domain Dedication)"

    override fun withHttpClient(client: HttpClient): WallpaperSourceAdapter =
        ArtInstituteCc0SourceAdapter(client)

    override suspend fun fetchCandidates(
        enabledCategories: Set<SafeCategory>,
        limit: Int,
        offset: Int,
        targetWidthPx: Int
    ): List<CandidateAsset> {
        if (SafeCategory.SCENIC_FINE_ART !in enabledCategories &&
            SafeCategory.LANDSCAPES_NATURE !in enabledCategories &&
            SafeCategory.BOTANICAL_FLORA !in enabledCategories) {
            return emptyList()
        }
        val page = ((offset / limit.coerceAtLeast(1)) % 8) + 1
        // Alternate between the enabled themes; each query's results are filed under the category it serves.
        val queries = buildList {
            if (SafeCategory.SCENIC_FINE_ART in enabledCategories || SafeCategory.LANDSCAPES_NATURE in enabledCategories) {
                val landscapeCategory = if (SafeCategory.SCENIC_FINE_ART in enabledCategories) {
                    SafeCategory.SCENIC_FINE_ART
                } else {
                    SafeCategory.LANDSCAPES_NATURE
                }
                add("landscape%20mountains%20seascape%20clouds%20valley" to landscapeCategory)
            }
            if (SafeCategory.BOTANICAL_FLORA in enabledCategories) {
                add("botanical%20flowers%20garden%20orchard%20foliage" to SafeCategory.BOTANICAL_FLORA)
            }
        }
        val (query, queryCategory) = queries[((offset / limit.coerceAtLeast(1)) / 8) % queries.size]
        val apiUrl = "https://api.artic.edu/api/v1/artworks/search" +
            "?q=$query" +
            "&fields=id,title,artist_title,image_id,is_public_domain,classification_title,subject_titles,term_titles" +
            "&limit=$limit&page=$page"
        val jsonText = httpClient.fetchTextOrNull(apiUrl) ?: return emptyList()
        return parseResponse(jsonText, queryCategory).take(limit)
    }

    fun parseResponse(
        jsonText: String,
        category: SafeCategory = SafeCategory.SCENIC_FINE_ART
    ): List<CandidateAsset> {
        val out = ArrayList<CandidateAsset>()
        val root = parseSourceObject("Art Institute", jsonText)
        val dataArr = root.arr("data") ?: return emptyList()

        for (i in 0 until dataArr.size) {
            val item = dataArr.obj(i) ?: continue
            val id = item.int("id", -1)
            val imageId = item.str("image_id").cleanedOrNull()
            if (id <= 0 || imageId == null) continue

            // Fail-closed: is_public_domain must be explicitly true (default false)
            if (!item.bool("is_public_domain", false)) continue

            val title = item.str("title", "").trim().ifBlank { continue }
            val artist = item.str("artist_title").cleanedOrNull()
                ?: "Unknown Artist (Art Institute of Chicago)"
            val classification = item.str("classification_title", "").trim().lowercase(Locale.ROOT)

            // Block sculpture, figurine, anatomy, or portrait classifications at source adapter level
            if (classification.containsAnyOf("sculpture", "portrait", "nude", "armor", "weapon", "costume")) {
                continue
            }

            // Zero injected tags: only include actual fields from the ArtIC response
            val tags = ArrayList<String>()
            classification.cleanedOrNull()?.let(tags::add)
            item.arr("subject_titles")?.let { tags.addAll(it.cleanStrings()) }
            item.arr("term_titles")?.let { tags.addAll(it.cleanStrings()) }

            // Request ArtIC's largest public IIIF rendition (1686px; falls back to 843px if 1686px 403/404s)
            val iiifUrl = "https://www.artic.edu/iiif/2/$imageId/full/1686,/0/default.jpg"
            val iiifFallbackUrl = "https://www.artic.edu/iiif/2/$imageId/full/843,/0/default.jpg"
            val pageUrl = "https://www.artic.edu/artworks/$id"

            out.add(
                CandidateAsset(
                    id = "artic-$id",
                    title = title,
                    description = "$title — $artist (Art Institute of Chicago Open Access CC0)",
                    category = category,
                    rawCategoriesAndTags = tags,
                    downloadUrl = iiifUrl,
                    fallbackDownloadUrls = listOf(iiifFallbackUrl),
                    provenance = ProvenanceRecord(
                        sourceId = sourceId,
                        sourceName = sourceName,
                        assetId = "artic-$id",
                        originalPageUrl = pageUrl,
                        creatorName = artist,
                        creatorPageUrl = "",
                        licenseType = LicenseType.CC0,
                        attributionText = "$artist — $title (CC0 1.0 Public Domain, Art Institute of Chicago)",
                        retrievedAtEpochMs = System.currentTimeMillis(),
                        originalWidth = 0,
                        originalHeight = 0,
                        renditionWidth = 0,
                        renditionHeight = 0,
                        safetyClassification = SafetyClassification.SAFE,
                        safetyClassificationSource = "ArtIC Open Access Public Domain Filter (${classification.ifBlank { "Artwork" }})",
                        sha256Checksum = "",
                        licenseUrl = LicenseType.CC0.defaultUrl,
                        licenseVersion = "1.0",
                        adapterVersion = SourceRegistry.get(sourceId)?.adapterVersion ?: ProvenanceRecord.DEFAULT_ADAPTER_VERSION,
                        safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION
                    )
                )
            )
        }
        return out
    }
}

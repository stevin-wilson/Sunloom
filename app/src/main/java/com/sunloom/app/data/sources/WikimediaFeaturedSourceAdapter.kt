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
 * Keyless Source #1: Wikimedia Commons Featured Pictures Adapter.
 * Queries curated Featured Pictures via the public MediaWiki Search & ImageInfo API
 * with pagination support (`gsroffset`), explicit license allowlist, `Restrictions` screening,
 * and zero injected safety tags.
 */
class WikimediaFeaturedSourceAdapter(
    private val httpClient: HttpClient = DefaultHttpsClient.shared
) : WallpaperSourceAdapter {

    override val sourceId: String = "wikimedia_featured"
    override val sourceName: String = "Wikimedia Commons Featured Photography"
    override val licenseSummary: String = "Public Domain / CC0 / CC BY 2.0–4.0 / CC BY-SA 2.0–4.0 (Verified per asset)"

    override fun withHttpClient(client: HttpClient): WallpaperSourceAdapter =
        WikimediaFeaturedSourceAdapter(client)

    private val searchQueriesByCategory = mapOf(
        SafeCategory.LANDSCAPES_NATURE to "filetype:bitmap incategory:\"Featured_pictures_on_Wikimedia_Commons\" mountain OR lake OR landscape OR valley",
        SafeCategory.SPACE_ASTRONOMY to "filetype:bitmap incategory:\"Featured_pictures_on_Wikimedia_Commons\" astronomy OR aurora OR nebula OR night sky",
        SafeCategory.ARCHITECTURE_CITYSCAPES to "filetype:bitmap incategory:\"Featured_pictures_on_Wikimedia_Commons\" architecture OR bridge OR castle OR skyline",
        SafeCategory.ABSTRACT_PATTERNS to "filetype:bitmap incategory:\"Featured_pictures_on_Wikimedia_Commons\" mineral OR crystal OR ice OR pattern",
        SafeCategory.SCENIC_FINE_ART to "filetype:bitmap incategory:\"Featured_pictures_on_Wikimedia_Commons\" coast OR fjord OR glacier OR sunset",
        SafeCategory.BOTANICAL_FLORA to "filetype:bitmap incategory:\"Featured_pictures_on_Wikimedia_Commons\" botanical OR flora OR flower OR orchard OR leaf OR succulent OR fruit",
        SafeCategory.AERIAL_EARTH to "filetype:bitmap incategory:\"Featured_pictures_on_Wikimedia_Commons\" aerial OR satellite OR \"earth from space\" OR \"river delta\" OR coastline"
    )

    override suspend fun fetchCandidates(
        enabledCategories: Set<SafeCategory>,
        limit: Int,
        offset: Int,
        targetWidthPx: Int
    ): List<CandidateAsset> {
        val results = ArrayList<CandidateAsset>()
        val activeCategories = enabledCategories.filter { it in searchQueriesByCategory.keys }
            .ifEmpty { listOf(SafeCategory.LANDSCAPES_NATURE) }

        val maxBudget = SourceRegistry.get(sourceId)?.maxRequestsPerSync ?: 6
        val spacingMs = SourceRegistry.get(sourceId)?.minRequestSpacingMs ?: 150L
        val maxCategoryQueries = minOf(activeCategories.size, (maxBudget / 3).coerceIn(1, 2))
        val catCursor = (offset / 4).coerceAtLeast(0) % activeCategories.size
        val rotatedCategories = activeCategories.drop(catCursor) + activeCategories.take(catCursor)
        val categoriesToQuery = rotatedCategories.take(maxCategoryQueries)
        val perCategoryLimit = ((limit + categoriesToQuery.size - 1) / categoriesToQuery.size).coerceAtLeast(4)
        val requestedThumbWidth = targetWidthPx.coerceIn(2560, 5120)

        for ((index, cat) in categoriesToQuery.withIndex()) {
            if (index > 0 && spacingMs > 0L) {
                delay(spacingMs)
            }
            val rawQuery = searchQueriesByCategory[cat] ?: continue
            val encodedQuery = URLEncoder.encode(rawQuery, "UTF-8")
            val apiUrl = "https://commons.wikimedia.org/w/api.php?action=query" +
                "&generator=search&gsrnamespace=6&gsrsearch=$encodedQuery" +
                "&gsrlimit=$perCategoryLimit&gsroffset=${offset.coerceAtLeast(0)}" +
                "&prop=imageinfo&iiprop=url|size|extmetadata&iiurlwidth=$requestedThumbWidth&format=json"

            val jsonText = httpClient.fetchTextOrNull(apiUrl) {
                return results.take(limit)
            } ?: continue
            results.addAll(parseResponse(jsonText, cat, requestedThumbWidth))
        }
        return results.take(limit)
    }

    fun parseResponse(
        jsonText: String,
        targetCategory: SafeCategory,
        targetWidthPx: Int = 2560
    ): List<CandidateAsset> {
        val out = ArrayList<CandidateAsset>()
        val root = SimpleJson.parseObject(jsonText) ?: return emptyList()
        val pages = root.obj("query")?.obj("pages") ?: return emptyList()

        for (key in pages.keys()) {
            val page = pages.obj(key) ?: continue
            val rawTitle = page.str("title", "").removePrefix("File:")
            val imageInfoArr = page.arr("imageinfo") ?: continue
            if (imageInfoArr.size == 0) continue

            val info = imageInfoArr.obj(0) ?: continue
            val width = info.int("width", 0).coerceAtLeast(0)
            val height = info.int("height", 0).coerceAtLeast(0)
            val thumbWidth = info.int("thumbwidth", width).coerceAtLeast(0)
            val thumbHeight = info.int("thumbheight", height).coerceAtLeast(0)
            val thumbUrl = info.str("thumburl", "")
            val fullUrl = info.str("url", "")
            val thumbValid = thumbUrl.startsWith("https://") && isSupportedImageUrl(thumbUrl)
            val fullValid = fullUrl.startsWith("https://") &&
                isSupportedImageUrl(fullUrl) &&
                (width == 0 || (width in 1..8192 && height in 1..8192))
            val preferFull = fullValid && thumbWidth in 1 until targetWidthPx && width > thumbWidth
            val orderedUrls = buildList {
                if (preferFull) {
                    add(fullUrl)
                    if (thumbValid) add(thumbUrl)
                } else {
                    if (thumbValid) add(thumbUrl)
                    if (fullValid) add(fullUrl)
                }
            }.distinct()
            if (orderedUrls.isEmpty()) continue
            val downloadUrl = orderedUrls.first()
            val fallbackUrls = orderedUrls.drop(1)
            val descriptionUrl = info.str("descriptionurl", "").takeIf { it.startsWith("https://") }.orEmpty()

            val ext = info.obj("extmetadata")
            // Reject files with non-empty personality, trademark, or insignia restrictions
            val restrictions = ext?.obj("Restrictions")?.str("value", "")?.trim().orEmpty()
            if (restrictions.isNotEmpty()) {
                continue
            }

            val artistHtml = ext?.obj("Artist")?.str("value", "") ?: ""
            val artistClean = stripHtml(artistHtml)
            val extractedCreatorUrl = extractFirstHttpsHref(artistHtml)

            val licenseCode = ext?.obj("License")?.str("value", "") ?: ""
            val licenseShort = ext?.obj("LicenseShortName")?.str("value", "") ?: ""
            val usageTerms = ext?.obj("UsageTerms")?.str("value", "") ?: ""
            val licenseUrl = ext?.obj("LicenseUrl")?.str("value", "")?.trim().orEmpty()
            val descriptionHtml = ext?.obj("ImageDescription")?.str("value", rawTitle) ?: rawTitle
            val descriptionClean = stripHtml(descriptionHtml).ifBlank { rawTitle }

            // Zero injected tags: use only actual categories returned by Wikimedia Commons
            val categoriesRaw = ext?.obj("Categories")?.str("value", "") ?: ""
            val categoryTokens = categoriesRaw.split("|")
                .map { it.removePrefix("Category:").trim() }
                .filter { it.isNotEmpty() }

            val (licenseType, licenseVersion) = mapLicenseAndVersion(
                code = licenseCode,
                shortName = licenseShort,
                usageTerms = usageTerms
            )

            val isPlaceholderCreator = isUnknownCreatorPlaceholder(artistClean)
            val resolvedCreator = when {
                !isPlaceholderCreator && artistClean.isNotBlank() -> artistClean
                licenseType == LicenseType.CC0 || licenseType == LicenseType.PUBLIC_DOMAIN ->
                    "Archival / Public Domain (Wikimedia Commons)"
                else -> "" // Leave empty so L1 rejects CC BY / CC BY-SA with unknown author
            }
            val cleanTitle = rawTitle.substringBeforeLast('.').replace('_', ' ').trim()

            out.add(
                CandidateAsset(
                    id = "wiki-$key",
                    title = cleanTitle.ifBlank { "Wikimedia Featured Photograph" },
                    description = descriptionClean,
                    category = targetCategory,
                    rawCategoriesAndTags = categoryTokens,
                    downloadUrl = downloadUrl,
                    fallbackDownloadUrls = fallbackUrls,
                    provenance = ProvenanceRecord(
                        sourceId = sourceId,
                        sourceName = sourceName,
                        assetId = "wiki-$key",
                        originalPageUrl = descriptionUrl,
                        creatorName = resolvedCreator,
                        creatorPageUrl = extractedCreatorUrl,
                        licenseType = licenseType,
                        attributionText = "${resolvedCreator.ifBlank { "Unknown" }} (${licenseShort.ifBlank { licenseType.label }} via Wikimedia Commons)",
                        retrievedAtEpochMs = System.currentTimeMillis(),
                        originalWidth = width,
                        originalHeight = height,
                        renditionWidth = thumbWidth,
                        renditionHeight = thumbHeight,
                        safetyClassification = SafetyClassification.SAFE,
                        safetyClassificationSource = "Wikimedia Featured Pictures Curated Query ($targetCategory)",
                        sha256Checksum = "",
                        licenseUrl = licenseUrl.ifBlank { licenseType.defaultUrl },
                        licenseVersion = licenseVersion,
                        adapterVersion = SourceRegistry.get(sourceId)?.adapterVersion ?: ProvenanceRecord.DEFAULT_ADAPTER_VERSION,
                        safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION
                    )
                )
            )
        }
        return out
    }

    private fun isSupportedImageUrl(url: String): Boolean =
        SourceAdapterUtils.isSupportedRasterExtension(url)

    private fun isUnknownCreatorPlaceholder(creator: String): Boolean {
        val norm = creator.trim().lowercase(Locale.ROOT)
            .replace(NON_ALNUM_SPACE_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        return norm.isEmpty() ||
            norm == "unknown" ||
            norm == "unknown author" ||
            norm == "unknown photographer" ||
            norm == "unknown artist" ||
            norm == "anonymous" ||
            norm == "n a" ||
            norm == "not provided" ||
            norm.startsWith("unknown author ")
    }

    /**
     * Explicit allowlist mapping for Wikimedia Commons licenses (Article VI §5).
     * Strictly rejects NonCommercial (NC), NoDerivs (ND), CC 1.0, and GFDL-only licenses.
     */
    fun mapLicenseAndVersion(
        code: String,
        shortName: String,
        usageTerms: String
    ): Pair<LicenseType, String> {
        val combined = "$code | $shortName | $usageTerms".lowercase(Locale.ROOT)

        // Reject any NonCommercial or NoDerivatives license immediately
        if (combined.contains("by-nc") ||
            combined.contains("by nc") ||
            combined.contains("noncommercial") ||
            combined.contains("non-commercial") ||
            combined.contains("non commercial") ||
            combined.contains("by-nd") ||
            combined.contains("by nd") ||
            combined.contains("noderiv") ||
            combined.contains("no-deriv") ||
            combined.contains("no deriv")
        ) {
            return LicenseType.UNKNOWN_OR_RESTRICTED to ""
        }

        // Check CC0 1.0
        if (combined.contains("cc0") || combined.contains("cc-zero") || combined.contains("cc zero")) {
            return LicenseType.CC0 to "1.0"
        }

        // Check Public Domain Mark / PD-*
        if (combined.contains("public domain") ||
            combined.contains("pdm") ||
            PD_TAG_REGEX.containsMatchIn(combined)
        ) {
            return LicenseType.PUBLIC_DOMAIN to "1.0"
        }

        // Extract allowed Creative Commons version (2.0, 2.5, 3.0, 4.0 only — reject 1.0)
        val allowedVersion = ALLOWED_CC_VERSIONS.firstOrNull { ver ->
            combined.contains(ver)
        }
        if (allowedVersion == null) {
            return LicenseType.UNKNOWN_OR_RESTRICTED to ""
        }

        return when {
            combined.contains("cc-by-sa") ||
                combined.contains("cc by-sa") ||
                combined.contains("attribution-sharealike") ||
                combined.contains("attribution-share alike") -> LicenseType.CC_BY_SA to allowedVersion

            combined.contains("cc-by") ||
                combined.contains("cc by") ||
                combined.contains("creative commons attribution") -> LicenseType.CC_BY to allowedVersion

            else -> LicenseType.UNKNOWN_OR_RESTRICTED to ""
        }
    }

    private fun extractFirstHttpsHref(html: String): String {
        val match = HTTPS_HREF_REGEX.find(html)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun stripHtml(input: String): String {
        return input.replace(HTML_TAG_REGEX, " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&nbsp;", " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    companion object {
        private val ALLOWED_CC_VERSIONS = listOf("4.0", "3.0", "2.5", "2.0")
        private val NON_ALNUM_SPACE_REGEX = Regex("[^a-z0-9 ]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val PD_TAG_REGEX = Regex("\\bpd([-a-z0-9]+)?\\b")
        private val HTTPS_HREF_REGEX = Regex("""href=["'](https://[^"']+)["']""", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("<[^>]*>")
    }
}

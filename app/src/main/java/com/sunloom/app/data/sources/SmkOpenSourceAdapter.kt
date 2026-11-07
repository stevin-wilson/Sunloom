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
 * Schedule A Admitted Source Adapter: SMK — Statens Museum for Kunst / National Gallery of Denmark (`smk_open`).
 *
 * Queries `api.smk.dk/api/v1/art/search` with `[has_image:true],[public_domain:true]` and `lang=en`,
 * enforces fail-closed `public_domain == true` plus Public Domain Mark 1.0 / CC0 rights verification,
 * builds bounded IIIF renditions on `iip.smk.dk` (`!2560,2560` with `!1686,1686` fallback),
 * filters to 2D visual art media, and injects zero synthetic tags.
 */
class SmkOpenSourceAdapter(
    private val httpClient: HttpClient = DefaultHttpsClient.shared
) : WallpaperSourceAdapter {

    override val sourceId: String = "smk_open"
    override val sourceName: String = "SMK — National Gallery of Denmark (Open)"
    override val licenseSummary: String = "Public Domain Mark 1.0 / CC0 1.0 (SMK Open)"

    override fun withHttpClient(client: HttpClient): WallpaperSourceAdapter =
        SmkOpenSourceAdapter(client)

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
                SafeCategory.BOTANICAL_FLORA
            )
        )
        if (supported.isEmpty()) return emptyList()

        val querySpecs = buildList {
            if (SafeCategory.BOTANICAL_FLORA in supported) {
                add("botanical flowers flora garden" to SafeCategory.BOTANICAL_FLORA)
            }
            if (SafeCategory.SCENIC_FINE_ART in supported || SafeCategory.LANDSCAPES_NATURE in supported) {
                add("landscape mountain valley" to SafeCategory.SCENIC_FINE_ART)
                add("seascape coast ocean" to SafeCategory.SCENIC_FINE_ART)
                add("forest river sunset" to SafeCategory.LANDSCAPES_NATURE)
            }
            if (SafeCategory.ARCHITECTURE_CITYSCAPES in supported) {
                add("architecture courtyard garden" to SafeCategory.ARCHITECTURE_CITYSCAPES)
            }
        }.ifEmpty { listOf("landscape coast sunset" to SafeCategory.SCENIC_FINE_ART) }

        val (query, targetCategory) = querySpecs[(offset / 4).coerceAtLeast(0) % querySpecs.size]
        val encodedKeys = URLEncoder.encode(query, "UTF-8")
        val safeOffset = (offset % OFFSET_SPAN).coerceAtLeast(0)
        val rows = (limit * 2).coerceIn(4, 16)

        val apiUrl = "https://api.smk.dk/api/v1/art/search/" +
            "?keys=$encodedKeys" +
            "&filters=%5Bhas_image%3Atrue%5D%2C%5Bpublic_domain%3Atrue%5D" +
            "&lang=en&offset=$safeOffset&rows=$rows"

        val jsonText = httpClient.fetchTextOrNull(apiUrl) ?: return emptyList()

        return parseResponse(jsonText, targetCategory, targetWidthPx).take(limit)
    }

    fun parseResponse(
        jsonText: String,
        targetCategory: SafeCategory = SafeCategory.SCENIC_FINE_ART,
        targetWidthPx: Int = 2560
    ): List<CandidateAsset> {
        val out = ArrayList<CandidateAsset>()
        val root = SimpleJson.parseObject(jsonText) ?: return emptyList()
        val items = root.arr("items") ?: return emptyList()

        for (i in 0 until items.size) {
            val item = items.obj(i) ?: continue
            val publicDomain = item.bool("public_domain", false)
            val rightsRaw = item.str("rights", "").trim()
            val (licenseType, licenseVersion) = mapRightsToLicense(publicDomain, rightsRaw)
            if (!licenseType.permitsWallpaperUse) continue

            val objectNumber = item.str("object_number", "").trim()
            val rawId = item.str("id", "").trim()
            val stableKey = (objectNumber.ifEmpty { rawId })
                .replace(SAFE_KEY_REGEX, "_")
                .trim('_')
            if (stableKey.isEmpty()) continue

            val title = extractPreferredTitle(item)
            if (title.isEmpty()) continue

            val objectNames = extractStringOrObjectList(item.arr("object_names"), "name")
            if (!isObjectNamesAllowed(objectNames)) continue

            val iiifUrls = buildIiifRenditionChain(
                iiifIdRaw = item.str("image_iiif_id", "").trim(),
                imageNativeRaw = item.str("image_native", "").trim(),
                targetWidthPx = targetWidthPx
            )
            if (iiifUrls.isEmpty()) continue

            // The catalog states the original image's pixel size; use it to skip unsuitable works before downloading.
            val (advertisedW, advertisedH) = item.int("image_width", 0).let { w ->
                val h = item.int("image_height", 0)
                if (w > 0 && h > 0) w to h else 0 to 0
            }

            val creator = extractCreator(item)
            val frontendUrl = item.str("frontend_url", "").trim()
            val canonicalPageUrl = if (frontendUrl.startsWith("https://") &&
                SourceRegistry.isHostAllowed(extractHost(frontendUrl), sourceId)
            ) {
                frontendUrl
            } else {
                "https://open.smk.dk/en/artwork/image/${stableKey.lowercase(Locale.ROOT)}"
            }

            // Zero synthetic tags: collect genuine SMK object_names, techniques, and motif values
            val tags = ArrayList<String>()
            tags.addAll(objectNames)
            tags.addAll(extractStringOrObjectList(item.arr("techniques"), "technique"))
            tags.addAll(extractStringOrObjectList(item.arr("motif"), "name"))

            val resolvedLicenseUrl = if (rightsRaw.startsWith("https://")) {
                rightsRaw
            } else {
                licenseType.defaultUrl
            }

            out.add(
                CandidateAsset(
                    id = "smk-$stableKey",
                    title = title,
                    description = "$title — $creator (SMK Open Public Domain Collection)",
                    category = targetCategory,
                    rawCategoriesAndTags = tags.distinct(),
                    downloadUrl = iiifUrls.first(),
                    fallbackDownloadUrls = iiifUrls.drop(1),
                    provenance = ProvenanceRecord(
                        sourceId = sourceId,
                        sourceName = sourceName,
                        assetId = "smk-$stableKey",
                        originalPageUrl = canonicalPageUrl,
                        creatorName = creator,
                        creatorPageUrl = "",
                        licenseType = licenseType,
                        attributionText = "$creator — $title (${licenseType.label}, SMK — Statens Museum for Kunst)",
                        retrievedAtEpochMs = System.currentTimeMillis(),
                        originalWidth = advertisedW,
                        originalHeight = advertisedH,
                        renditionWidth = 0,
                        renditionHeight = 0,
                        safetyClassification = SafetyClassification.SAFE,
                        safetyClassificationSource = "SMK Open Public Domain Medium Filter (${objectNames.firstOrNull().orEmpty().ifBlank { "2D Artwork" }})",
                        sha256Checksum = "",
                        licenseUrl = resolvedLicenseUrl,
                        licenseVersion = licenseVersion,
                        adapterVersion = SourceRegistry.get(sourceId)?.adapterVersion ?: ProvenanceRecord.DEFAULT_ADAPTER_VERSION,
                        safetyGateVersion = SafetyGate.COMPOSITE_GATE_VERSION
                    )
                )
            )
        }
        return out
    }

    /**
     * Fail-closed SMK license verification:
     * Requires `publicDomain == true`. When `rights` is present, requires explicit CC0 or
     * Public Domain Mark 1.0 / SMK Open public-domain wording, rejecting any copyright/NC/ND notice.
     */
    fun mapRightsToLicense(publicDomain: Boolean, rights: String): Pair<LicenseType, String> {
        if (!publicDomain) return LicenseType.UNKNOWN_OR_RESTRICTED to ""
        val norm = rights.trim().lowercase(Locale.ROOT)
        if (norm.isEmpty() || norm == "null") {
            return LicenseType.PUBLIC_DOMAIN to "1.0"
        }
        if (norm.contains("by-nc") ||
            norm.contains("noncommercial") ||
            norm.contains("by-nd") ||
            norm.contains("noderiv") ||
            norm.contains("copyright") ||
            norm.contains("ophavsret") ||
            norm.contains("all rights reserved") ||
            norm.contains("use-of-images") ||
            norm.contains("incopyright")
        ) {
            return LicenseType.UNKNOWN_OR_RESTRICTED to ""
        }
        if (norm.contains("cc0") || norm.contains("zero/1.0") || norm.contains("cc-zero")) {
            return LicenseType.CC0 to "1.0"
        }
        if (norm.contains("publicdomain/mark") ||
            norm.contains("public domain") ||
            norm.contains("public_domain") ||
            norm.contains("pdm") ||
            norm.contains("smk-open") ||
            norm.contains("smk open")
        ) {
            return LicenseType.PUBLIC_DOMAIN to "1.0"
        }
        return LicenseType.UNKNOWN_OR_RESTRICTED to ""
    }

    private fun extractPreferredTitle(item: SimpleJson.JsonObj): String {
        val titlesArr = item.arr("titles")
        if (titlesArr != null) {
            var firstAny = ""
            for (i in 0 until titlesArr.size) {
                val tObj = titlesArr.obj(i) ?: continue
                val text = tObj.str("title", "").trim()
                if (text.isEmpty() || text == "null") continue
                if (firstAny.isEmpty()) firstAny = text
                val lang = tObj.str("language", "").trim().lowercase(Locale.ROOT)
                if (lang == "en" || lang == "eng") {
                    return text
                }
            }
            if (firstAny.isNotEmpty()) return firstAny
        }
        return item.str("title", "").trim().takeIf { it != "null" }.orEmpty()
    }

    private fun extractCreator(item: SimpleJson.JsonObj): String {
        val prodArr = item.arr("production")
        if (prodArr != null) {
            for (i in 0 until prodArr.size) {
                val pObj = prodArr.obj(i) ?: continue
                val creator = pObj.str("creator", "").trim()
                if (creator.isNotBlank() && creator != "null") {
                    return creator
                }
            }
        }
        return "Unknown Artist (SMK — National Gallery of Denmark)"
    }

    private fun extractStringOrObjectList(arr: SimpleJson.JsonArr?, objectKey: String): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.size) {
            val obj = arr.obj(i)
            val raw = if (obj != null) {
                obj.str(objectKey, "").ifBlank { obj.str("term", "") }
            } else {
                arr.str(i, "")
            }
            val clean = raw.trim()
            if (clean.isNotBlank() && clean != "null") {
                out.add(clean)
            }
        }
        return out
    }

    private fun isObjectNamesAllowed(names: List<String>): Boolean {
        if (names.isEmpty()) return false
        val joined = names.joinToString(" | ").lowercase(Locale.ROOT)
        if (PROHIBITED_OBJECT_TERMS.any { joined.contains(it) }) return false
        return ALLOWED_OBJECT_TERMS.any { joined.contains(it) }
    }

    private fun buildIiifRenditionChain(
        iiifIdRaw: String,
        imageNativeRaw: String,
        targetWidthPx: Int = 2560
    ): List<String> {
        val urls = ArrayList<String>()
        val baseService = when {
            iiifIdRaw.startsWith("https://iip.smk.dk/") ->
                iiifIdRaw.removeSuffix("/info.json").trimEnd('/')
            iiifIdRaw.isNotBlank() && iiifIdRaw != "null" && !iiifIdRaw.startsWith("http") ->
                "https://iip.smk.dk/iiif/jp2/${iiifIdRaw.trimStart('/')}"
            else -> ""
        }
        if (baseService.isNotEmpty() && SourceRegistry.isHostAllowed(extractHost(baseService), sourceId)) {
            if (targetWidthPx > 2560) {
                val bound = targetWidthPx.coerceIn(2560, 4096)
                urls.add("$baseService/full/!$bound,$bound/0/default.jpg")
            }
            urls.add("$baseService/full/!2560,2560/0/default.jpg")
            urls.add("$baseService/full/!1686,1686/0/default.jpg")
        }
        if (SourceAdapterUtils.isSupportedHttpsRasterUrl(imageNativeRaw, sourceId)) {
            urls.add(imageNativeRaw)
        }
        return urls.distinct()
    }

    private fun extractHost(url: String): String =
        SourceAdapterUtils.extractHost(url)

    companion object {
        /** Result window walked before wrapping. */
        private const val OFFSET_SPAN = 240

        private val SAFE_KEY_REGEX = Regex("[^a-zA-Z0-9._-]")
        private val ALLOWED_OBJECT_TERMS = listOf(
            "painting",
            "maleri",
            "drawing",
            "tegning",
            "print",
            "grafik",
            "photograph",
            "fotografi",
            "watercolor",
            "watercolour",
            "akvarel",
            "gouache",
            "pastel",
            "etching",
            "lithograph",
            "woodcut",
            "aquatint",
            "engraving"
        )

        private val PROHIBITED_OBJECT_TERMS = listOf(
            "sculpture",
            "skulptur",
            "statue",
            "bust",
            "buste",
            "figurine",
            "weapon",
            "våben",
            "vaben",
            "armor",
            "rustning",
            "coin",
            "mønt",
            "medal",
            "medalje",
            "death mask",
            "dødsmaske",
            "anatomical"
        )
    }
}

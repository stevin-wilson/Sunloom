package com.sunloom.app.data.sources

import com.sunloom.app.domain.model.SourceIds
import com.sunloom.app.domain.model.SafeCategory

/**
 * Article VI §§3, 4, 7, 8, 9 & Schedule A (Constitution v0.5):
 * Single source of truth for admitted image sources, allowed metadata/image hosts,
 * per-sync request budgets, and safety signal profiles.
 */
data class SourceRegistration(
    val sourceId: String,
    val sourceName: String,
    val plainDescription: String,
    val licenseSummary: String,
    val termsUrl: String,
    val reviewedDate: String,
    val reviewer: String,
    val isAdmitted: Boolean,
    val isBuiltInOffline: Boolean = false,
    val supportsDarkPhotography: Boolean = false,
    /** Categories this source's queries can supply (empty for the offline sources); see SourceCoverageMatrixTest. */
    val servedCategories: Set<SafeCategory> = emptySet(),
    val adapterVersion: String = "1.4.0",
    val allowedHosts: Set<String> = emptySet(),
    val maxRequestsPerSync: Int = 6,
    val minRequestSpacingMs: Long = 150L,
    val maxRenditionLongEdgePx: Int = Int.MAX_VALUE,
    val publishedRateLimitNote: String = "",
    val safetySignalsSummary: String = ""
)

object SourceRegistry {

    const val REGISTRY_VERSION = "2026.11.04-v2"
    const val AUTO_DISABLE_FAILURE_THRESHOLD = 5

    private val REGISTRATIONS: Map<String, SourceRegistration> = listOf(
        SourceRegistration(
            sourceId = SourceIds.SEED_RESERVE,
            sourceName = "Sunloom On-Device Procedural Reserve",
            plainDescription = "Generated on device by Sunloom at your display's native resolution (CC0 1.0)",
            licenseSummary = "CC0 1.0 Public Domain (Generated on device)",
            termsUrl = "",
            reviewedDate = "2026-11-04",
            reviewer = "Sunloom Maintainer",
            isAdmitted = true,
            isBuiltInOffline = true,
            allowedHosts = emptySet(),
            maxRequestsPerSync = 0,
            minRequestSpacingMs = 0L,
            publishedRateLimitNote = "Offline procedural synthesis",
            safetySignalsSummary = "Deterministic procedural horizon and ridge compositions"
        ),
        SourceRegistration(
            sourceId = "wikimedia_featured",
            servedCategories = setOf(
                SafeCategory.LANDSCAPES_NATURE,
                SafeCategory.SPACE_ASTRONOMY,
                SafeCategory.ARCHITECTURE_CITYSCAPES,
                SafeCategory.ABSTRACT_PATTERNS,
                SafeCategory.SCENIC_FINE_ART,
                SafeCategory.BOTANICAL_FLORA,
                SafeCategory.AERIAL_EARTH
            ),
            sourceName = "Wikimedia Commons Featured Photography",
            plainDescription = "Key-free MediaWiki API — Curated Featured Pictures (Landscapes, Astronomy & Architecture)",
            licenseSummary = "Public Domain / CC0 / CC BY 2.0–4.0 / CC BY-SA 2.0–4.0 (Verified per asset)",
            termsUrl = "https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use",
            reviewedDate = "2026-11-04",
            reviewer = "Sunloom Maintainer",
            isAdmitted = true,
            supportsDarkPhotography = true,
            allowedHosts = setOf("commons.wikimedia.org", "upload.wikimedia.org"),
            maxRequestsPerSync = 6,
            minRequestSpacingMs = 150L,
            publishedRateLimitNote = "200 req/s anonymous limit; Sunloom <= 6 req/sync",
            safetySignalsSummary = "Featured Pictures curation + Restrictions check + L2 Tier 1/2 denylist"
        ),
        SourceRegistration(
            sourceId = "nasa_library",
            servedCategories = setOf(
                SafeCategory.SPACE_ASTRONOMY,
                SafeCategory.AERIAL_EARTH,
                SafeCategory.LANDSCAPES_NATURE
            ),
            sourceName = "NASA Space & Earth Image Library",
            plainDescription = "Key-free images-api.nasa.gov — Public Domain daylight Earth & deep-space photography",
            licenseSummary = "Public Domain (NASA / US Government Works)",
            termsUrl = "https://www.nasa.gov/multimedia/guidelines/",
            reviewedDate = "2026-11-04",
            reviewer = "Sunloom Maintainer",
            isAdmitted = true,
            supportsDarkPhotography = true,
            allowedHosts = setOf("images-api.nasa.gov", "images-assets.nasa.gov", "images.nasa.gov"),
            maxRequestsPerSync = 10,
            minRequestSpacingMs = 150L,
            publishedRateLimitNote = "1,000 req/hr anonymous limit; Sunloom <= 10 req/sync",
            safetySignalsSummary = "JPL/JSC/GSFC only, no HQ ids or third-party credits + L2 denylist, press-event and map/rendering checks"
        ),
        SourceRegistration(
            sourceId = "artic_cc0",
            servedCategories = setOf(
                SafeCategory.LANDSCAPES_NATURE,
                SafeCategory.SCENIC_FINE_ART,
                SafeCategory.BOTANICAL_FLORA
            ),
            sourceName = "Art Institute of Chicago Open Access (Landscapes)",
            plainDescription = "Key-free CC0 API — Public domain landscape & architectural works",
            licenseSummary = "CC0 1.0 Universal (Public Domain Dedication)",
            termsUrl = "https://www.artic.edu/open-access/public-api",
            reviewedDate = "2026-11-04",
            reviewer = "Sunloom Maintainer",
            isAdmitted = true,
            allowedHosts = setOf("api.artic.edu", "www.artic.edu"),
            maxRequestsPerSync = 5,
            minRequestSpacingMs = 150L,
            maxRenditionLongEdgePx = 1686,
            publishedRateLimitNote = "60 req/min anonymous limit; Sunloom <= 5 req/sync",
            safetySignalsSummary = "Fail-closed is_public_domain + non-figurative classification filter + L2 Tier 1/2 denylist"
        ),
        SourceRegistration(
            sourceId = "met_museum_cc0",
            servedCategories = setOf(
                SafeCategory.LANDSCAPES_NATURE,
                SafeCategory.SCENIC_FINE_ART,
                SafeCategory.BOTANICAL_FLORA,
                SafeCategory.ARCHITECTURE_CITYSCAPES
            ),
            sourceName = "The Met Open Access (CC0)",
            plainDescription = "Key-free collectionapi.metmuseum.org — Public domain landscapes, seascapes & fine art",
            licenseSummary = "CC0 1.0 Public Domain (The Metropolitan Museum of Art)",
            termsUrl = "https://www.metmuseum.org/policies-and-documents/open-access",
            reviewedDate = "2026-11-04",
            reviewer = "Sunloom Maintainer",
            isAdmitted = true,
            allowedHosts = setOf("collectionapi.metmuseum.org", "images.metmuseum.org", "www.metmuseum.org"),
            maxRequestsPerSync = 7,
            minRequestSpacingMs = 150L,
            publishedRateLimitNote = "80 req/s anonymous limit; Sunloom <= 7 req/sync with 150ms spacing",
            safetySignalsSummary = "Fail-closed isPublicDomain + department/classification allowlist + L2 Tier 1/2 denylist"
        ),
        SourceRegistration(
            sourceId = "cma_cc0",
            servedCategories = setOf(
                SafeCategory.LANDSCAPES_NATURE,
                SafeCategory.SCENIC_FINE_ART,
                SafeCategory.BOTANICAL_FLORA,
                SafeCategory.ARCHITECTURE_CITYSCAPES,
                SafeCategory.ABSTRACT_PATTERNS
            ),
            sourceName = "Cleveland Museum of Art Open Access (CC0)",
            plainDescription = "Key-free openaccess-api.clevelandart.org — CC0 landscapes, seascapes, architecture & botanical art",
            licenseSummary = "CC0 1.0 Universal (Public Domain Dedication)",
            termsUrl = "https://www.clevelandart.org/open-access",
            reviewedDate = "2026-11-04",
            reviewer = "Sunloom Maintainer",
            isAdmitted = true,
            allowedHosts = setOf(
                "openaccess-api.clevelandart.org",
                "openaccess-cdn.clevelandart.org",
                "www.clevelandart.org",
                "clevelandart.org"
            ),
            maxRequestsPerSync = 5,
            minRequestSpacingMs = 250L,
            publishedRateLimitNote = "No published anonymous limit; conservative Sunloom budget <= 5 req/sync, 250ms spacing",
            safetySignalsSummary = "Fail-closed share_license_status==CC0 + 2D medium type allowlist + L2 Tier 1/2 denylist"
        ),
        SourceRegistration(
            sourceId = "smk_open",
            servedCategories = setOf(
                SafeCategory.LANDSCAPES_NATURE,
                SafeCategory.SCENIC_FINE_ART,
                SafeCategory.BOTANICAL_FLORA,
                SafeCategory.ARCHITECTURE_CITYSCAPES
            ),
            sourceName = "SMK — National Gallery of Denmark (Open)",
            plainDescription = "Key-free api.smk.dk — Public domain Golden Age seascapes, Nordic landscapes & architecture",
            licenseSummary = "Public Domain Mark 1.0 / CC0 1.0 (SMK Open)",
            termsUrl = "https://www.smk.dk/en/article/smk-open",
            reviewedDate = "2026-11-04",
            reviewer = "Sunloom Maintainer",
            isAdmitted = true,
            allowedHosts = setOf("api.smk.dk", "iip.smk.dk", "open.smk.dk", "www.smk.dk"),
            maxRequestsPerSync = 5,
            minRequestSpacingMs = 250L,
            publishedRateLimitNote = "No published anonymous limit; conservative Sunloom budget <= 5 req/sync, 250ms spacing",
            safetySignalsSummary = "Fail-closed public_domain==true & PDM/CC0 rights verification + English/Nordic L2 denylist"
        ),
        SourceRegistration(
            sourceId = SourceIds.USER_LOCAL,
            sourceName = "User Local Photos",
            plainDescription = "Direct user-selected photos via Android Photo Picker",
            licenseSummary = "User-Provided Local File (Exempt under Article VI §10)",
            termsUrl = "",
            reviewedDate = "2026-11-04",
            reviewer = "Sunloom Maintainer",
            isAdmitted = true,
            isBuiltInOffline = true,
            allowedHosts = emptySet(),
            maxRequestsPerSync = 0,
            minRequestSpacingMs = 0L,
            publishedRateLimitNote = "Local Photo Picker / SAF stream",
            safetySignalsSummary = "Quarantine + L2–L5 structural, blocklist, and rendition verification"
        ),
        SourceRegistration(
            sourceId = "openverse",
            sourceName = "Openverse Institutional Catalog (Candidate — Disabled)",
            plainDescription = "Candidate aggregator (api.openverse.org/v1) kept disabled pending institutional source pinning",
            licenseSummary = "CC0 / PDM / CC BY / CC BY-SA",
            termsUrl = "https://docs.openverse.org/terms_of_service.html",
            reviewedDate = "2026-11-04",
            reviewer = "Sunloom Maintainer",
            isAdmitted = false,
            allowedHosts = emptySet(),
            publishedRateLimitNote = "20 req/min, 200 req/day anonymous limit; requires institutional source pinning"
        )
    ).associateBy { it.sourceId }

    fun get(sourceId: String): SourceRegistration? = REGISTRATIONS[sourceId]

    fun isSourceAdmitted(sourceId: String): Boolean = REGISTRATIONS[sourceId]?.isAdmitted == true

    fun admittedSources(): List<SourceRegistration> =
        REGISTRATIONS.values.filter { it.isAdmitted }

    fun allAllowedHosts(): Set<String> =
        REGISTRATIONS.values
            .filter { it.isAdmitted }
            .flatMap { it.allowedHosts }
            .toSet()

    fun isHostAllowed(host: String, sourceId: String? = null): Boolean {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return false
        val permittedHosts = if (sourceId != null) {
            val reg = REGISTRATIONS[sourceId] ?: return false
            if (!reg.isAdmitted) return false
            reg.allowedHosts
        } else {
            allAllowedHosts()
        }
        return permittedHosts.any { allowed ->
            normalized == allowed
        }
    }
}

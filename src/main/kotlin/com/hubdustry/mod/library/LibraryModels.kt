package com.hubdustry.mod.library

enum class ContentKind { SCHEMATIC, MAP }
enum class LibraryState { DRAFT, PUBLISHED, HIDDEN, ARCHIVED }
enum class LibrarySort { RECOMMENDED, RECENT, OLDEST, NAME }
enum class RankTier { NEW, COMMUNITY, QUALITY, EXCELLENT }

data class RatingSummary(
    val score: Double, val tier: RankTier, val ratingCount: Long,
    val expertCount: Long, val version: String
)

data class LibraryCapabilities(val actorId: String? = null, val names: Set<String> = emptySet()) {
    val canEdit get() = "library.edit.own" in names
    val canSubmit get() = "library.submit" in names
    val canModerate get() = "library.moderate" in names
    val canRate get() = "library.rate" in names
}

data class Attribution(
    val creditName: String?, val authorId: String?, val identityVerified: Boolean,
    val sourceUrl: String?, val createdAt: Long?, val verified: Boolean = false,
    val licenseNotice: String? = null, val sourceHistory: SourceHistory? = null
)

data class SourceHistory(val firstCommit: String, val firstPath: String, val firstCommittedAt: Long, val importedAt: Long)

data class LibraryItem(
    val id: String, val kind: ContentKind, val name: String, val description: String,
    val tags: List<String>, val uploaderId: String?, val ownerId: String?, val attribution: Attribution?,
    val revision: Long, val state: LibraryState, val sha256: String, val sizeBytes: Long,
    val assetId: String, val previewRequestId: String, val artifactId: String?,
    val width: Int?, val height: Int?, val createdAt: Long?, val updatedAt: Long?,
    val capabilities: LibraryCapabilities, val rank: RatingSummary?
)

data class LibraryPage(val items: List<LibraryItem>, val total: Int, val offset: Int, val limit: Int)
data class SystemTag(val id: String, val label: String)
data class TagCategory(val id: String, val label: String, val multiple: Boolean, val tags: List<SystemTag>)
data class TagCatalog(val kind: ContentKind, val categories: List<TagCategory>)

data class LibraryQuery(
    val kind: ContentKind? = null, val text: String = "", val tags: List<String> = emptyList(),
    val ownerMe: Boolean = false, val state: LibraryState? = null,
    val sort: LibrarySort = LibrarySort.RECOMMENDED, val minRank: RankTier? = null,
    val offset: Int = 0, val limit: Int = 24
) {
    init { Bounds.requireQuery(offset, limit, text, tags) }
}

object Bounds {
    const val MAX_TEXT = 160
    const val MAX_DESCRIPTION = 8000
    const val MAX_TAGS = 12
    const val MAX_TAG = 48
    const val MAX_SOURCE_BYTES = 3_000_000
    const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024

    fun requireQuery(offset: Int, limit: Int, text: String, tags: List<String>) {
        require(offset in 0..10_000) { "offset out of bounds" }
        require(limit in 1..50) { "limit out of bounds" }
        require(text.length <= MAX_TEXT) { "query too long" }
        require(tags.size <= MAX_TAGS && tags.all { it.isNotBlank() && it.length <= MAX_TAG }) { "tags out of bounds" }
    }

    fun requireMetadata(name: String, description: String, tags: List<String>) {
        require(name.isNotBlank() && name.length <= 160) { "name out of bounds" }
        require(description.length <= MAX_DESCRIPTION) { "description out of bounds" }
        require(tags.size <= MAX_TAGS && tags.all { it.isNotBlank() && it.length <= MAX_TAG }) { "tags out of bounds" }
    }
}

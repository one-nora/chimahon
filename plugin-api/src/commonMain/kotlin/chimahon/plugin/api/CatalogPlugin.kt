package chimahon.plugin.api

import kotlinx.serialization.Serializable

interface CatalogPlugin : Plugin {
    val catalogType: CatalogType
    val catalogInfo: CatalogInfo
    suspend fun getSources(): List<CatalogSource>
    suspend fun search(query: String, filters: SearchFilters = SearchFilters()): CatalogResult<List<CatalogItem>>
    suspend fun getDetails(sourceId: String, contentId: String): CatalogResult<CatalogItemDetails>
    suspend fun getChapters(sourceId: String, contentId: String): CatalogResult<List<CatalogChapter>>
    suspend fun getChapterContent(sourceId: String, contentId: String, chapterId: String): CatalogResult<ChapterContent>
    fun supportsFeature(feature: CatalogFeature): Boolean
    suspend fun getPopular(sourceId: String, page: Int = 1): CatalogResult<List<CatalogItem>>
    suspend fun getLatest(sourceId: String, page: Int = 1): CatalogResult<List<CatalogItem>>
    suspend fun refresh(): CatalogResult<Unit>
}

@Serializable
enum class CatalogType {
    LNREADER,
    USER_SOURCE,
    TACHIYOMI,
    CUSTOM
}

@Serializable
data class CatalogInfo(
    val name: String,
    val description: String,
    val iconUrl: String? = null,
    val website: String? = null,
    val languages: List<String> = emptyList(),
    val contentTypes: List<ContentType> = listOf(ContentType.NOVEL),
    val requiresLogin: Boolean = false
)

@Serializable
enum class ContentType {
    NOVEL, MANGA, COMIC, LIGHT_NOVEL, WEB_NOVEL, FANFICTION
}

@Serializable
data class CatalogSource(
    val id: String,
    val name: String,
    val language: String,
    val iconUrl: String? = null,
    val baseUrl: String,
    val isNsfw: Boolean = false,
    val version: Int = 1,
    val features: List<CatalogFeature> = emptyList()
)

@Serializable
enum class CatalogFeature {
    SEARCH, POPULAR, LATEST, FILTERS, LOGIN, CLOUDFLARE_BYPASS, DOWNLOAD, TRACKING, COMMENTS
}

@Serializable
data class SearchFilters(
    val sourceIds: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val status: ContentStatus? = null,
    val contentType: ContentType? = null,
    val language: String? = null,
    val sortBy: SortOption = SortOption.RELEVANCE,
    val page: Int = 1,
    val pageSize: Int = 20
)

@Serializable
enum class ContentStatus {
    ONGOING, COMPLETED, HIATUS, CANCELLED, UNKNOWN
}

@Serializable
enum class SortOption {
    RELEVANCE, LATEST, POPULAR, RATING, TITLE, CHAPTERS
}

@Serializable
data class CatalogItem(
    val id: String,
    val sourceId: String,
    val title: String,
    val coverUrl: String? = null,
    val description: String? = null,
    val author: String? = null,
    val status: ContentStatus = ContentStatus.UNKNOWN,
    val genres: List<String> = emptyList(),
    val rating: Float? = null,
    val chapterCount: Int? = null,
    val lastUpdated: Long? = null
)

@Serializable
data class CatalogItemDetails(
    val item: CatalogItem,
    val fullDescription: String? = null,
    val alternativeTitles: List<String> = emptyList(),
    val artist: String? = null,
    val originalLanguage: String? = null,
    val year: Int? = null,
    val relatedIds: List<String> = emptyList(),
    val externalLinks: Map<String, String> = emptyMap()
)

@Serializable
data class CatalogChapter(
    val id: String,
    val title: String,
    val number: Float,
    val volume: Int? = null,
    val dateUpload: Long? = null,
    val scanlator: String? = null,
    val url: String? = null
)

@Serializable
data class ChapterContent(
    val chapterId: String,
    val text: String? = null,
    val imageUrls: List<String> = emptyList(),
    val type: ChapterContentType
)

@Serializable
enum class ChapterContentType {
    TEXT, IMAGES, MIXED
}

sealed class CatalogResult<out T> {
    data class Success<T>(val data: T) : CatalogResult<T>()
    data class Error(val error: CatalogError) : CatalogResult<Nothing>()
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }
    inline fun <R> map(transform: (T) -> R): CatalogResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }
}

@Serializable
sealed class CatalogError {
    data class NetworkError(val message: String) : CatalogError()
    data class ParseError(val message: String) : CatalogError()
    data class SourceNotFound(val sourceId: String) : CatalogError()
    data class ContentNotFound(val contentId: String) : CatalogError()
    data class AuthRequired(val message: String) : CatalogError()
    data class Unknown(val message: String) : CatalogError()
}

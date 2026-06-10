package chimahon.source.komga

import chimahon.novel.model.NovelServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Response
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KomgaSource(
    private val server: NovelServer,
) : CatalogueSource {

    private val network: NetworkHelper by lazy { Injekt.get<NetworkHelper>() }
    private val json = Json { ignoreUnknownKeys = true }

    override val id: Long by lazy { generateId(server) }
    override val name: String get() = server.name
    override val lang: String get() = "all"
    override val supportsLatest: Boolean get() = true

    private val baseUrl: String get() = server.baseUrl.trimEnd('/')

    private val client: OkHttpClient
        get() {
            val builder = network.client.newBuilder()
            if (server.requiresAuth()) {
                if (!server.apiKey.isNullOrBlank()) {
                    builder.addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("X-API-Key", server.apiKey!!).build())
                    }
                } else if (!server.username.isNullOrBlank()) {
                    builder.addInterceptor { chain ->
                        val credential = Credentials.basic(server.username!!, "")
                        chain.proceed(chain.request().newBuilder().header("Authorization", credential).build())
                    }
                }
            }
            return builder.build()
        }

    // ===== Manga CatalogueSource Implementation =====

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.newCall(GET("$baseUrl/api/v1/series?page=${page - 1}&size=20&sort=metadata.titleSort,asc&media_status=READY&deleted=false")).awaitSuccess()
        return parseMangaPage(response)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val response = client.newCall(GET("$baseUrl/api/v1/series?search=$query&page=${page - 1}&size=20&media_status=READY&deleted=false")).awaitSuccess()
        return parseMangaPage(response)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.newCall(GET("$baseUrl/api/v1/series?page=${page - 1}&size=20&sort=lastModifiedDate,desc&media_status=READY&deleted=false")).awaitSuccess()
        return parseMangaPage(response)
    }

    override fun getFilterList() = FilterList()

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val seriesId = manga.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/v1/series/$seriesId")).awaitSuccess()
        return parseMangaDetails(response)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val seriesId = manga.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/v1/series/$seriesId/books?unpaged=true&media_status=READY&deleted=false")).awaitSuccess()
        return parseMangaChapters(response)
    }

    override suspend fun getPageList(chapter: SChapter): List<eu.kanade.tachiyomi.source.model.Page> {
        val bookId = chapter.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/v1/books/$bookId/pages")).awaitSuccess()
        val pages = json.decodeFromString<List<KomgaPageDto>>(response.body.string())
        return pages.map { page ->
            eu.kanade.tachiyomi.source.model.Page(
                index = page.number - 1,
                imageUrl = "$baseUrl/api/v1/books/$bookId/pages/${page.number}/media",
            )
        }
    }

    // ===== Parsers =====

    private fun parseMangaPage(response: Response): MangasPage {
        val data = json.decodeFromString<PageWrapperDto<KomgaSeriesDto>>(response.body.string())
        val mangas = data.content.mapNotNull { it.toSManga(baseUrl) }
        return MangasPage(mangas, !data.last)
    }

    private fun parseMangaDetails(response: Response): SManga {
        val series = json.decodeFromString<KomgaSeriesDto>(response.body.string())
        return series.toSManga(baseUrl)
    }

    private fun parseMangaChapters(response: Response): List<SChapter> {
        val data = json.decodeFromString<PageWrapperDto<KomgaBookDto>>(response.body.string())
        return data.content
            .map { book ->
                SChapter.create().apply {
                    url = "$baseUrl/api/v1/books/${book.id}"
                    name = book.metadata.title.ifBlank { book.name }
                    chapter_number = book.metadata.numberSort
                    date_upload = book.metadata.releaseDate?.let { parseDate(it) }
                        ?: book.created?.let { parseDateTime(it) }
                        ?: parseDateTime(book.fileLastModified)
                    scanlator = book.metadata.authors.firstOrNull { it.role == "translator" }?.name
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    private fun parseDate(dateStr: String): Long = runCatching { threadLocalDate.get()!!.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)

    private fun parseDateTime(dateStr: String): Long = runCatching { threadLocalDateTime.get()!!.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)

    companion object {
        private val threadLocalDate = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
        private val threadLocalDateTime = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        fun generateId(server: NovelServer): Long {
            val key = "komga:${server.baseUrl}:${server.name}"
            val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }
    }
}

@Serializable
data class PageWrapperDto<T>(
    val content: List<T>,
    val empty: Boolean = false,
    val first: Boolean = false,
    val last: Boolean,
    val number: Long = 0,
    val numberOfElements: Long = 0,
    val size: Long = 0,
    val totalElements: Long = 0,
    val totalPages: Long = 0,
)

@Serializable
data class KomgaSeriesDto(
    val id: String,
    val libraryId: String,
    val name: String,
    val created: String? = null,
    val lastModified: String? = null,
    val fileLastModified: String = "",
    val booksCount: Int = 0,
    val metadata: KomgaSeriesMetadataDto,
    val booksMetadata: KomgaBookMetadataAggregationDto = KomgaBookMetadataAggregationDto(),
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        title = metadata.title
        url = "$baseUrl/api/v1/series/$id"
        thumbnail_url = "$url/thumbnail"
        status = when {
            metadata.status == "ENDED" && metadata.totalBookCount != null && booksCount < metadata.totalBookCount -> SManga.PUBLISHING_FINISHED
            metadata.status == "ENDED" -> SManga.COMPLETED
            metadata.status == "ONGOING" -> SManga.ONGOING
            metadata.status == "ABANDONED" -> SManga.CANCELLED
            metadata.status == "HIATUS" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        genre = (metadata.genres + booksMetadata.tags).sorted().distinct().joinToString(", ")
        description = metadata.summary.ifBlank { booksMetadata.summary }
        author = booksMetadata.authors.filter { it.role == "writer" }.map { it.name }.distinct().joinToString()
        artist = booksMetadata.authors.filter { it.role == "penciller" }.map { it.name }.distinct().joinToString()
    }

}

@Serializable
data class KomgaSeriesMetadataDto(
    val status: String = "",
    val created: String? = null,
    val lastModified: String? = null,
    val title: String = "",
    val titleSort: String = "",
    val summary: String = "",
    val summaryLock: Boolean = false,
    val readingDirection: String = "",
    val readingDirectionLock: Boolean = false,
    val publisher: String = "",
    val publisherLock: Boolean = false,
    val ageRating: Int? = null,
    val ageRatingLock: Boolean = false,
    val language: String = "",
    val languageLock: Boolean = false,
    val genres: Set<String> = emptySet(),
    val genresLock: Boolean = false,
    val tags: Set<String> = emptySet(),
    val tagsLock: Boolean = false,
    val totalBookCount: Int? = null,
)

@Serializable
data class KomgaBookMetadataAggregationDto(
    val authors: List<KomgaAuthorDto> = emptyList(),
    val tags: Set<String> = emptySet(),
    val releaseDate: String? = null,
    val summary: String = "",
    val summaryNumber: String = "",
    val created: String = "",
    val lastModified: String = "",
)

@Serializable
data class KomgaBookDto(
    val id: String,
    val seriesId: String,
    val seriesTitle: String,
    val name: String,
    val number: Float = 0f,
    val created: String? = null,
    val lastModified: String? = null,
    val fileLastModified: String = "",
    val sizeBytes: Long = 0,
    val size: String = "",
    val media: KomgaMediaDto? = null,
    val metadata: KomgaBookMetadataDto = KomgaBookMetadataDto(),
)

@Serializable
data class KomgaMediaDto(
    val status: String = "",
    val mediaType: String = "",
    val pagesCount: Int = 0,
    val mediaProfile: String = "DIVINA",
    val epubDivinaCompatible: Boolean = false,
)

@Serializable
data class KomgaBookMetadataDto(
    val title: String = "",
    val titleLock: Boolean = false,
    val summary: String = "",
    val summaryLock: Boolean = false,
    val number: String = "",
    val numberLock: Boolean = false,
    val numberSort: Float = 0f,
    val numberSortLock: Boolean = false,
    val releaseDate: String? = null,
    val releaseDateLock: Boolean = false,
    val authors: List<KomgaAuthorDto> = emptyList(),
    val authorsLock: Boolean = false,
    val tags: Set<String> = emptySet(),
    val tagsLock: Boolean = false,
)

@Serializable
data class KomgaAuthorDto(
    val name: String,
    val role: String,
)

@Serializable
data class KomgaPageDto(
    val number: Int,
    val fileName: String = "",
    val mediaType: String = "",
)

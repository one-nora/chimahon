package chimahon.source.kavita

import chimahon.novel.model.NovelServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KavitaSource(
    private val server: NovelServer,
) : CatalogueSource {

    private val network: NetworkHelper by lazy { Injekt.get<NetworkHelper>() }
    private val json = Json { ignoreUnknownKeys = true }

    override val id: Long by lazy { generateId(server) }
    override val name: String get() = server.name
    override val lang: String get() = "all"
    override val supportsLatest: Boolean get() = true

    private val baseUrl: String get() = server.baseUrl.trimEnd('/')
    private var authToken: String? = null

    private val authHeaders: Headers
        get() = Headers.Builder().apply {
            if (!server.apiKey.isNullOrBlank()) {
                add("Authorization", "Bearer ${server.apiKey}")
            } else if (authToken != null) {
                add("Authorization", "Bearer $authToken")
            }
        }.build()

    private val client: OkHttpClient
        get() {
            val builder = network.client.newBuilder()
            if (!server.apiKey.isNullOrBlank()) {
                builder.addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${server.apiKey}").build())
                }
            } else if (!server.username.isNullOrBlank()) {
                builder.addInterceptor { chain ->
                    val req = chain.request()
                    if (req.url.encodedPath.endsWith("/login", ignoreCase = true)) {
                        chain.proceed(req)
                    } else {
                        val token = authToken ?: runBlocking { login() }
                        chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
                    }
                }
            }
            return builder.build()
        }

    private suspend fun login(): String {
        val body = buildJsonObject {
            put("username", server.username ?: "")
            put("password", server.apiKey ?: "")
        }.toString()
        val response = network.client.newCall(
            POST(
                "$baseUrl/api/Account/login",
                headers = Headers.Builder().add("Content-Type", "application/json").build(),
                body = body.toRequestBody("application/json".toMediaType()),
            ),
        ).awaitSuccess()
        val token = json.decodeFromString<KavitaLoginResponse>(response.body.string()).token
        authToken = token
        return token
    }

    // ===== Manga CatalogueSource Implementation =====

    override suspend fun getPopularManga(page: Int): MangasPage {
        val filter = KavitaFilterV2Dto(
            sortOptions = KavitaSortOptions(sortField = 8, isAscending = false),
            statements = mutableListOf(),
        )
        val payload = json.encodeToJsonElement(filter).toString()
        val response = client.newCall(
            POST(
                "$baseUrl/api/Series/all-v2?pageNumber=$page&pageSize=20",
                headers = authHeaders,
                body = payload.toRequestBody("application/json".toMediaType()),
            ),
        ).awaitSuccess()
        return parseMangaPage(response)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val filter = KavitaFilterV2Dto(
            sortOptions = KavitaSortOptions(sortField = 1, isAscending = true),
            statements = mutableListOf(),
        )
        if (query.isNotBlank()) {
            filter.statements.add(KavitaFilterStatementDto(comparison = 7, field = 1, value = query))
        }
        val payload = json.encodeToJsonElement(filter).toString()
        val response = client.newCall(
            POST(
                "$baseUrl/api/Series/all-v2?pageNumber=$page&pageSize=20",
                headers = authHeaders,
                body = payload.toRequestBody("application/json".toMediaType()),
            ),
        ).awaitSuccess()
        return parseMangaPage(response)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val filter = KavitaFilterV2Dto(
            sortOptions = KavitaSortOptions(sortField = 4, isAscending = false),
            statements = mutableListOf(),
        )
        val payload = json.encodeToJsonElement(filter).toString()
        val response = client.newCall(
            POST(
                "$baseUrl/api/Series/all-v2?pageNumber=$page&pageSize=20",
                headers = authHeaders,
                body = payload.toRequestBody("application/json".toMediaType()),
            ),
        ).awaitSuccess()
        return parseMangaPage(response)
    }

    override fun getFilterList() = FilterList()

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val seriesId = manga.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/series/metadata?seriesId=$seriesId")).awaitSuccess()
        return parseMangaDetails(response).let { details ->
            if (details.title.isBlank() && manga.title.isNotBlank()) {
                details.copy().also { it.title = manga.title }
            } else {
                details
            }
        }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val seriesId = manga.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/Series/$seriesId/Volumes")).awaitSuccess()
        val volumes = json.decodeFromString<List<KavitaVolumeDto>>(response.body.string())
        return volumes.flatMap { volume ->
            volume.chapters.map { chapter ->
                SChapter.create().apply {
                    url = "$baseUrl/api/Chapter/${chapter.id}"
                    name = chapter.titleName?.takeIf { it.isNotBlank() } ?: chapter.range ?: "Chapter ${chapter.minNumber}"
                    chapter_number = chapter.minNumber.toFloat()
                    date_upload = chapter.created?.let { parseDateTime(it) } ?: 0L
                }
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<eu.kanade.tachiyomi.source.model.Page> {
        val chapterId = chapter.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/Chapter/$chapterId/Pages")).awaitSuccess()
        val pages = json.decodeFromString<List<KavitaPageDto>>(response.body.string())
        return pages.map { page ->
            eu.kanade.tachiyomi.source.model.Page(
                index = page.number - 1,
                imageUrl = "$baseUrl/api/Reader/Extract?chapterId=$chapterId&page=${page.number}",
            )
        }
    }

    // ===== Parsers =====

    private fun parseMangaPage(response: Response): MangasPage {
        val seriesList = json.decodeFromString<List<KavitaSeriesDto>>(response.body.string())
        return MangasPage(seriesList.map { it.toSManga(baseUrl) }, seriesList.size >= 20)
    }

    private fun parseMangaDetails(response: Response): SManga {
        val detail = json.decodeFromString<KavitaSeriesDetailPlusDto>(response.body.string())
        return detail.toSManga(baseUrl)
    }

    private fun parseDateTime(dateStr: String): Long = runCatching { threadLocalFormatter.get()!!.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)

    companion object {
        private val threadLocalFormatter = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        fun generateId(server: NovelServer): Long {
            val key = "kavita:${server.baseUrl}:${server.name}"
            val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }
    }
}

// ===== DTOs =====

@Serializable
data class KavitaLoginResponse(val token: String)

@Serializable
data class KavitaLibraryDto(
    val id: Int,
    val name: String,
    val type: Int,
)

@Serializable
data class KavitaSeriesDto(
    val id: Int,
    val name: String,
    val originalName: String = "",
    val localizedName: String? = "",
    val sortName: String? = "",
    val pages: Int = 0,
    val coverImageLocked: Boolean = true,
    val pagesRead: Int = 0,
    val userRating: Float = 0f,
    val userReview: String? = "",
    val format: Int = 0,
    val created: String? = "",
    val libraryId: Int = 0,
    val libraryName: String? = "",
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = "$baseUrl/api/Series/$id"
        title = localizedName?.takeIf { it.isNotBlank() } ?: sortName?.takeIf { it.isNotBlank() } ?: name
        description = ""
        status = SManga.UNKNOWN
        thumbnail_url = "$baseUrl/api/Image/SeriesCover?seriesId=$id"
        initialized = true
    }
}

@Serializable
data class KavitaSeriesDetailPlusDto(
    val seriesId: Int? = null,
    val libraryName: String? = "",
    val libraryId: Int? = null,
    val summary: String? = null,
    val genres: List<KavitaMetadataGenre> = emptyList(),
    val tags: List<KavitaMetadataTag> = emptyList(),
    val writers: List<KavitaMetadataPeople> = emptyList(),
    val coverArtists: List<KavitaMetadataPeople> = emptyList(),
    val publicationStatus: Int? = null,
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = seriesId?.let { "$baseUrl/api/Series/$it" } ?: ""
        title = ""
        author = writers.joinToString { it.name }.takeIf { it.isNotBlank() }
        artist = coverArtists.joinToString { it.name }.takeIf { it.isNotBlank() }
        description = summary
        genre = (genres.map { it.title } + tags.map { it.title }).joinToString(", ").takeIf { it.isNotBlank() }
        status = publicationStatus?.let { mapPublicationStatusV2(it) } ?: SManga.UNKNOWN
        thumbnail_url = seriesId?.let { "$baseUrl/api/Image/SeriesCover?seriesId=$it" }
        initialized = true
    }
}

private fun mapPublicationStatusV2(status: Int): Int = when (status) {
    1 -> SManga.ONGOING
    2 -> SManga.COMPLETED
    3 -> SManga.CANCELLED
    4 -> SManga.ON_HIATUS
    5 -> SManga.PUBLISHING_FINISHED
    else -> SManga.UNKNOWN
}

@Serializable
data class KavitaMetadataGenre(val id: Int, val title: String)

@Serializable
data class KavitaMetadataTag(val id: Int, val title: String)

@Serializable
data class KavitaMetadataPeople(val id: Int, val name: String)

@Serializable
data class KavitaVolumeDto(
    val id: Int,
    val minNumber: Double,
    val maxNumber: Double,
    val name: String = "",
    val pages: Int = 0,
    val pagesRead: Int = 0,
    val lastModified: String = "",
    val created: String = "",
    val seriesId: Int = 0,
    val coverImage: String = "",
    val chapters: List<KavitaChapterDto> = emptyList(),
)

@Serializable
data class KavitaChapterDto(
    val id: Int,
    val range: String = "",
    val number: String = "",
    val minNumber: Double = 0.0,
    val maxNumber: Double = 0.0,
    val pages: Int = 0,
    val isSpecial: Boolean = false,
    val title: String = "",
    val titleName: String? = null,
    val pagesRead: Int = 0,
    val coverImageLocked: Boolean = true,
    val coverImage: String = "",
    val volumeId: Int = 0,
    val created: String = "",
    val lastModifiedUtc: String = "",
    val releaseDate: String = "",
)

@Serializable
data class KavitaPageDto(
    val number: Int,
    val fileName: String? = null,
)

// ===== Filter DTOs =====

@Serializable
data class KavitaFilterV2Dto(
    val sortOptions: KavitaSortOptions,
    val statements: MutableList<KavitaFilterStatementDto>,
)

@Serializable
data class KavitaSortOptions(
    var sortField: Int = 1,
    var isAscending: Boolean = true,
)

@Serializable
data class KavitaFilterStatementDto(
    val comparison: Int,
    val field: Int,
    val value: String,
)

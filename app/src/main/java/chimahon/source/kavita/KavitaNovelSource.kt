package chimahon.source.kavita

import chimahon.novel.model.NovelServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KavitaNovelSource(
    private val server: NovelServer,
) : NovelsPageSource {

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

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        val seriesId = novel.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/series/metadata?seriesId=$seriesId")).awaitSuccess()
        return parseNovelDetails(response).let { details ->
            // Preserve original title if the detail DTO doesn't have one
            if (details.title.isBlank() && novel.title.isNotBlank()) {
                details.copy().also { it.title = novel.title }
            } else {
                details
            }
        }
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        val seriesId = novel.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/Series/$seriesId/Volumes")).awaitSuccess()
        val volumes = json.decodeFromString<List<KavitaVolumeDto>>(response.body.string())
        return volumes.flatMap { volume ->
            volume.chapters.map { chapter ->
                SNChapter(
                    name = chapter.titleName?.takeIf { it.isNotBlank() } ?: chapter.range.takeIf { it.isNotBlank() } ?: "Chapter ${chapter.minNumber.toInt()}",
                    url = "$baseUrl/api/Chapter/${chapter.id}",
                    chapter_number = chapter.minNumber.toFloat(),
                    date_upload = chapter.created.takeIf { it.isNotBlank() }?.let { parseDateTime(it) } ?: 0L,
                )
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        val chapterId = chapter.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/Chapter/$chapterId/ExtractText")).awaitSuccess()
        val text = response.body.string()
        return if (text.isNotBlank()) ChapterContent.text(text) else ChapterContent.text("No text content available")
    }

    override suspend fun getPopularNovels(page: Int): NovelPage {
        val libraries = getLibraries().filter { it.type == 2 || it.type == 4 }
        if (libraries.isEmpty()) return NovelPage(emptyList(), false)
        val filter = KavitaFilterV2Dto(
            sortOptions = KavitaSortOptions(sortField = 8, isAscending = false),
            statements = libraries.map { lib ->
                KavitaFilterStatementDto(comparison = 5, field = 19, value = lib.id.toString())
            }.toMutableList(),
        )
        return executeSeriesSearch(filter, page)
    }

    override suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage {
        val libraries = getLibraries().filter { it.type == 2 || it.type == 4 }
        val filter = KavitaFilterV2Dto(
            sortOptions = KavitaSortOptions(sortField = 1, isAscending = true),
            statements = mutableListOf(),
        )
        if (query.isNotBlank()) {
            filter.statements.add(KavitaFilterStatementDto(comparison = 7, field = 1, value = query))
        }
        if (libraries.isNotEmpty()) {
            filter.statements.addAll(libraries.map { lib ->
                KavitaFilterStatementDto(comparison = 5, field = 19, value = lib.id.toString())
            })
        }
        return executeSeriesSearch(filter, page)
    }

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        val libraries = getLibraries().filter { it.type == 2 || it.type == 4 }
        if (libraries.isEmpty()) return NovelPage(emptyList(), false)
        val filter = KavitaFilterV2Dto(
            sortOptions = KavitaSortOptions(sortField = 4, isAscending = false),
            statements = libraries.map { lib ->
                KavitaFilterStatementDto(comparison = 5, field = 19, value = lib.id.toString())
            }.toMutableList(),
        )
        return executeSeriesSearch(filter, page)
    }

    override fun getFilterList() = FilterList()

    private suspend fun executeSeriesSearch(filter: KavitaFilterV2Dto, page: Int): NovelPage {
        val payload = json.encodeToJsonElement(filter).toString()
        val response = client.newCall(
            POST(
                "$baseUrl/api/Series/all-v2?pageNumber=$page&pageSize=20",
                headers = authHeaders,
                body = payload.toRequestBody("application/json".toMediaType()),
            ),
        ).awaitSuccess()
        return parseNovelPage(response)
    }

    private suspend fun getLibraries(): List<KavitaLibraryDto> {
        val response = client.newCall(GET("$baseUrl/api/Library")).awaitSuccess()
        return json.decodeFromString<List<KavitaLibraryDto>>(response.body.string())
    }

    private fun parseNovelPage(response: Response): NovelPage {
        val seriesList = json.decodeFromString<List<KavitaSeriesDto>>(response.body.string())
        return NovelPage(seriesList.map { it.toSNNovel(baseUrl) }, seriesList.size >= 20)
    }

    private fun parseNovelDetails(response: Response): SNNovel {
        val detail = json.decodeFromString<KavitaSeriesDetailPlusDto>(response.body.string())
        return detail.toSNNovel(baseUrl)
    }

    private fun parseDateTime(dateStr: String): Long = runCatching {
        threadLocalFormatter.get()!!.parse(dateStr)?.time ?: 0L
    }.getOrDefault(0L)

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

fun KavitaSeriesDto.toSNNovel(baseUrl: String): SNNovel = SNNovel(
    url = "$baseUrl/api/Series/$id",
    title = localizedName?.takeIf { it.isNotBlank() } ?: sortName?.takeIf { it.isNotBlank() } ?: name,
    description = "",
    status = SNNovel.UNKNOWN,
    thumbnail_url = "$baseUrl/api/Image/SeriesCover?seriesId=$id",
)

fun KavitaSeriesDetailPlusDto.toSNNovel(baseUrl: String): SNNovel = SNNovel(
    url = seriesId?.let { "$baseUrl/api/Series/$it" } ?: "",
    title = "",
    author = writers.joinToString { it.name }.takeIf { it.isNotBlank() },
    artist = coverArtists.joinToString { it.name }.takeIf { it.isNotBlank() },
    description = summary ?: "",
    genre = (genres.map { it.title } + tags.map { it.title }).joinToString(", ").takeIf { it.isNotBlank() },
    status = publicationStatus?.let { mapPublicationStatus(it) } ?: SNNovel.UNKNOWN,
    thumbnail_url = seriesId?.let { "$baseUrl/api/Image/SeriesCover?seriesId=$it" },
)

private fun mapPublicationStatus(status: Int): Int = when (status) {
    1 -> SNNovel.ONGOING
    2 -> SNNovel.COMPLETED
    3 -> SNNovel.CANCELLED
    4 -> SNNovel.ON_HIATUS
    5 -> SNNovel.PUBLISHING_FINISHED
    else -> SNNovel.UNKNOWN
}

package chimahon.source.komga

import chimahon.novel.model.NovelServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KomgaNovelSource(
    private val server: NovelServer,
) : NovelsPageSource {

    private val network: NetworkHelper by lazy { Injekt.get<NetworkHelper>() }
    private val json = Json { ignoreUnknownKeys = true }

    override val id: Long by lazy { generateId(server) }
    override val name: String get() = server.name
    override val lang: String get() = "all"
    override val supportsLatest: Boolean get() = true

    private val baseUrl: String get() = server.baseUrl.trimEnd('/')

    private val authHeaders: Headers
        get() {
            return if (!server.apiKey.isNullOrBlank()) {
                Headers.Builder().add("X-API-Key", server.apiKey!!).build()
            } else if (!server.username.isNullOrBlank()) {
                Headers.Builder().add("Authorization", Credentials.basic(server.username!!, server.apiKey ?: "")).build()
            } else {
                Headers.Builder().build()
            }
        }

    private val client: OkHttpClient
        get() {
            val builder = network.client.newBuilder()
            if (!server.apiKey.isNullOrBlank()) {
                builder.addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("X-API-Key", server.apiKey!!).build())
                }
            } else if (!server.username.isNullOrBlank()) {
                builder.addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic(server.username!!, server.apiKey ?: "")).build())
                }
            }
            return builder.build()
        }

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        val seriesId = novel.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/v1/series/$seriesId")).awaitSuccess()
        return parseNovelDetails(response)
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        val seriesId = novel.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/v1/series/$seriesId/books?unpaged=true&media_status=READY&deleted=false")).awaitSuccess()
        return parseNovelChapters(response)
    }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        val bookId = chapter.url.substringAfterLast("/")
        val pagesResponse = client.newCall(GET("$baseUrl/api/v1/books/$bookId/pages")).awaitSuccess()
        val pages = json.decodeFromString<List<KomgaPageDto>>(pagesResponse.body.string())
        val imageUrls = pages.map { "$baseUrl/api/v1/books/$bookId/pages/${it.number}/media" }
        return if (imageUrls.isNotEmpty()) ChapterContent.images(imageUrls) else ChapterContent.text("No content")
    }

    override suspend fun getPopularNovels(page: Int): NovelPage {
        val response = client.newCall(GET("$baseUrl/api/v1/series?page=${page - 1}&size=20&sort=metadata.titleSort,asc&media_status=READY&deleted=false")).awaitSuccess()
        return parseNovelPage(response)
    }

    override suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage {
        val response = client.newCall(GET("$baseUrl/api/v1/series?search=$query&page=${page - 1}&size=20&media_status=READY&deleted=false")).awaitSuccess()
        return parseNovelPage(response)
    }

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        val response = client.newCall(GET("$baseUrl/api/v1/series?page=${page - 1}&size=20&sort=lastModifiedDate,desc&media_status=READY&deleted=false")).awaitSuccess()
        return parseNovelPage(response)
    }

    override fun getFilterList() = FilterList()

    private fun parseNovelPage(response: Response): NovelPage {
        val data = json.decodeFromString<PageWrapperDto<KomgaSeriesDto>>(response.body.string())
        val novels = data.content.mapNotNull { it.toSNNovel(baseUrl) }
        return NovelPage(novels, !data.last)
    }

    private fun parseNovelDetails(response: Response): SNNovel {
        val series = json.decodeFromString<KomgaSeriesDto>(response.body.string())
        return series.toSNNovel(baseUrl)
    }

    private fun parseNovelChapters(response: Response): List<SNChapter> {
        val data = json.decodeFromString<PageWrapperDto<KomgaBookDto>>(response.body.string())
        return data.content
            .map { book ->
                SNChapter(
                    name = book.metadata.title.ifBlank { book.name },
                    url = "$baseUrl/api/v1/books/${book.id}",
                    chapter_number = book.metadata.numberSort,
                    date_upload = book.metadata.releaseDate?.let { parseDate(it) }
                        ?: book.created?.let { parseDateTime(it) }
                        ?: parseDateTime(book.fileLastModified),
                    scanlator = book.metadata.authors.firstOrNull { it.role == "translator" }?.name,
                )
            }
            .sortedByDescending { it.chapter_number }
    }

    private fun parseDate(dateStr: String): Long = runCatching { threadLocalDate.get().parse(dateStr)?.time ?: 0L }.getOrDefault(0L)

    private fun parseDateTime(dateStr: String): Long = runCatching { threadLocalDateTime.get().parse(dateStr)?.time ?: 0L }.getOrDefault(0L)

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

fun KomgaSeriesDto.toSNNovel(baseUrl: String): SNNovel = SNNovel(
    url = "$baseUrl/api/v1/series/$id",
    title = metadata.title,
    author = booksMetadata.authors.filter { it.role == "writer" }.map { it.name }.distinct().joinToString().takeIf { it.isNotBlank() },
    artist = booksMetadata.authors.filter { it.role == "penciller" }.map { it.name }.distinct().joinToString().takeIf { it.isNotBlank() },
    description = metadata.summary.ifBlank { booksMetadata.summary },
    genre = (metadata.genres + booksMetadata.tags).sorted().distinct().joinToString(", "),
    status = when {
        metadata.status == "ENDED" && metadata.totalBookCount != null && booksCount < metadata.totalBookCount -> SNNovel.PUBLISHING_FINISHED
        metadata.status == "ENDED" -> SNNovel.COMPLETED
        metadata.status == "ONGOING" -> SNNovel.ONGOING
        metadata.status == "ABANDONED" -> SNNovel.CANCELLED
        metadata.status == "HIATUS" -> SNNovel.ON_HIATUS
        else -> SNNovel.UNKNOWN
    },
    thumbnail_url = "$baseUrl/api/v1/series/$id/thumbnail",
)

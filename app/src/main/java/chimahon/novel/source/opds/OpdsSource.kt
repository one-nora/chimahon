package chimahon.novel.source.opds

import chimahon.novel.model.NovelServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import java.security.MessageDigest

class OpdsSource(
    private val server: NovelServer,
) : NovelSource {

    private val network: NetworkHelper by lazy { uy.kohesive.injekt.Injekt.get() }
    private val json = Json { ignoreUnknownKeys = true }

    override val id: Long by lazy { generateId(server) }
    override val name: String get() = server.name
    override val lang: String get() = "en"

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

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        val response = client.newCall(GET(novel.url)).awaitSuccess()
        return parseNovelDetails(response, novel)
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        val response = client.newCall(GET(novel.url)).awaitSuccess()
        return parseChapterList(response)
    }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        val response = client.newCall(GET(chapter.url)).awaitSuccess()
        val body = response.body.string()
        val doc = Jsoup.parse(body)
        val content = doc.selectFirst("div.entry-content, div.content, article, body")
        return ChapterContent.text(content?.wholeText() ?: body)
    }

    override suspend fun getPopularNovels(page: Int): NovelPage {
        val response = client.newCall(GET("$baseUrl/opds-catalog?orderby=popularity&page=$page")).awaitSuccess()
        return parseNovelList(response, page)
    }

    override suspend fun getSearchNovels(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList): NovelPage {
        val response = client.newCall(GET("$baseUrl/opds-catalog?search=$query&page=$page")).awaitSuccess()
        return parseNovelList(response, page)
    }

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        val response = client.newCall(GET("$baseUrl/opds-catalog?orderby=new&page=$page")).awaitSuccess()
        return parseNovelList(response, page)
    }

    override fun getFilterList() = eu.kanade.tachiyomi.source.model.FilterList()

    private fun parseNovelList(response: Response, page: Int): NovelPage {
        val body = response.body.string()
        return if (body.trimStart().startsWith("{")) {
            parseOpds2List(body, page)
        } else {
            parseOpds1List(body, page)
        }
    }

    private fun parseOpds2List(body: String, page: Int): NovelPage {
        val novels = mutableListOf<SNNovel>()
        val root = json.parseToJsonElement(body).jsonObject
        val feed = root["feed"] ?: root
        val entries = feed.jsonObject["books"]?.jsonArray ?: feed.jsonObject["navigation"]?.jsonArray ?: emptyList()

        for (entry in entries) {
            val obj = entry.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: "Unknown"
            val url = obj["links"]?.jsonArray?.firstOrNull {
                it.jsonObject["rel"]?.jsonPrimitive?.content == "self" ||
                it.jsonObject["type"]?.jsonPrimitive?.content?.contains("opds-publication") == true
            }?.jsonObject?.get("href")?.jsonPrimitive?.content ?: continue

            val cover = obj["links"]?.jsonArray?.firstOrNull {
                it.jsonObject["rel"]?.jsonPrimitive?.content == "http://opds-spec.org/image" ||
                it.jsonObject["type"]?.jsonPrimitive?.content?.startsWith("image/") == true
            }?.jsonObject?.get("href")?.jsonPrimitive?.content

            val author = obj["author"]?.jsonObject?.get("name")?.jsonPrimitive?.content

            novels.add(
                SNNovel(
                    url = if (url.startsWith("http")) url else "$baseUrl$url",
                    title = title,
                    author = author,
                    thumbnail_url = cover,
                ),
            )
        }

        return NovelPage(novels, novels.size >= 20)
    }

    private fun parseOpds1List(body: String, page: Int): NovelPage {
        val novels = mutableListOf<SNNovel>()
        val doc = Jsoup.parse(body, "UTF-8")

        doc.select("entry").forEach { entry ->
            val title = entry.selectFirst("title")?.text() ?: return@forEach
            val id = entry.selectFirst("id")?.text() ?: return@forEach
            val cover = entry.select("link[rel=http\\:opds-spec.org\\/image], link[rel=http\\:opds-spec.org\\/image-thumbnail]")
                .firstOrNull()?.attr("href")
            val author = entry.selectFirst("author name")?.text()

            novels.add(
                SNNovel(
                    url = if (id.startsWith("http")) id else "$baseUrl$id",
                    title = title,
                    author = author,
                    thumbnail_url = cover,
                ),
            )
        }

        return NovelPage(novels, novels.size >= 20)
    }

    private fun parseNovelDetails(response: Response, novel: SNNovel): SNNovel {
        val body = response.body.string()
        return if (body.trimStart().startsWith("{")) {
            val root = json.parseToJsonElement(body).jsonObject
            novel.copy(
                title = root["metadata"]?.jsonObject?.get("title")?.jsonPrimitive?.content ?: novel.title,
                author = root["metadata"]?.jsonObject?.get("author")?.jsonObject?.get("name")?.jsonPrimitive?.content ?: novel.author,
                description = root["metadata"]?.jsonObject?.get("description")?.jsonPrimitive?.content ?: novel.description,
            )
        } else {
            val doc = Jsoup.parse(body, "UTF-8")
            novel.copy(
                title = doc.selectFirst("title")?.text() ?: novel.title,
                description = doc.selectFirst("content, summary")?.text() ?: novel.description,
            )
        }
    }

    private fun parseChapterList(response: Response): List<SNChapter> {
        val body = response.body.string()
        val chapters = mutableListOf<SNChapter>()

        if (body.trimStart().startsWith("{")) {
            val root = json.parseToJsonElement(body).jsonObject
            val entries = root["feed"]?.jsonObject?.get("books")?.jsonArray
                ?: root["publications"]?.jsonArray
                ?: emptyList()

            entries.forEachIndexed { index, entry ->
                val obj = entry.jsonObject
                val title = obj["metadata"]?.jsonObject?.get("title")?.jsonPrimitive?.content
                    ?: obj["title"]?.jsonPrimitive?.content ?: "Unknown"
                val url = obj["links"]?.jsonArray?.firstOrNull {
                    it.jsonObject["rel"]?.jsonPrimitive?.content == "http://opds-spec.org/acquisition" ||
                    it.jsonObject["type"]?.jsonPrimitive?.content?.contains("epub") == true ||
                    it.jsonObject["type"]?.jsonPrimitive?.content?.contains("html") == true
                }?.jsonObject?.get("href")?.jsonPrimitive?.content ?: return@forEachIndexed

                chapters.add(
                    SNChapter(
                        name = title,
                        url = if (url.startsWith("http")) url else "$baseUrl$url",
                        chapter_number = (index + 1).toFloat(),
                    ),
                )
            }
        } else {
            val doc = Jsoup.parse(body, "UTF-8")
            doc.select("entry").forEachIndexed { index, entry ->
                val title = entry.selectFirst("title")?.text() ?: return@forEachIndexed
                val id = entry.selectFirst("id")?.text() ?: return@forEachIndexed
                val date = entry.selectFirst("updated, published")?.text()

                chapters.add(
                    SNChapter(
                        name = title,
                        url = if (id.startsWith("http")) id else "$baseUrl$id",
                        chapter_number = (index + 1).toFloat(),
                        date_upload = date?.let { parseDate(it) } ?: 0L,
                    ),
                )
            }
        }

        return chapters
    }

    private fun parseDate(dateStr: String): Long = runCatching { java.time.Instant.parse(dateStr).toEpochMilli() }.getOrDefault(0L)

    companion object {
        fun generateId(server: NovelServer): Long {
            val key = "opds:${server.baseUrl}:${server.name}"
            val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }
    }
}

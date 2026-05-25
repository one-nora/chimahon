package chimahon.novel.extension.js

import chimahon.source.ireader.model.ChapterInfo
import chimahon.source.ireader.model.MangaInfo
import chimahon.source.ireader.model.Page
import chimahon.source.ireader.model.Text
import chimahon.source.ireader.source.CatalogSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class JsNovelSource(
    private val engine: JsSourceEngine,
    private val metadata: JsSourceMetadata,
    private val script: String
) : CatalogSource {
    private val json = Json { ignoreUnknownKeys = true }

    override val id: Long by lazy {
        val key = "${metadata.name.lowercase()}/${metadata.lang}/1"
        chimahon.source.ireader.source.HttpSource.generateSourceId(key)
    }

    override val name: String get() = metadata.name
    override val lang: String get() = metadata.lang

    suspend fun initialize(): Boolean {
        val result = engine.loadSource(script, metadata.id)
        return result != null
    }

    override suspend fun getMangaList(sort: chimahon.source.ireader.model.Listing?, page: Int): chimahon.source.ireader.model.MangasPageInfo {
        val result = engine.callFunctionWithFilters("popularNovels", page, "[]")
        return parseNovelList(result)
    }

    override suspend fun getMangaList(filters: List<chimahon.source.ireader.model.Filter<*>>, page: Int): chimahon.source.ireader.model.MangasPageInfo {
        val query = filters.filterIsInstance<chimahon.source.ireader.model.Filter.Title>()
            .firstOrNull()?.value ?: ""

        val result = if (query.isNotBlank()) {
            engine.callFunction("searchNovels", query, page)
        } else {
            engine.callFunctionWithFilters("popularNovels", page, "[]")
        }
        return parseNovelList(result)
    }

    override suspend fun getMangaDetails(manga: MangaInfo, commands: List<chimahon.source.ireader.model.Command<*>>): MangaInfo {
        val result = engine.callFunction("parseNovel", manga.key)
        return parseNovelDetails(result, manga)
    }

    override suspend fun getChapterList(manga: MangaInfo, commands: List<chimahon.source.ireader.model.Command<*>>): List<ChapterInfo> {
        val result = engine.callFunction("parseNovel", manga.key)
        return parseChapterList(result)
    }

    override suspend fun getPageList(chapter: ChapterInfo, commands: List<chimahon.source.ireader.model.Command<*>>): List<Page> {
        val result = engine.callFunction("parseChapter", chapter.key)
        return parseChapterContent(result)
    }

    override fun getListings(): List<chimahon.source.ireader.model.Listing> {
        return listOf(
            object : chimahon.source.ireader.model.Listing("Popular") {},
            object : chimahon.source.ireader.model.Listing("Latest") {}
        )
    }

    override fun getFilters(): List<chimahon.source.ireader.model.Filter<*>> = emptyList()
    override fun getCommands(): List<chimahon.source.ireader.model.Command<*>> = emptyList()

    private fun parseNovelList(jsonStr: String?): chimahon.source.ireader.model.MangasPageInfo {
        if (jsonStr == null) return chimahon.source.ireader.model.MangasPageInfo.empty()

        return try {
            val cleaned = jsonStr.replace("\\\"", "\"").removeSurrounding("\"")
            val response = json.decodeFromString<JsNovelListResponse>(cleaned)

            if (response.error != null) {
                return chimahon.source.ireader.model.MangasPageInfo.empty()
            }

            val novels = response.novels?.map { novel ->
                MangaInfo(
                    key = novel.path ?: novel.url ?: "",
                    title = novel.name ?: novel.title ?: "Unknown",
                    author = novel.author ?: "",
                    cover = novel.cover ?: novel.image ?: "",
                    description = novel.description ?: "",
                    genres = novel.genres?.split(",")?.map { it.trim() } ?: emptyList(),
                    status = parseStatus(novel.status)
                )
            } ?: emptyList()

            chimahon.source.ireader.model.MangasPageInfo(novels, response.hasNextPage ?: false)
        } catch (e: Exception) {
            chimahon.source.ireader.model.MangasPageInfo.empty()
        }
    }

    private fun parseNovelDetails(jsonStr: String?, original: MangaInfo): MangaInfo {
        if (jsonStr == null) return original

        return try {
            val cleaned = jsonStr.replace("\\\"", "\"").removeSurrounding("\"")
            val novel = json.decodeFromString<JsNovelDetail>(cleaned)

            MangaInfo(
                key = original.key,
                title = novel.name ?: novel.title ?: original.title,
                author = novel.author ?: original.author,
                cover = novel.cover ?: novel.image ?: original.cover,
                description = novel.description ?: original.description,
                genres = novel.genres?.split(",")?.map { it.trim() } ?: original.genres,
                status = parseStatus(novel.status)
            )
        } catch (e: Exception) {
            original
        }
    }

    private fun parseChapterList(jsonStr: String?): List<ChapterInfo> {
        if (jsonStr == null) return emptyList()

        return try {
            val cleaned = jsonStr.replace("\\\"", "\"").removeSurrounding("\"")
            val novel = json.decodeFromString<JsNovelDetail>(cleaned)

            novel.chapters?.mapIndexed { index, chapter ->
                ChapterInfo(
                    key = chapter.url ?: chapter.path ?: "",
                    name = chapter.name ?: chapter.title ?: "Chapter ${index + 1}",
                    number = chapter.chapterNumber?.toFloatOrNull() ?: (index + 1).toFloat(),
                    dateUpload = parseDate(chapter.date)
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseChapterContent(jsonStr: String?): List<Page> {
        if (jsonStr == null) return emptyList()

        return try {
            val cleaned = jsonStr.replace("\\\"", "\"").removeSurrounding("\"")
            val chapter = json.decodeFromString<JsChapterContent>(cleaned)

            if (chapter.text != null) {
                chapter.text.split("\n\n")
                    .filter { it.isNotBlank() }
                    .map { Text(it.trim()) }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseStatus(status: String?): Long {
        return when (status?.lowercase()?.trim()) {
            "ongoing", "publishing", "serializing" -> MangaInfo.ONGOING
            "completed", "complete", "finished" -> MangaInfo.COMPLETED
            "licensed" -> MangaInfo.LICENSED
            "cancelled", "canceled", "dropped" -> MangaInfo.CANCELLED
            "hiatus", "on hiatus", "on hold" -> MangaInfo.ON_HIATUS
            else -> MangaInfo.UNKNOWN
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            java.time.Instant.parse(dateStr).toEpochMilli()
        } catch (e: Exception) {
            try {
                val formats = listOf(
                    "yyyy-MM-dd",
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "MM/dd/yyyy",
                    "dd/MM/yyyy"
                )
                for (format in formats) {
                    try {
                        val sdf = java.text.SimpleDateFormat(format, java.util.Locale.US)
                        return sdf.parse(dateStr)?.time ?: 0L
                    } catch (e: Exception) {}
                }
                0L
            } catch (e: Exception) {
                0L
            }
        }
    }
}

@Serializable
data class JsNovelListResponse(
    val novels: List<JsNovelItem>? = null,
    val hasNextPage: Boolean? = null,
    val error: String? = null
)

@Serializable
data class JsNovelItem(
    val name: String? = null,
    val title: String? = null,
    val path: String? = null,
    val url: String? = null,
    val cover: String? = null,
    val image: String? = null,
    val description: String? = null,
    val author: String? = null,
    val status: String? = null,
    val genres: String? = null
)

@Serializable
data class JsNovelDetail(
    val name: String? = null,
    val title: String? = null,
    val path: String? = null,
    val url: String? = null,
    val cover: String? = null,
    val image: String? = null,
    val description: String? = null,
    val author: String? = null,
    val status: String? = null,
    val genres: String? = null,
    val chapters: List<JsChapterItem>? = null
)

@Serializable
data class JsChapterItem(
    val name: String? = null,
    val title: String? = null,
    val path: String? = null,
    val url: String? = null,
    val chapterNumber: String? = null,
    val date: String? = null
)

@Serializable
data class JsChapterContent(
    val text: String? = null,
    val images: List<String>? = null
)

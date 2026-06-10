package ireader.core.source.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MangaInfo(
    val key: String,
    val title: String,
    val artist: String = "",
    val author: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val status: Long = UNKNOWN,
    val cover: String = "",
) {
    fun isOngoing(): Boolean = status == ONGOING
    fun isCompleted(): Boolean = status == COMPLETED || status == PUBLISHING_FINISHED
    fun isValid(): Boolean = key.isNotBlank() && title.isNotBlank()
    fun getCleanDescription(): String = description.replace(Regex("\\s+"), " ").trim()

    companion object {
        const val UNKNOWN = 0L
        const val ONGOING = 1L
        const val COMPLETED = 2L
        const val LICENSED = 3L
        const val PUBLISHING_FINISHED = 4L
        const val CANCELLED = 5L
        const val ON_HIATUS = 6L

        fun parseStatus(statusText: String): Long {
            return when (statusText.trim().lowercase()) {
                "ongoing", "publishing", "serializing" -> ONGOING
                "completed", "complete", "finished" -> COMPLETED
                "licensed" -> LICENSED
                "cancelled", "canceled", "dropped" -> CANCELLED
                "hiatus", "on hiatus", "on hold" -> ON_HIATUS
                else -> UNKNOWN
            }
        }
    }
}

@Serializable
data class ChapterInfo(
    var key: String,
    var name: String,
    var dateUpload: Long = 0,
    var number: Float = -1f,
    var scanlator: String = "",
    var type: Long = NOVEL,
) {
    fun hasValidNumber(): Boolean = number >= 0f
    fun isNovel(): Boolean = type == NOVEL
    fun isValid(): Boolean = key.isNotBlank() && name.isNotBlank()
    fun withAutoNumber(): ChapterInfo = if (number < 0f) copy(number = extractChapterNumber(name)) else this

    companion object {
        const val MIX = 0L
        const val NOVEL = 1L
        const val MUSIC = 2L
        const val MANGA = 3L
        const val MOVIE = 4L

        fun extractChapterNumber(name: String): Float {
            val patterns = listOf(
                Regex("""(?:chapter|ch\.?|episode|ep\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
                Regex("""^(\d+(?:\.\d+)?)(?:\s*[-:.]|\s+)"""),
            )
            return patterns.firstNotNullOfOrNull { pattern ->
                pattern.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            } ?: -1f
        }
    }
}

@Serializable
sealed class Page

@Serializable
data class PageUrl(val url: String) : Page()

@Serializable
sealed class PageComplete : Page()

@Serializable
data class ImageUrl(val url: String) : PageComplete()

@Serializable
data class ImageBase64(val data: String) : PageComplete()

@Serializable
data class Text(val text: String) : PageComplete()

@Serializable
data class MovieUrl(val url: String) : PageComplete()

@Serializable
data class Subtitle(
    val url: String,
    val language: String? = null,
    val name: String? = null,
) : PageComplete()

@Serializable
data class MangasPageInfo(
    val mangas: List<MangaInfo>,
    val hasNextPage: Boolean,
) {
    fun isEmpty(): Boolean = mangas.isEmpty()
    fun isNotEmpty(): Boolean = mangas.isNotEmpty()
    fun size(): Int = mangas.size
    fun filter(predicate: (MangaInfo) -> Boolean): MangasPageInfo = copy(mangas = mangas.filter(predicate))
    fun map(transform: (MangaInfo) -> MangaInfo): MangasPageInfo = copy(mangas = mangas.map(transform))

    companion object {
        fun empty(): MangasPageInfo = MangasPageInfo(emptyList(), false)
        fun lastPage(mangas: List<MangaInfo>): MangasPageInfo = MangasPageInfo(mangas, false)
    }
}

@Serializable
data class ChaptersPageInfo(
    val chapters: List<ChapterInfo>,
    val hasNextPage: Boolean,
) {
    fun isEmpty(): Boolean = chapters.isEmpty()
    fun isNotEmpty(): Boolean = chapters.isNotEmpty()
    fun size(): Int = chapters.size

    companion object {
        fun empty(): ChaptersPageInfo = ChaptersPageInfo(emptyList(), false)
        fun singlePage(chapters: List<ChapterInfo>): ChaptersPageInfo = ChaptersPageInfo(chapters, false)
        fun lastPage(chapters: List<ChapterInfo>): ChaptersPageInfo = ChaptersPageInfo(chapters, false)
    }
}

abstract class Listing(val name: String)

private val pageJson = Json { ignoreUnknownKeys = true }

fun String.decode(): List<Page> {
    if (isBlank()) return emptyList()
    return runCatching { pageJson.decodeFromString<List<Page>>(this) }.getOrElse { emptyList() }
}

fun List<Page>.encode(): String {
    if (isEmpty()) return "[]"
    return runCatching { pageJson.encodeToString(this) }.getOrElse { "[]" }
}

package chimahon.source.ireader.adapter

import chimahon.source.ireader.model.ChapterInfo
import chimahon.source.ireader.model.Filter
import chimahon.source.ireader.model.ImageUrl
import chimahon.source.ireader.model.MangaInfo
import chimahon.source.ireader.model.Page
import chimahon.source.ireader.model.Text
import chimahon.source.ireader.source.CatalogSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.ContentItem
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel

class IReaderSourceAdapter(
    private val ireaderSource: CatalogSource
) : NovelsPageSource {

    override val id: Long get() = ireaderSource.id
    override val name: String get() = ireaderSource.name
    override val lang: String get() = ireaderSource.lang
    override val supportsLatest: Boolean get() = ireaderSource.supportsLatest()

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        val mangaInfo = novel.toMangaInfo()
        val result = ireaderSource.getMangaDetails(mangaInfo, emptyList())
        return result.toSNNovel()
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        val mangaInfo = novel.toMangaInfo()
        val chapters = ireaderSource.getChapterList(mangaInfo, emptyList())
        return chapters.map { it.toSNChapter() }
    }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        val chapterInfo = chapter.toChapterInfo()
        val pages = ireaderSource.getPageList(chapterInfo, emptyList())
        return pages.toChapterContent()
    }

    override suspend fun getPopularNovels(page: Int): NovelPage {
        val result = ireaderSource.getMangaList(null, page)
        return NovelPage(
            novels = result.mangas.map { it.toSNNovel() },
            hasNextPage = result.hasNextPage
        )
    }

    override suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage {
        val ireaderFilters = listOf<Filter<*>>(Filter.Title().apply { value = query })
        val result = ireaderSource.getMangaList(ireaderFilters, page)
        return NovelPage(
            novels = result.mangas.map { it.toSNNovel() },
            hasNextPage = result.hasNextPage
        )
    }

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        val listings = ireaderSource.getListings()
        val latestListing = listings.lastOrNull()
        val result = ireaderSource.getMangaList(latestListing, page)
        return NovelPage(
            novels = result.mangas.map { it.toSNNovel() },
            hasNextPage = result.hasNextPage
        )
    }

    override fun getFilterList(): FilterList = FilterList()
}

fun SNNovel.toMangaInfo(): MangaInfo = MangaInfo(
    key = url,
    title = title,
    author = author ?: "",
    artist = artist ?: "",
    description = description ?: "",
    genres = genre?.split(", ")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
    status = status.toLong(),
    cover = thumbnail_url ?: ""
)

fun MangaInfo.toSNNovel(): SNNovel = SNNovel(
    url = key,
    title = title,
    author = author.takeIf { it.isNotBlank() },
    artist = artist.takeIf { it.isNotBlank() },
    description = description.takeIf { it.isNotBlank() },
    genre = genres.joinToString(", ").takeIf { it.isNotBlank() },
    status = status.toInt(),
    thumbnail_url = cover.takeIf { it.isNotBlank() }
)

fun SNChapter.toChapterInfo(): ChapterInfo = ChapterInfo(
    key = url,
    name = name,
    dateUpload = date_upload,
    number = chapter_number,
    scanlator = scanlator ?: ""
)

fun ChapterInfo.toSNChapter(): SNChapter = SNChapter(
    name = name,
    url = key,
    date_upload = dateUpload,
    chapter_number = number,
    scanlator = scanlator.takeIf { it.isNotBlank() }
)

fun List<Page>.toChapterContent(): ChapterContent {
    val textPages = this.filterIsInstance<Text>()
    val imagePages = this.filterIsInstance<ImageUrl>()
    return when {
        textPages.isNotEmpty() && imagePages.isEmpty() -> {
            ChapterContent.text(textPages.map { it.text })
        }
        imagePages.isNotEmpty() && textPages.isEmpty() -> {
            ChapterContent.images(imagePages.map { it.url })
        }
        else -> {
            val items = this.mapNotNull { page ->
                when (page) {
                    is Text -> ContentItem.Text(page.text)
                    is ImageUrl -> ContentItem.Image(page.url)
                    else -> null
                }
            }
            ChapterContent.mixed(items)
        }
    }
}

package chimahon.source.ireader.adapter

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.ContentItem
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import ireader.core.source.CatalogSource
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Filter
import ireader.core.source.model.ImageUrl
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.Page
import ireader.core.source.model.Text

class IReaderCompatSourceAdapter(
    private val source: CatalogSource,
) : NovelsPageSource {
    override val id: Long get() = source.id
    override val name: String get() = source.name
    override val lang: String get() = source.lang
    override val supportsLatest: Boolean get() = source.supportsLatest()

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        return source.getMangaDetails(novel.toCompatMangaInfo(), emptyList()).toSNNovel()
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        return source.getChapterList(novel.toCompatMangaInfo(), emptyList()).map { it.toSNChapter() }
    }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        return source.getPageList(chapter.toCompatChapterInfo(), emptyList()).toChapterContent()
    }

    override suspend fun getPopularNovels(page: Int): NovelPage {
        val result = source.getMangaList(source.getListings().firstOrNull(), page)
        return NovelPage(result.mangas.map { it.toSNNovel() }, result.hasNextPage)
    }

    override suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage {
        val result = source.getMangaList(listOf(Filter.Title().apply { value = query }), page)
        return NovelPage(result.mangas.map { it.toSNNovel() }, result.hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        val result = source.getMangaList(source.getListings().lastOrNull(), page)
        return NovelPage(result.mangas.map { it.toSNNovel() }, result.hasNextPage)
    }

    override fun getFilterList(): FilterList = FilterList()
}

private fun SNNovel.toCompatMangaInfo(): MangaInfo = MangaInfo(
    key = url,
    title = title,
    author = author.orEmpty(),
    artist = artist.orEmpty(),
    description = description.orEmpty(),
    genres = genre?.split(", ")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty(),
    status = status.toLong(),
    cover = thumbnail_url.orEmpty(),
)

private fun MangaInfo.toSNNovel(): SNNovel = SNNovel(
    url = key,
    title = title,
    author = author.takeIf { it.isNotBlank() },
    artist = artist.takeIf { it.isNotBlank() },
    description = description.takeIf { it.isNotBlank() },
    genre = genres.joinToString(", ").takeIf { it.isNotBlank() },
    status = status.toInt(),
    thumbnail_url = cover.takeIf { it.isNotBlank() },
)

private fun SNChapter.toCompatChapterInfo(): ChapterInfo = ChapterInfo(
    key = url,
    name = name,
    dateUpload = date_upload,
    number = chapter_number,
    scanlator = scanlator.orEmpty(),
)

private fun ChapterInfo.toSNChapter(): SNChapter = SNChapter(
    name = name,
    url = key,
    date_upload = dateUpload,
    chapter_number = number,
    scanlator = scanlator.takeIf { it.isNotBlank() },
)

private fun List<Page>.toChapterContent(): ChapterContent {
    val textPages = filterIsInstance<Text>()
    val imagePages = filterIsInstance<ImageUrl>()
    return when {
        textPages.isNotEmpty() && imagePages.isEmpty() -> ChapterContent.text(textPages.map { it.text })
        imagePages.isNotEmpty() && textPages.isEmpty() -> ChapterContent.images(imagePages.map { it.url })
        else -> ChapterContent.mixed(
            mapNotNull { page ->
                when (page) {
                    is Text -> ContentItem.Text(page.text)
                    is ImageUrl -> ContentItem.Image(page.url)
                    else -> null
                }
            },
        )
    }
}

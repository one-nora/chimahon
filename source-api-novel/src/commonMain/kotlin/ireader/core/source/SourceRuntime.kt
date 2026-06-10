package ireader.core.source

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import ireader.core.http.DEFAULT_USER_AGENT
import ireader.core.http.HttpClientsInterface
import ireader.core.prefs.PreferenceStore
import ireader.core.source.CatalogSource.Companion.TYPE_NOVEL
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Command
import ireader.core.source.model.CommandList
import ireader.core.source.model.Filter
import ireader.core.source.model.FilterList
import ireader.core.source.model.ImageUrl
import ireader.core.source.model.Listing
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangasPageInfo
import ireader.core.source.model.Page
import ireader.core.source.model.PageComplete
import ireader.core.source.model.PageUrl
import ireader.core.source.model.Text
import kotlinx.coroutines.flow.MutableSharedFlow

class Dependencies(
    val httpClients: HttpClientsInterface,
    val preferences: PreferenceStore,
)

interface Source {
    val id: Long
    val name: String
    val lang: String

    suspend fun getMangaDetails(manga: MangaInfo, commands: List<Command<*>>): MangaInfo
    suspend fun getChapterList(manga: MangaInfo, commands: List<Command<*>>): List<ChapterInfo>

    suspend fun getChapterListPaged(
        manga: MangaInfo,
        page: Int,
        commands: List<Command<*>>,
    ): ireader.core.source.model.ChaptersPageInfo {
        return ireader.core.source.model.ChaptersPageInfo.singlePage(getChapterList(manga, commands))
    }

    suspend fun getChapterPageCount(manga: MangaInfo): Int = 1
    fun supportsPaginatedChapters(): Boolean = false
    suspend fun getPageList(chapter: ChapterInfo, commands: List<Command<*>>): List<Page>
    fun getRegex(): Regex = Regex("")
    fun getSourceKey(): String = "$name-$lang-$id"
    fun matchesId(sourceId: Long): Boolean = id == sourceId
}

interface CatalogSource : Source {
    companion object {
        const val TYPE_NOVEL = 0
        const val TYPE_MANGA = 1
        const val TYPE_MOVIE = 2

        fun getTypeName(type: Int): String {
            return when (type) {
                TYPE_NOVEL -> "Novel"
                TYPE_MANGA -> "Manga"
                TYPE_MOVIE -> "Movie"
                else -> "Unknown"
            }
        }
    }

    suspend fun getMangaList(sort: Listing?, page: Int): MangasPageInfo
    suspend fun getMangaList(filters: FilterList, page: Int): MangasPageInfo
    fun getListings(): List<Listing>
    fun getFilters(): FilterList
    fun getCommands(): CommandList
    fun supportsSearch(): Boolean = getFilters().isNotEmpty()
    fun supportsLatest(): Boolean = getListings().isNotEmpty()
    fun hasFilters(): Boolean = getFilters().isNotEmpty()
    fun hasCommands(): Boolean = getCommands().isNotEmpty()
    fun getCapabilities(): SourceCapabilities = SourceCapabilities()
}

data class SourceCapabilities(
    val supportsLatest: Boolean = true,
    val supportsSearch: Boolean = true,
    val supportsFilters: Boolean = true,
    val supportsDeepLinks: Boolean = false,
    val supportsCommands: Boolean = false,
    val type: Int = TYPE_NOVEL,
)

abstract class HttpSource(private val dependencies: Dependencies) : CatalogSource {
    abstract val baseUrl: String
    open val versionId = 1
    val eventFlow = MutableSharedFlow<String>()

    override val id: Long by lazy {
        generateSourceId("${name.lowercase()}/$lang/$versionId")
    }

    open val client: HttpClient
        get() = dependencies.httpClients.default

    open val type: Int = TYPE_NOVEL

    override fun toString(): String = "$name (${lang.uppercase()})"

    open suspend fun getPage(page: PageUrl): PageComplete {
        throw Exception("Incomplete source implementation")
    }

    open fun getImageRequest(page: ImageUrl): Pair<HttpClient, HttpRequestBuilder> {
        return client to HttpRequestBuilder().apply { url(page.url) }
    }

    open fun getCoverRequest(url: String): Pair<HttpClient, HttpRequestBuilder> {
        return client to HttpRequestBuilder().apply { url(url) }
    }

    override fun getListings(): List<Listing> = emptyList()
    override fun getCommands(): CommandList = emptyList()
    override fun getFilters(): FilterList = emptyList()

    protected fun getAbsoluteUrl(path: String): String = SourceHelpers.buildAbsoluteUrl(baseUrl, path)
    protected suspend fun emitEvent(event: String) { eventFlow.emit(event) }
    open suspend fun isAvailable(): Boolean = true

    companion object {
        fun generateSourceId(key: String): Long {
            var hash = 0L
            for (char in key) {
                hash = 31 * hash + char.code
            }
            return hash and Long.MAX_VALUE
        }
    }
}

abstract class ParsedHttpSource(dependencies: Dependencies) : SourceFactory(dependencies)

object SourceHelpers {
    fun buildAbsoluteUrl(baseUrl: String, path: String): String {
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> "$baseUrl$path"
            else -> "$baseUrl/$path"
        }
    }
}

suspend fun HttpResponse.asJsoup(): Document = bodyAsText().asJsoup()
fun String.asJsoup(): Document = Ksoup.parse(this)
inline fun <reified T> Iterable<*>.findInstance(): T? = filterIsInstance<T>().firstOrNull()

abstract class SourceFactory(
    private val deps: Dependencies,
) : HttpSource(deps) {
    open val detailFetcher: Detail = Detail()
    open val chapterFetcher: Chapters = Chapters()
    open val contentFetcher: Content = Content()
    open val exploreFetchers: List<BaseExploreFetcher> = listOf()

    class LatestListing : Listing("Latest")
    class FetcherListing(val key: String, name: String) : Listing(name)

    open fun getCustomBaseUrl(): String = baseUrl

    override fun getListings(): List<Listing> {
        val nonSearchFetchers = exploreFetchers.filter { it.type != Type.Search }
        return if (nonSearchFetchers.isNotEmpty()) {
            nonSearchFetchers.map { FetcherListing(it.key, it.key.replaceFirstChar { char -> char.uppercase() }) }
        } else {
            listOf(LatestListing())
        }
    }

    open fun getUserAgent(): String = DEFAULT_USER_AGENT

    open fun HttpRequestBuilder.headersBuilder(
        block: HeadersBuilder.() -> Unit = {
            append(HttpHeaders.UserAgent, getUserAgent())
            append(HttpHeaders.CacheControl, "max-age=0")
        },
    ) {
        headers(block)
    }

    open fun requestBuilder(
        url: String,
        block: HeadersBuilder.() -> Unit = {
            append(HttpHeaders.UserAgent, getUserAgent())
            append(HttpHeaders.CacheControl, "max-age=0")
        },
    ): HttpRequestBuilder {
        return HttpRequestBuilder().apply {
            url(url)
            headers(block)
        }
    }

    open val page = "{page}"
    open val query = "{query}"

    open suspend fun getListRequest(baseExploreFetcher: BaseExploreFetcher, page: Int, query: String = ""): Document {
        return client.get(requestBuilder(buildFetcherUrl(baseExploreFetcher, page, query))).asJsoup()
    }

    protected fun buildFetcherUrl(fetcher: BaseExploreFetcher, page: Int, query: String = ""): String {
        val endpoint = fetcher.endpoint.orEmpty()
        return buildString {
            append(getCustomBaseUrl())
            append(
                endpoint
                    .replace(this@SourceFactory.page, fetcher.onPage(page.toString()))
                    .replace(this@SourceFactory.query, fetcher.onQuery(query)),
            )
        }
    }

    open suspend fun getLists(
        baseExploreFetcher: BaseExploreFetcher,
        page: Int,
        query: String = "",
        filters: FilterList,
    ): MangasPageInfo {
        val selector = baseExploreFetcher.selector ?: return MangasPageInfo.empty()
        val document = getListRequest(baseExploreFetcher, page, query)
        return bookListParse(document, selector, baseExploreFetcher, { parseMangaFromElement(it, baseExploreFetcher) }, page)
    }

    open fun bookListParse(
        document: Document,
        elementSelector: String,
        baseExploreFetcher: BaseExploreFetcher,
        parser: (element: Element) -> MangaInfo,
        page: Int,
    ): MangasPageInfo {
        val books = document.select(elementSelector).mapNotNull { element ->
            runCatching { parser(element) }.getOrNull()?.takeIf { it.isValid() }
        }
        val next = if (baseExploreFetcher.infinitePage) {
            true
        } else if (baseExploreFetcher.maxPage != -1) {
            page < baseExploreFetcher.maxPage
        } else {
            selectorReturnerStringType(document, baseExploreFetcher.nextPageSelector, baseExploreFetcher.nextPageAtt).isNotBlank()
        }
        return MangasPageInfo(books, next)
    }

    protected open fun parseMangaFromElement(element: Element, fetcher: BaseExploreFetcher): MangaInfo {
        val title = selectorReturnerStringType(element, fetcher.nameSelector, fetcher.nameAtt)
            .trim()
            .let { fetcher.onName(it, fetcher.key) }
        val url = selectorReturnerStringType(element, fetcher.linkSelector, fetcher.linkAtt)
            .trim()
            .let { fetcher.onLink(it, fetcher.key) }
            .let { if (fetcher.addBaseUrlToLink) SourceHelpers.buildAbsoluteUrl(baseUrl, it) else it }
        val cover = selectorReturnerStringType(element, fetcher.coverSelector, fetcher.coverAtt)
            .trim()
            .let { fetcher.onCover(it, fetcher.key) }
            .let { if (fetcher.addBaseurlToCoverLink) SourceHelpers.buildAbsoluteUrl(baseUrl, it) else it }
        return MangaInfo(key = url, title = title, cover = cover)
    }

    override suspend fun getMangaList(sort: Listing?, page: Int): MangasPageInfo {
        val nonSearchFetchers = exploreFetchers.filter { it.type != Type.Search }
        val fetcher = when (sort) {
            is FetcherListing -> nonSearchFetchers.find { it.key == sort.key }
            else -> nonSearchFetchers.firstOrNull()
        } ?: return MangasPageInfo.empty()
        return getLists(fetcher, page, "", emptyList())
    }

    override suspend fun getMangaList(filters: FilterList, page: Int): MangasPageInfo {
        filters.findInstance<Filter.Title>()?.value?.takeIf { it.isNotBlank() }?.let { searchQuery ->
            val searchFetcher = exploreFetchers.firstOrNull { it.type == Type.Search } ?: return MangasPageInfo.empty()
            return getLists(searchFetcher, page, searchQuery, filters)
        }
        return MangasPageInfo.empty()
    }

    open fun chapterFromElement(element: Element): ChapterInfo {
        val link = selectorReturnerStringType(element, chapterFetcher.linkSelector, chapterFetcher.linkAtt)
            .trim()
            .let { chapterFetcher.onLink(it) }
            .let { if (chapterFetcher.addBaseUrlToLink) SourceHelpers.buildAbsoluteUrl(baseUrl, it) else it }
        val name = selectorReturnerStringType(element, chapterFetcher.nameSelector, chapterFetcher.nameAtt)
            .trim()
            .let { chapterFetcher.onName(it) }
        return ChapterInfo(
            key = link,
            name = name,
            dateUpload = selectorReturnerStringType(element, chapterFetcher.uploadDateSelector, chapterFetcher.uploadDateAtt)
                .trim()
                .let { chapterFetcher.uploadDateParser(it) },
            number = selectorReturnerStringType(element, chapterFetcher.numberSelector, chapterFetcher.numberAtt)
                .trim()
                .let { chapterFetcher.onNumber(it) }
                .toFloatOrNull() ?: ChapterInfo.extractChapterNumber(name),
            scanlator = selectorReturnerStringType(element, chapterFetcher.translatorSelector, chapterFetcher.translatorAtt)
                .trim()
                .let { chapterFetcher.onTranslator(it) },
        )
    }

    open fun chaptersParse(document: Document): List<ChapterInfo> {
        val selector = chapterFetcher.selector ?: return emptyList()
        return document.select(selector).mapNotNull { runCatching { chapterFromElement(it) }.getOrNull()?.takeIf { chapter -> chapter.isValid() } }
    }

    open suspend fun getChapterListRequest(manga: MangaInfo, commands: List<Command<*>>): Document {
        return client.get(requestBuilder(manga.key)).asJsoup()
    }

    override suspend fun getChapterList(manga: MangaInfo, commands: List<Command<*>>): List<ChapterInfo> {
        commands.findInstance<Command.Chapter.Fetch>()?.let { return chaptersParse(it.html.asJsoup()).let(::applyChapterSorting) }
        return applyChapterSorting(chaptersParse(getChapterListRequest(manga, commands)))
    }

    protected open fun applyChapterSorting(chapters: List<ChapterInfo>): List<ChapterInfo> {
        return if (chapterFetcher.reverseChapterList) chapters else chapters.reversed()
    }

    open fun statusParser(text: String): Long = detailFetcher.onStatus(text)

    open fun detailParse(document: Document): MangaInfo {
        return MangaInfo(
            key = "",
            title = selectorReturnerStringType(document, detailFetcher.nameSelector, detailFetcher.nameAtt).trim().let { detailFetcher.onName(it) },
            cover = selectorReturnerStringType(document, detailFetcher.coverSelector, detailFetcher.coverAtt)
                .trim()
                .let { detailFetcher.onCover(it) }
                .let { if (detailFetcher.addBaseurlToCoverLink) SourceHelpers.buildAbsoluteUrl(baseUrl, it) else it },
            author = selectorReturnerStringType(document, detailFetcher.authorBookSelector, detailFetcher.authorBookAtt).trim().let { detailFetcher.onAuthor(it) },
            description = selectorReturnerListType(document, detailFetcher.descriptionSelector, detailFetcher.descriptionBookAtt)
                .let { detailFetcher.onDescription(it) }
                .filter { it.isNotBlank() }
                .joinToString("\n\n"),
            genres = selectorReturnerListType(document, detailFetcher.categorySelector, detailFetcher.categoryAtt)
                .let { detailFetcher.onCategory(it) }
                .filter { it.isNotBlank() },
            status = selectorReturnerStringType(document, detailFetcher.statusSelector, detailFetcher.statusAtt).trim().let { statusParser(it) },
        )
    }

    open suspend fun getMangaDetailsRequest(manga: MangaInfo, commands: List<Command<*>>): Document {
        return client.get(requestBuilder(manga.key)).asJsoup()
    }

    override suspend fun getMangaDetails(manga: MangaInfo, commands: List<Command<*>>): MangaInfo {
        commands.findInstance<Command.Detail.Fetch>()?.let { return detailParse(it.html.asJsoup()).copy(key = it.url) }
        return detailParse(getMangaDetailsRequest(manga, commands)).copy(key = manga.key)
    }

    open suspend fun getContentRequest(chapter: ChapterInfo, commands: List<Command<*>>): Document {
        return client.get(requestBuilder(chapter.key)).asJsoup()
    }

    open suspend fun getContents(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        return pageContentParse(getContentRequest(chapter, commands))
    }

    open fun pageContentParse(document: Document): List<Page> {
        val title = selectorReturnerStringType(document, contentFetcher.pageTitleSelector, contentFetcher.pageTitleAtt).trim().let { contentFetcher.onTitle(it) }
        val content = selectorReturnerListType(document, contentFetcher.pageContentSelector, contentFetcher.pageContentAtt)
            .let { contentFetcher.onContent(it) }
            .filter { it.isNotBlank() }
        return buildList {
            if (title.isNotBlank()) add(Text(title))
            addAll(content.map { Text(it) })
        }
    }

    override suspend fun getPageList(chapter: ChapterInfo, commands: List<Command<*>>): List<Page> {
        commands.findInstance<Command.Content.Fetch>()?.let { return pageContentParse(it.html.asJsoup()) }
        return getContents(chapter, commands)
    }

    data class BaseExploreFetcher(
        val key: String,
        val endpoint: String? = null,
        val selector: String? = null,
        val addBaseUrlToLink: Boolean = false,
        val nextPageSelector: String? = null,
        val nextPageAtt: String? = null,
        val nextPageValue: String? = null,
        val addBaseurlToCoverLink: Boolean = false,
        val linkSelector: String? = null,
        val linkAtt: String? = null,
        val onLink: (url: String, key: String) -> String = { url, _ -> url },
        val nameSelector: String? = null,
        val nameAtt: String? = null,
        val onName: (String, key: String) -> String = { name, _ -> name },
        val coverSelector: String? = null,
        val coverAtt: String? = null,
        val onCover: (String, key: String) -> String = { cover, _ -> cover },
        val onQuery: (query: String) -> String = { it },
        val onPage: (page: String) -> String = { it },
        val infinitePage: Boolean = false,
        val maxPage: Int = -1,
        val type: Type = Type.Others,
    )

    data class Detail(
        val addBaseurlToCoverLink: Boolean = false,
        val nameSelector: String? = null,
        val nameAtt: String? = null,
        val onName: (String) -> String = { it },
        val coverSelector: String? = null,
        val coverAtt: String? = null,
        val onCover: (String) -> String = { it },
        val descriptionSelector: String? = null,
        val descriptionBookAtt: String? = null,
        val onDescription: (List<String>) -> List<String> = { it },
        val authorBookSelector: String? = null,
        val authorBookAtt: String? = null,
        val onAuthor: (String) -> String = { it },
        val categorySelector: String? = null,
        val categoryAtt: String? = null,
        val onCategory: (List<String>) -> List<String> = { it },
        val statusSelector: String? = null,
        val statusAtt: String? = null,
        val onStatus: (String) -> Long = { MangaInfo.UNKNOWN },
        val type: Type = Type.Detail,
    )

    data class Chapters(
        val selector: String? = null,
        val addBaseUrlToLink: Boolean = false,
        val reverseChapterList: Boolean = false,
        val linkSelector: String? = null,
        val onLink: (String) -> String = { it },
        val linkAtt: String? = null,
        val nameSelector: String? = null,
        val nameAtt: String? = null,
        val onName: (String) -> String = { it },
        val numberSelector: String? = null,
        val numberAtt: String? = null,
        val onNumber: (String) -> String = { it },
        val uploadDateSelector: String? = null,
        val uploadDateAtt: String? = null,
        val uploadDateParser: (String) -> Long = { 0L },
        val translatorSelector: String? = null,
        val translatorAtt: String? = null,
        val onTranslator: (String) -> String = { it },
        val type: Type = Type.Chapters,
    )

    data class Content(
        val pageTitleSelector: String? = null,
        val pageTitleAtt: String? = null,
        val onTitle: (String) -> String = { it },
        val pageContentSelector: String? = null,
        val pageContentAtt: String? = null,
        val onContent: (List<String>) -> List<String> = { it },
        val type: Type = Type.Content,
    )

    enum class Type { Search, Detail, Chapters, Content, Others }

    open fun selectorReturnerStringType(document: Document, selector: String? = null, att: String? = null): String {
        return try {
            when {
                selector.isNullOrBlank() && !att.isNullOrBlank() -> document.attr(att)
                !selector.isNullOrBlank() && att.isNullOrBlank() -> document.select(selector).text()
                !selector.isNullOrBlank() && !att.isNullOrBlank() -> document.select(selector).attr(att)
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    open fun selectorReturnerStringType(element: Element, selector: String? = null, att: String? = null): String {
        return try {
            when {
                selector.isNullOrBlank() && !att.isNullOrBlank() -> element.attr(att)
                !selector.isNullOrBlank() && att.isNullOrBlank() -> element.select(selector).text()
                !selector.isNullOrBlank() && !att.isNullOrBlank() -> element.select(selector).attr(att)
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    open fun selectorReturnerListType(document: Document, selector: String? = null, att: String? = null): List<String> {
        return try {
            when {
                selector.isNullOrBlank() && !att.isNullOrBlank() -> document.attr(att).takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
                !selector.isNullOrBlank() && att.isNullOrBlank() -> document.select(selector).mapNotNull { it.text().takeIf(String::isNotBlank) }
                !selector.isNullOrBlank() && !att.isNullOrBlank() -> document.select(selector).attr(att).takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

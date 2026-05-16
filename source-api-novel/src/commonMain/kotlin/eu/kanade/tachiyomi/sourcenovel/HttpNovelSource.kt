package eu.kanade.tachiyomi.sourcenovel

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.lang.awaitSingle
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

@Suppress("unused")
abstract class HttpNovelSource : NovelsPageSource {

    protected val network: NetworkHelper by lazy {
        Injekt.get<NetworkHelper>()
    }

    abstract val baseUrl: String

    open val versionId = 1

    override val id by lazy { generateId(name, lang, versionId) }

    open val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    protected open fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    override fun toString() = "$name (${lang.uppercase()})"

    override suspend fun getPopularNovels(page: Int): NovelPage {
        return client.newCall(popularNovelsRequest(page))
            .awaitSuccess()
            .let { response -> popularNovelsParse(response) }
    }

    protected abstract fun popularNovelsRequest(page: Int): Request

    protected abstract fun popularNovelsParse(response: Response): NovelPage

    override suspend fun getSearchNovels(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList): NovelPage {
        return client.newCall(searchNovelsRequest(page, query, filters))
            .awaitSuccess()
            .let { response -> searchNovelsParse(response) }
    }

    protected abstract fun searchNovelsRequest(
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.source.model.FilterList,
    ): Request

    protected abstract fun searchNovelsParse(response: Response): NovelPage

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        return client.newCall(latestUpdatesRequest(page))
            .awaitSuccess()
            .let { response -> latestUpdatesParse(response) }
    }

    protected abstract fun latestUpdatesRequest(page: Int): Request

    protected abstract fun latestUpdatesParse(response: Response): NovelPage

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        return client.newCall(novelDetailsRequest(novel))
            .awaitSuccess()
            .let { response ->
                novelDetailsParse(response).apply { initialized = true }
            }
    }

    open fun novelDetailsRequest(novel: SNNovel): Request {
        return GET(baseUrl + novel.url, headers)
    }

    protected abstract fun novelDetailsParse(response: Response): SNNovel

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        return client.newCall(chapterListRequest(novel))
            .awaitSuccess()
            .let { response -> chapterListParse(response) }
    }

    protected open fun chapterListRequest(novel: SNNovel): Request {
        return GET(baseUrl + novel.url, headers)
    }

    protected abstract fun chapterListParse(response: Response): List<SNChapter>

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        return client.newCall(chapterContentRequest(chapter))
            .awaitSuccess()
            .let { response -> chapterContentParse(response) }
    }

    protected open fun chapterContentRequest(chapter: SNChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    protected abstract fun chapterContentParse(response: Response): ChapterContent

    override fun getFilterList() = eu.kanade.tachiyomi.source.model.FilterList()

    fun SNChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SNNovel.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = java.net.URI(orig.replace(" ", "%20"))
            var out = uri.path
            if (uri.query != null) {
                out += "?" + uri.query
            }
            if (uri.fragment != null) {
                out += "#" + uri.fragment
            }
            out
        } catch (e: java.net.URISyntaxException) {
            orig
        }
    }

    open fun getNovelUrl(novel: SNNovel): String {
        return novelDetailsRequest(novel).url.toString()
    }

    open fun getChapterUrl(chapter: SNChapter): String {
        return chapterContentRequest(chapter).url.toString()
    }

    open fun prepareNewChapter(chapter: SNChapter, novel: SNNovel) {}
}

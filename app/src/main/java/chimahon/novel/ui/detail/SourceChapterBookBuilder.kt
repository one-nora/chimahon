package chimahon.novel.ui.detail

import android.content.Context
import com.canopus.chimareader.data.Bookmark
import com.canopus.chimareader.data.BookMetadata
import com.canopus.chimareader.data.BookStorage
import com.canopus.chimareader.data.FileNames
import com.canopus.chimareader.data.epub.EpubBook
import com.canopus.chimareader.data.epub.EpubManifest
import com.canopus.chimareader.data.epub.EpubSpine
import com.canopus.chimareader.data.epub.ManifestItem
import com.canopus.chimareader.data.epub.SpineItem
import com.canopus.chimareader.data.epub.EpubMediaType
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.ContentItem
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import java.io.File

object SourceChapterBookBuilder {

    private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val CACHE_MAX_ENTRIES = 50

    private val SAFELIST = Safelist.relaxed()
        .addTags("ruby", "rt", "rp", "sup", "sub")
        .addAttributes("img", "src", "alt", "width", "height", "style")
        .addAttributes("a", "href", "title", "rel")
        .addAttributes(":all", "style", "class", "id", "lang", "dir", "title")
        .addProtocols("img", "src", "http", "https", "data")
        .addProtocols("a", "href", "http", "https", "mailto")

    fun cleanUpOldCache(context: Context) {
        val cacheDir = BookStorage.getBooksDirectory(context)
        if (!cacheDir.exists()) return
        val entries = cacheDir.listFiles()?.filter { it.name.startsWith("src_") }?.toList() ?: return
        if (entries.size <= CACHE_MAX_ENTRIES) return
        val toDelete = entries
            .sortedBy { it.lastModified() }
            .take(entries.size - CACHE_MAX_ENTRIES)
        for (dir in toDelete) {
            dir.deleteRecursively()
        }
    }

    fun cleanUpExpiredEntries(context: Context) {
        val cacheDir = BookStorage.getBooksDirectory(context)
        if (!cacheDir.exists()) return
        val now = System.currentTimeMillis()
        cacheDir.listFiles()?.filter { it.name.startsWith("src_") }?.forEach { dir ->
            if (dir.isDirectory && now - dir.lastModified() > CACHE_MAX_AGE_MS) {
                dir.deleteRecursively()
            }
        }
    }

    private fun String.escapeXml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun String.escapeUrl(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "%22")

    suspend fun buildSingleChapter(
        context: Context,
        source: NovelSource,
        novel: SNNovel,
        chapter: SNChapter,
    ): File {
        val bookId = "src_${source.id}_${novel.title.hashCode()}"
        val bookDir = BookStorage.getBookDirectory(context, bookId)

        if (bookDir.exists()) bookDir.deleteRecursively()
        bookDir.mkdirs()

        val content = source.getChapterContent(chapter)
        val xhtmlContent = chapterContentToXhtml(content)

        File(bookDir, "chapter_0.xhtml").writeText(xhtmlContent, Charsets.UTF_8)

        val manifestItems = mapOf(
            "chapter_0" to ManifestItem("chapter_0", "chapter_0.xhtml", EpubMediaType.XHTML),
        )

        val epubBook = EpubBook(
            title = novel.title,
            author = novel.author,
            manifest = EpubManifest(items = manifestItems),
            spine = EpubSpine(items = listOf(SpineItem(idref = "chapter_0"))),
            extractedDir = bookDir,
        )

        BookStorage.save(
            BookMetadata(id = bookId, title = novel.title, author = novel.author, folder = bookId),
            bookDir, FileNames.metadata,
        )
        return bookDir
    }

    suspend fun build(
        context: Context,
        source: NovelSource,
        novel: SNNovel,
        chapters: List<SNChapter>,
        startChapterIndex: Int = 0,
    ): File {
        val bookId = "src_${source.id}_${novel.title.hashCode()}"
        val bookDir = BookStorage.getBookDirectory(context, bookId)

        if (bookDir.exists()) bookDir.deleteRecursively()
        bookDir.mkdirs()

        cleanUpOldCache(context)

        val hrefs = mutableMapOf<String, ManifestItem>()
        val semaphore = Semaphore(4)

        coroutineScope {
            val deferred = chapters.mapIndexed { index, chapter ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val content = source.getChapterContent(chapter)
                        val id = "chapter_$index"
                        val href = "$id.xhtml"
                        val xhtml = chapterContentToXhtml(content)
                        File(bookDir, href).writeText(xhtml, Charsets.UTF_8)
                        ManifestItem(id, href, EpubMediaType.XHTML)
                    } finally {
                        semaphore.release()
                    }
                }
            }
            deferred.awaitAll().forEach { hrefs[it.id] = it }
        }

        val spineItems = chapters.indices.map { SpineItem(idref = "chapter_$it") }

        val epubBook = EpubBook(
            title = novel.title,
            author = novel.author,
            manifest = EpubManifest(items = hrefs),
            spine = EpubSpine(items = spineItems),
            extractedDir = bookDir,
        )

        BookStorage.save(
            BookMetadata(id = bookId, title = novel.title, author = novel.author, folder = bookId),
            bookDir, FileNames.metadata,
        )
        if (startChapterIndex in chapters.indices) {
            BookStorage.save(
                Bookmark(
                    chapterIndex = startChapterIndex,
                    progress = 0.0,
                    characterCount = 0,
                    lastModified = System.currentTimeMillis(),
                ),
                bookDir,
                FileNames.bookmark,
            )
        }

        return bookDir
    }

    private fun chapterContentToXhtml(content: ChapterContent): String = when (content) {
        is ChapterContent.Text -> buildChapterXhtml(content.fullText())
        is ChapterContent.Html -> buildHtmlChapterXhtml(content.html)
        is ChapterContent.Images -> buildImageChapterXhtml(content.urls)
        is ChapterContent.Mixed -> buildMixedChapterXhtml(content.items)
    }

    private fun buildChapterXhtml(text: String): String = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body>
${text.escapeXml().replace("\n\n", "</p><p>").let { "<p>$it</p>" }}
</body>
</html>"""

    private fun buildHtmlChapterXhtml(html: String): String {
        val sanitized = Jsoup.clean(html, SAFELIST)
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body>
$sanitized
</body>
</html>"""
    }

    private fun buildImageChapterXhtml(imageUrls: List<String>): String {
        val images = imageUrls.joinToString("\n") { url ->
            """<div style="text-align:center;margin:0;page-break-after:always;"><img src="${url.escapeUrl()}" style="max-width:100%;height:auto;object-fit:contain;"/></div>"""
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body>
$images
</body>
</html>"""
    }

    private fun buildMixedChapterXhtml(items: List<ContentItem>): String {
        val body = items.joinToString("\n") { item ->
            when (item) {
                is ContentItem.Text -> item.text.escapeXml()
                is ContentItem.Image -> """<div style="text-align:center;"><img src="${item.url.escapeUrl()}" style="max-width:100%;height:auto;"/></div>"""
            }
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body>
$body
</body>
</html>"""
    }
}

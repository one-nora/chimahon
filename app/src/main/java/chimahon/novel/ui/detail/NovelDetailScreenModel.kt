package chimahon.novel.ui.detail

import android.content.Context
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.canopus.chimareader.data.BookStorage
import com.canopus.chimareader.data.FileNames
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.launch
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

data class NovelChapterItem(
    val index: Int,
    val snChapter: SNChapter,
    val novelChapter: NovelChapter? = null,
    val selected: Boolean = false,
) {
    val id: Long get() = novelChapter?.id ?: -(index + 1).toLong()
    val isRead: Boolean get() = novelChapter?.read ?: false
    val lastPageRead: Long get() = novelChapter?.lastPageRead ?: 0
    val isBookmarked: Boolean get() = novelChapter?.bookmark ?: false
}

sealed interface Dialog {
    data object DeleteChapters : Dialog
}

@Immutable
data class NovelDetailState(
    val novel: SNNovel = SNNovel.create(),
    val dbNovel: Novel? = null,
    val source: NovelSource? = null,
    val chapters: List<NovelChapterItem> = emptyList(),
    val isLoading: Boolean = true,
    val isFavorite: Boolean = false,
    val error: String? = null,
    val dialog: Dialog? = null,
    val selectedChapters: Set<Long> = emptySet(),
    val selectionMode: Boolean = false,
)

class NovelDetailScreenModel(
    private val novel: SNNovel,
    private val source: NovelSource,
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
) : StateScreenModel<NovelDetailState>(NovelDetailState(isLoading = true)) {

    private var cachedDbNovel: Novel? = null
    private var cachedChapters: List<SNChapter>? = null

    init {
        loadDetails()
        observeFavoriteStatus()
    }

    fun resume() {
        screenModelScope.launch {
            syncBookmarkToDb()
        }
    }

    private fun loadDetails() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true)
            try {
                val details = source.getNovelDetails(novel)
                val chapters = source.getChapterList(novel)
                cachedChapters = chapters

                val items = chapters.mapIndexed { index, ch ->
                    NovelChapterItem(index = index, snChapter = ch)
                }

                mutableState.value = mutableState.value.copy(
                    novel = details,
                    chapters = items,
                    isLoading = false,
                )
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = e.message,
                )
            }
        }
    }

    private fun observeFavoriteStatus() {
        screenModelScope.launch {
            val existing = novelRepository.getNovelByUrlAndSourceId(novel.url, source.id)
            if (existing != null) {
                cachedDbNovel = existing
                mutableState.value = mutableState.value.copy(isFavorite = true)
                subscribeToDbChapters(existing.id)
            }
        }
    }

    private fun subscribeToDbChapters(novelId: Long) {
        screenModelScope.launch {
            novelChapterRepository.getChaptersByNovelIdAsFlow(novelId).collect { dbChapters ->
                val merged = mutableState.value.chapters.map { item ->
                    val match = dbChapters.find { it.url == item.snChapter.url }
                    item.copy(novelChapter = match)
                }
                mutableState.value = mutableState.value.copy(chapters = merged)
            }
        }
    }

    fun toggleFavorite() {
        screenModelScope.launch {
            if (mutableState.value.isFavorite) {
                removeFromLibrary()
            } else {
                addToLibrary()
            }
        }
    }

    private suspend fun addToLibrary() {
        val domainNovel = Novel.fromSourceNovel(novel, source.id)
        val novelId = novelRepository.insert(domainNovel)

        val sourceChapters = cachedChapters ?: source.getChapterList(novel)
        val dbChapters = sourceChapters.mapIndexed { index, snCh ->
            NovelChapter.create().copy(
                novelId = novelId,
                url = snCh.url,
                name = snCh.name,
                scanlator = snCh.scanlator,
                sourceOrder = index.toLong(),
                chapterNumber = snCh.chapter_number,
                dateFetch = System.currentTimeMillis(),
                dateUpload = snCh.date_upload,
            )
        }
        novelChapterRepository.insertAll(dbChapters)

        cachedDbNovel = novelRepository.getNovelById(novelId)
        mutableState.value = mutableState.value.copy(isFavorite = true)
        subscribeToDbChapters(novelId)
    }

    private suspend fun removeFromLibrary() {
        val existing = novelRepository.getNovelByUrlAndSourceId(novel.url, source.id) ?: return
        novelRepository.deleteNovel(existing.id)
        cachedDbNovel = null
        val cleared = mutableState.value.chapters.map { it.copy(novelChapter = null) }
        mutableState.value = mutableState.value.copy(
            isFavorite = false,
            chapters = cleared,
        )
    }

    fun getNextUnreadChapter(): NovelChapterItem? {
        return mutableState.value.chapters.firstOrNull { !it.isRead }
    }

    fun markChapterRead(chapter: NovelChapterItem) {
        val dbChapter = chapter.novelChapter ?: return
        screenModelScope.launch {
            novelChapterRepository.update(
                NovelChapterUpdate(
                    id = dbChapter.id,
                    read = true,
                    lastPageRead = 0,
                )
            )
        }
    }

    fun markChapterUnread(chapter: NovelChapterItem) {
        val dbChapter = chapter.novelChapter ?: return
        screenModelScope.launch {
            novelChapterRepository.update(
                NovelChapterUpdate(
                    id = dbChapter.id,
                    read = false,
                    lastPageRead = 0,
                )
            )
        }
    }

    fun markSelectedChaptersRead(read: Boolean) {
        val selected = mutableState.value.selectedChapters
        screenModelScope.launch {
            mutableState.value.chapters
                .filter { it.id in selected }
                .mapNotNull { it.novelChapter }
                .forEach { dbCh ->
                    novelChapterRepository.update(
                        NovelChapterUpdate(
                            id = dbCh.id,
                            read = read,
                            lastPageRead = if (read) 0 else dbCh.lastPageRead,
                        )
                    )
                }
        }
    }

    fun toggleChapterSelection(chapterId: Long) {
        val current = mutableState.value.selectedChapters.toMutableSet()
        if (chapterId in current) current.remove(chapterId) else current.add(chapterId)
        mutableState.value = mutableState.value.copy(
            selectedChapters = current,
            selectionMode = current.isNotEmpty(),
        )
    }

    fun clearSelection() {
        mutableState.value = mutableState.value.copy(
            selectedChapters = emptySet(),
            selectionMode = false,
        )
    }

    fun selectAll() {
        mutableState.value = mutableState.value.copy(
            selectedChapters = mutableState.value.chapters.map { it.id }.toSet(),
            selectionMode = true,
        )
    }

    fun invertSelection() {
        val all = mutableState.value.chapters.map { it.id }.toSet()
        val current = mutableState.value.selectedChapters
        val inverted = all - current
        mutableState.value = mutableState.value.copy(
            selectedChapters = inverted,
            selectionMode = inverted.isNotEmpty(),
        )
    }

    fun showDialog(dialog: Dialog) {
        mutableState.value = mutableState.value.copy(dialog = dialog)
    }

    fun dismissDialog() {
        mutableState.value = mutableState.value.copy(dialog = null)
    }

    private suspend fun syncBookmarkToDb() {
        val sourceChapters = cachedChapters ?: run {
            try {
                source.getChapterList(mutableState.value.novel)
            } catch (_: Exception) { return }
        }

        val bookId = "src_${source.id}_${mutableState.value.novel.title.hashCode()}"
        val context: Context = Injekt.get()
        val bookDir = File(context.cacheDir, "source_books/$bookId")
        if (!bookDir.exists()) return

        val bookmark = BookStorage.loadBookmark(bookDir) ?: return
        val dbNovel = cachedDbNovel ?: return
        if (bookmark.chapterIndex < 0 || bookmark.chapterIndex >= sourceChapters.size) return

        val snChapter = sourceChapters[bookmark.chapterIndex]
        val dbChapter = novelChapterRepository.getChapterByUrlAndNovelId(snChapter.url, dbNovel.id) ?: return
        val progressInt = (bookmark.progress * 100).toLong()

        novelChapterRepository.update(
            NovelChapterUpdate(
                id = dbChapter.id,
                read = true,
                lastPageRead = progressInt,
            )
        )
    }
}

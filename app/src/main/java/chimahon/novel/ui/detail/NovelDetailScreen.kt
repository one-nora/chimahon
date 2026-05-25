package chimahon.novel.ui.detail

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.canopus.chimareader.ui.reader.NovelReaderActivity
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

data class NovelDetailScreen(
    private val novel: SNNovel,
    private val source: NovelSource,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val screenModel = rememberScreenModel { NovelDetailScreenModel(novel, source) }
        val state by screenModel.state.collectAsState()

        scope.launch { screenModel.resume() }

        val chapterListState = rememberLazyListState()
        val chapters = state.chapters
        val isAnySelected = state.selectionMode
        val hasUnread = remember(chapters) { chapters.fastAny { !it.isRead } }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.novel.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            floatingActionButton = {
                if (hasUnread && !isAnySelected) {
                    SmallFloatingActionButton(
                        onClick = {
                            val next = screenModel.getNextUnreadChapter()
                            if (next != null) {
                                openChapter(context, source, state.novel, next, chapters, screenModel, scope)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(MR.strings.action_start))
                    }
                }
            },
        ) { contentPadding ->
            when {
                state.isLoading -> LoadingScreen(modifier = Modifier.padding(contentPadding))
                state.error != null -> EmptyScreen(
                    message = state.error ?: "Error",
                    modifier = Modifier.padding(contentPadding),
                )
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = chapterListState,
                        contentPadding = contentPadding,
                    ) {
                        item(key = "novel_header") {
                            NovelHeader(
                                novel = state.novel,
                                isFavorite = state.isFavorite,
                                onToggleFavorite = screenModel::toggleFavorite,
                            )
                        }

                        if (!state.novel.description.isNullOrBlank()) {
                            item(key = "novel_description") {
                                Text(
                                    text = state.novel.description!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }

                        item(key = "novel_chapter_header") {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                                Text(
                                    text = "Chapters (${chapters.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(4.dp))
                                HorizontalDivider()
                            }
                        }

                        if (chapters.isEmpty()) {
                            item(key = "novel_no_chapters") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "No chapters",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            items(chapters, key = { "ch_${it.snChapter.url}" }) { item ->
                                val readProgressText = if (item.lastPageRead > 0 && !item.isRead) {
                                    stringResource(MR.strings.chapter_progress, item.lastPageRead)
                                } else null

                                MangaChapterListItem(
                                    title = item.snChapter.name,
                                    date = if (item.snChapter.date_upload > 0) {
                                        relativeDateText(item.snChapter.date_upload)
                                    } else null,
                                    readProgress = readProgressText,
                                    scanlator = item.snChapter.scanlator,
                                    sourceName = null,
                                    read = item.isRead,
                                    bookmark = item.isBookmarked,
                                    selected = item.id in state.selectedChapters,
                                    downloadIndicatorEnabled = false,
                                    downloadStateProvider = { Download.State.NOT_DOWNLOADED },
                                    downloadProgressProvider = { 0 },
                                    chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                                    chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                                    onLongClick = {
                                        if (state.isFavorite) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            screenModel.toggleChapterSelection(item.id)
                                        }
                                    },
                                    onClick = {
                                        openChapter(context, source, state.novel, item, chapters, screenModel, scope)
                                    },
                                    onDownloadClick = null,
                                    onChapterSwipe = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelHeader(
    novel: SNNovel,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = novel.title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (!novel.author.isNullOrBlank()) {
                    Text(
                        text = novel.author!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onToggleFavorite,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isFavorite) "In Library" else "Add to Library")
            }
        }

        val statusText = when (novel.status) {
            SNNovel.ONGOING -> "Ongoing"
            SNNovel.COMPLETED -> "Completed"
            SNNovel.LICENSED -> "Licensed"
            SNNovel.PUBLISHING_FINISHED -> "Publishing Finished"
            SNNovel.CANCELLED -> "Cancelled"
            SNNovel.ON_HIATUS -> "On Hiatus"
            else -> "Unknown"
        }
        Text(
            text = "Status: $statusText",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun openChapter(
    context: Context,
    source: NovelSource,
    novel: SNNovel,
    item: NovelChapterItem,
    chapters: List<NovelChapterItem>,
    screenModel: NovelDetailScreenModel,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    scope.launch {
        try {
            val chapterIndex = chapters.indexOf(item)
            if (chapterIndex >= 0 && chapters.size > 1) {
                val bookDir = SourceChapterBookBuilder.build(
                    context = context,
                    source = source,
                    novel = novel,
                    chapters = chapters.map { it.snChapter },
                    startChapterIndex = chapterIndex,
                )
                NovelReaderActivity.launch(context, bookDir)
            } else {
                val bookDir = SourceChapterBookBuilder.buildSingleChapter(
                    context = context,
                    source = source,
                    novel = novel,
                    chapter = item.snChapter,
                )
                NovelReaderActivity.launch(context, bookDir)
            }
        } catch (_: Exception) {
        }
    }
}

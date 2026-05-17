package tachiyomi.data.novel

import tachiyomi.domain.novel.model.NovelChapter

object NovelChapterMapper {
    fun mapChapter(
        _id: Long,
        novel_id: Long,
        url: String,
        name: String,
        scanlator: String?,
        read: Boolean,
        bookmark: Boolean,
        last_page_read: Long,
        chapter_number: Float,
        source_order: Long,
        date_fetch: Long,
        date_upload: Long,
        last_modified_at: Long,
        version: Long,
    ): NovelChapter = NovelChapter(
        id = _id,
        novelId = novel_id,
        url = url,
        name = name,
        scanlator = scanlator,
        read = read,
        bookmark = bookmark,
        lastPageRead = last_page_read,
        chapterNumber = chapter_number,
        sourceOrder = source_order,
        dateFetch = date_fetch,
        dateUpload = date_upload,
        lastModifiedAt = last_modified_at,
        version = version,
    )
}

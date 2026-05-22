package eu.kanade.tachiyomi.data.track.mangabaka.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MangabakaLibraryEntryResponse(
    val status: Long,
    val data: MangabakaLibraryEntry? = null,
)

@Serializable
data class MangabakaLibraryEntryCreateResponse(
    val status: Long,
    val data: Boolean? = null,
)

@Serializable
data class MangabakaLibraryEntry(
    val id: Long? = null,
    @SerialName("series_id")
    val seriesId: Long? = null,
    @SerialName("user_id")
    val userId: String? = null,
    val state: String? = null,
    @SerialName("progress_chapter")
    val progressChapter: Double? = null,
    @SerialName("progress_volume")
    val progressVolume: Double? = null,
    val rating: Double? = null,
    @SerialName("start_date")
    val startDate: String? = null,
    @SerialName("finish_date")
    val finishDate: String? = null,
    @SerialName("is_private")
    val isPrivate: Boolean? = null,
    val note: String? = null,
    @SerialName("read_link")
    val readLink: String? = null,
    val priority: Double? = null,
    @SerialName("number_of_rereads")
    val numberOfRereads: Double? = null,
)

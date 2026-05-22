package eu.kanade.tachiyomi.data.track.mangabaka.dto

import kotlinx.serialization.Serializable

@Serializable
data class MangabakaSearchResponse(
    val status: Long,
    val data: List<MangabakaSeries>,
    val pagination: MangabakaPagination? = null,
)

@Serializable
data class MangabakaSeriesResponse(
    val status: Long,
    val data: MangabakaSeries,
)

@Serializable
data class MangabakaSeries(
    val id: Long,
    val title: String? = null,
    val source: MangabakaSource? = null,
    val titles: Map<String, List<MangabakaTitle>>? = null,
)

@Serializable
data class MangabakaSource(
    val anilist: MangabakaSourceId? = null,
    @kotlinx.serialization.SerialName("my_anime_list")
    val myAnimeList: MangabakaSourceId? = null,
    val kitsu: MangabakaSourceId? = null,
    @kotlinx.serialization.SerialName("manga_updates")
    val mangaUpdates: MangabakaSourceId? = null,
    val shikimori: MangabakaSourceId? = null,
    @kotlinx.serialization.SerialName("anime_planet")
    val animePlanet: MangabakaSourceId? = null,
    @kotlinx.serialization.SerialName("anime_news_network")
    val animeNewsNetwork: MangabakaSourceId? = null,
)

@Serializable
data class MangabakaSourceId(
    val id: String? = null,
    val slug: String? = null,
    val url: String? = null,
)

@Serializable
data class MangabakaTitle(
    val type: String,
    val title: String,
    val note: String? = null,
)

@Serializable
data class MangabakaPagination(
    val count: Long,
    val page: Long,
    val limit: Long,
    val next: Long? = null,
    val previous: Long? = null,
)

@Serializable
data class MangabakaErrorResponse(
    val status: Long,
    val message: String? = null,
    val reason: String? = null,
)

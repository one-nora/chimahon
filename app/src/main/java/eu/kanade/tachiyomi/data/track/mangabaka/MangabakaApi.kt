package eu.kanade.tachiyomi.data.track.mangabaka

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangabakaErrorResponse
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangabakaLibraryEntry
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangabakaLibraryEntryCreateResponse
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangabakaLibraryEntryResponse
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangabakaProfile
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangabakaProfileResponse
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangabakaSearchResponse
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangabakaSeries
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangabakaSeriesResponse
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

class MangabakaApi(private val client: OkHttpClient, interceptor: MangabakaInterceptor, private val trackerId: Long) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder()
        .addInterceptor(interceptor)
        .build()

    suspend fun getProfile(): MangabakaProfile {
        return withIOContext {
            with(json) {
                val response = authClient.newCall(GET("$BASE_URL/my/profile"))
                    .awaitSuccess()
                    .parseAs<MangabakaProfileResponse>()
                response.data
            }
        }
    }

    suspend fun search(query: String): List<TrackSearch> {
        return withIOContext {
            with(json) {
                val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                val response = authClient.newCall(
                    GET("$BASE_URL/series/search?q=$encodedQuery"),
                )
                    .awaitSuccess()
                    .parseAs<MangabakaSearchResponse>()

                response.data.map { series ->
                    TrackSearch.create(trackerId).apply {
                        remote_id = series.id
                        title = series.title ?: ""
                        tracking_url = "${MANGA_URL}${series.id}"
                    }
                }
            }
        }
    }

    suspend fun getSeries(seriesId: Long): MangabakaSeries {
        return withIOContext {
            with(json) {
                val response = authClient.newCall(GET("$BASE_URL/series/$seriesId"))
                    .awaitSuccess()
                    .parseAs<MangabakaSeriesResponse>()
                response.data
            }
        }
    }

    suspend fun getLibraryEntry(seriesId: Long): MangabakaLibraryEntry? {
        return withIOContext {
            with(json) {
                val response = authClient.newCall(GET("$BASE_URL/my/library/$seriesId"))
                    .awaitSuccess()
                    .parseAs<MangabakaLibraryEntryResponse>()
                response.data
            }
        }
    }

    suspend fun createLibraryEntry(track: Track, apiState: String): Long {
        return withIOContext {
            val body = buildCreateUpdateBody(track, apiState)
            with(json) {
                authClient.newCall(
                    POST(
                        "$BASE_URL/my/library/${track.remote_id}",
                        headers = headersOf("Content-Type", JSON_MEDIA_TYPE),
                        body = body.toRequestBody(JSON_MEDIA),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<MangabakaLibraryEntryCreateResponse>()

                track.remote_id
            }
        }
    }

    suspend fun updateLibraryEntry(track: Track, apiState: String?) {
        withIOContext {
            val body = buildCreateUpdateBody(track, apiState)
            with(json) {
                authClient.newCall(
                    PUT(
                        "$BASE_URL/my/library/${track.remote_id}",
                        headers = headersOf("Content-Type", JSON_MEDIA_TYPE),
                        body = body.toRequestBody(JSON_MEDIA),
                    ),
                )
                    .awaitSuccess()
            }
        }
    }

    suspend fun deleteLibraryEntry(seriesId: Long) {
        withIOContext {
            authClient.newCall(DELETE("$BASE_URL/my/library/$seriesId"))
                .awaitSuccess()
        }
    }

    private fun buildCreateUpdateBody(track: Track, apiState: String?): String {
        return buildJsonObject {
            if (apiState != null) {
                put("state", apiState)
            }
            put("progress_chapter", track.last_chapter_read.toInt())
            val rating = track.score
            if (rating > 0.0) {
                put("rating", (rating * 10).toInt())
            }
            if (track.started_reading_date > 0) {
                put("start_date", Instant.ofEpochMilli(track.started_reading_date).toString())
            }
            if (track.finished_reading_date > 0) {
                put("finish_date", Instant.ofEpochMilli(track.finished_reading_date).toString())
            }
            put("is_private", track.private)
        }.toString()
    }

    companion object {
        private const val BASE_URL = "https://api.mangabaka.dev/v1"
        private const val MANGA_URL = "https://mangabaka.org/series/"

        private const val JSON_MEDIA_TYPE = "application/json"
        private val JSON_MEDIA = JSON_MEDIA_TYPE.toMediaType()
    }
}

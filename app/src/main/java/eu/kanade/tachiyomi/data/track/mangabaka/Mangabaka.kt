package eu.kanade.tachiyomi.data.track.mangabaka

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

class Mangabaka(id: Long) : BaseTracker(id, "MangaBaka"), DeletableTracker {

    companion object {
        const val CONSIDERING = 1L
        const val PLAN_TO_READ = 2L
        const val READING = 3L
        const val REREADING = 4L
        const val PAUSED = 5L
        const val DROPPED = 6L
        const val COMPLETED = 7L
    }

    override val supportsReadingDates: Boolean = true

    override val supportsPrivateTracking: Boolean = true

    private val interceptor by lazy { MangabakaInterceptor(this) }

    private val api by lazy { MangabakaApi(client, interceptor, id) }

    override fun getLogo() = R.drawable.brand_mangabaka

    override fun getStatusList(): List<Long> {
        return listOf(CONSIDERING, PLAN_TO_READ, READING, REREADING, PAUSED, DROPPED, COMPLETED)
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        CONSIDERING -> MR.strings.considering
        PLAN_TO_READ -> MR.strings.plan_to_read
        READING -> MR.strings.reading
        REREADING -> MR.strings.repeating
        PAUSED -> MR.strings.on_hold
        DROPPED -> MR.strings.dropped
        COMPLETED -> MR.strings.completed
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = REREADING

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> {
        return (0..10).map { it.toString() }.toImmutableList()
    }

    override fun indexToScore(index: Int): Double {
        return index.toDouble()
    }

    override fun displayScore(track: DomainTrack): String {
        return track.score.toInt().toString()
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        val apiState = track.toApiState()

        if (track.status != COMPLETED) {
            if (didReadChapter) {
                if (track.last_chapter_read.toLong() == track.total_chapters && track.total_chapters > 0) {
                    track.status = COMPLETED
                    track.finished_reading_date = System.currentTimeMillis()
                } else {
                    track.status = READING
                    if (track.last_chapter_read == 1.0) {
                        track.started_reading_date = System.currentTimeMillis()
                    }
                }
            }
        }

        api.updateLibraryEntry(track, apiState)
        return track
    }

    override suspend fun delete(track: DomainTrack) {
        api.deleteLibraryEntry(track.remoteId)
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val remoteTrack = try {
            api.getLibraryEntry(track.remote_id)
        } catch (_: Exception) {
            null
        }

        return if (remoteTrack != null) {
            track.copyFromRemote(remoteTrack)
            if (track.status != COMPLETED) {
                track.status = if (hasReadChapters) READING else track.status
            }
            api.updateLibraryEntry(track, track.toApiState())
            track
        } else {
            track.status = if (hasReadChapters) READING else CONSIDERING
            track.score = 0.0
            api.createLibraryEntry(track, track.toApiState())
            track
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return api.search(query)
    }

    override suspend fun refresh(track: Track): Track {
        val remoteTrack = api.getLibraryEntry(track.remote_id)

        if (remoteTrack != null) {
            track.copyFromRemote(remoteTrack)
        }

        return track
    }

    override suspend fun login(username: String, password: String) {
        interceptor.newAuth(password)
        val profile = api.getProfile()
        saveCredentials(profile.id, password)
    }

    override fun logout() {
        super.logout()
        interceptor.newAuth(null)
    }

    override fun hasNotStartedReading(status: Long): Boolean {
        return status == CONSIDERING || status == PLAN_TO_READ
    }

    override suspend fun searchById(id: String): TrackSearch? {
        return try {
            val seriesId = id.toLong()
            val series = api.getSeries(seriesId)
            TrackSearch.create(this.id).also {
                it.remote_id = series.id
                it.title = series.title ?: ""
                it.tracking_url = "https://mangabaka.org/series/${series.id}"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun Track.toApiState(): String = when (status) {
        CONSIDERING -> "considering"
        PLAN_TO_READ -> "plan_to_read"
        READING -> "reading"
        REREADING -> "rereading"
        PAUSED -> "paused"
        DROPPED -> "dropped"
        COMPLETED -> "completed"
        else -> "considering"
    }

    private fun Track.copyFromRemote(remote: eu.kanade.tachiyomi.data.track.mangabaka.dto.MangabakaLibraryEntry) {
        remote.state?.let { state ->
            status = when (state) {
                "considering" -> CONSIDERING
                "plan_to_read" -> PLAN_TO_READ
                "reading" -> READING
                "rereading" -> REREADING
                "paused" -> PAUSED
                "dropped" -> DROPPED
                "completed" -> COMPLETED
                else -> CONSIDERING
            }
        }
        remote.progressChapter?.let {
            last_chapter_read = it
        }
        remote.rating?.let {
            score = it / 10.0
        }
        remote.startDate?.let {
            started_reading_date = System.currentTimeMillis()
        }
        remote.finishDate?.let {
            finished_reading_date = System.currentTimeMillis()
        }
        remote.isPrivate?.let {
            private = it
        }
        remote.id?.let {
            library_id = it
        }
    }
}

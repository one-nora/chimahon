@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.sourcenovel.model

import java.io.Serializable

interface SNNovel : Serializable {

    var url: String

    var title: String

    var author: String?

    var artist: String?

    var description: String?

    var genre: String?

    var status: Int

    var thumbnail_url: String?

    var initialized: Boolean

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    fun copy() = create().also {
        it.url = url
        it.title = title
        it.author = author
        it.artist = artist
        it.thumbnail_url = thumbnail_url
        it.description = description
        it.genre = genre
        it.status = status
        it.initialized = initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SNNovel {
            return SNNovelImpl()
        }

        operator fun invoke(
            url: String,
            title: String,
            author: String? = null,
            artist: String? = null,
            description: String? = null,
            genre: String? = null,
            status: Int = 0,
            thumbnail_url: String? = null,
            initialized: Boolean = false,
        ): SNNovel {
            return create().also {
                it.url = url
                it.title = title
                it.author = author
                it.artist = artist
                it.description = description
                it.genre = genre
                it.status = status
                it.thumbnail_url = thumbnail_url
                it.initialized = initialized
            }
        }
    }
}

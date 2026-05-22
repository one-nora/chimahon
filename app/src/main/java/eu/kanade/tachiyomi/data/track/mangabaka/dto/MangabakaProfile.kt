package eu.kanade.tachiyomi.data.track.mangabaka.dto

import kotlinx.serialization.Serializable

@Serializable
data class MangabakaProfileResponse(
    val status: Long,
    val data: MangabakaProfile,
)

@Serializable
data class MangabakaProfile(
    val id: String,
    val nickname: String? = null,
    val preferred_username: String? = null,
    val role: String? = null,
    val auth_type: String? = null,
    val scopes: List<String>? = null,
    val rating_steps: Int? = null,
)

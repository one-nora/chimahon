package chimahon.plugin.api

import kotlinx.serialization.Serializable

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val description: String,
    val author: PluginAuthor,
    val type: PluginType,
    val permissions: List<PluginPermission>,
    val minAppVersion: String,
    val mainClass: String? = null,
    val iconUrl: String? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class PluginAuthor(
    val name: String,
    val email: String? = null,
    val website: String? = null
)

@Serializable
enum class PluginType {
    THEME,
    TRANSLATION,
    TTS,
    FEATURE,
    AI,
    CATALOG,
    IMAGE_PROCESSING,
    SYNC,
    COMMUNITY_SCREEN,
    GLOSSARY,
    SOURCE_LOADER,
    CLOUDFLARE_BYPASS
}

@Serializable
enum class PluginPermission {
    NETWORK,
    STORAGE,
    READER_CONTEXT,
    LIBRARY_ACCESS,
    PREFERENCES,
    NOTIFICATIONS,
    CATALOG_WRITE,
    SYNC_DATA,
    BACKGROUND_SERVICE,
    LOCAL_SERVER,
    IMAGE_PROCESSING,
    UI_INJECTION,
    GLOSSARY_ACCESS,
    CHARACTER_DATABASE,
    AUDIO_PLAYBACK
}

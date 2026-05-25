package chimahon.novel.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class CatalogRemoteApiModel(
    @SerialName("name") val name: String,
    @SerialName("pkg") val pkg: String,
    @SerialName("version") val version: String,
    @SerialName("code") val code: Int,
    @SerialName("lang") val lang: String,
    @SerialName("apk") val apk: String,
    @SerialName("id") val id: Long,
    @SerialName("description") val description: String,
    @SerialName("nsfw") val nsfw: Boolean,
)

@Serializable
data class LNReaderPluginModel(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("version") val version: String,
    @SerialName("url") val url: String,
    @SerialName("description") val description: String? = null,
    @SerialName("lang") val lang: String? = null,
    @SerialName("iconUrl") val iconUrl: String? = null
)

data class CatalogRemote(
    val name: String,
    val description: String,
    val sourceId: Long,
    val pkgName: String,
    val versionName: String,
    val versionCode: Int,
    val lang: String,
    val pkgUrl: String,
    val iconUrl: String,
    val nsfw: Boolean,
    val jarUrl: String,
    val repositoryType: String
)

class CatalogGithubApi(
    private val client: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val DEFAULT_REPOS = listOf(
            "https://raw.githubusercontent.com/kazemcodes/lnreader-plugins-unminified/refs/heads/repo/plugins/plugins.min.json"
        )
    }

    suspend fun fetchCatalogs(repoUrls: List<String> = DEFAULT_REPOS): List<CatalogRemote> {
        val allCatalogs = mutableListOf<CatalogRemote>()

        for (repoUrl in repoUrls) {
            try {
                val catalogs = fetchFromRepository(repoUrl)
                allCatalogs.addAll(catalogs)
            } catch (e: Exception) {
                android.util.Log.e("CatalogGithubApi", "Failed to fetch from $repoUrl", e)
            }
        }

        return allCatalogs.distinctBy { it.pkgName }
    }

    private suspend fun fetchFromRepository(repoUrl: String): List<CatalogRemote> {
        val request = Request.Builder()
            .url(repoUrl)
            .header("User-Agent", "Chimahon/1.0")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()

        if (body.startsWith("404") || body.contains("Not Found") ||
            body.contains("<!DOCTYPE html>") || body.contains("<html")) {
            return emptyList()
        }

        val trimmedResponse = body.trim()
        if (!trimmedResponse.startsWith("[") && !trimmedResponse.startsWith("{")) {
            return emptyList()
        }

        return when {
            repoUrl.contains("lnreader-plugins") || repoUrl.contains("plugins.min.json") -> {
                parseLNReaderFormat(body, repoUrl)
            }
            else -> {
                parseIReaderFormat(body, repoUrl)
            }
        }
    }

    private fun parseIReaderFormat(response: String, repoUrl: String): List<CatalogRemote> {
        val catalogs = json.decodeFromString<List<CatalogRemoteApiModel>>(response)
        val baseUrl = repoUrl.substringBefore("index.min.json", "").takeIf { it.isNotBlank() }
            ?: "https://raw.githubusercontent.com/IReaderorg/IReader-extensions/repov2/"

        return catalogs.map { catalog ->
            val iconUrl = "${baseUrl}icon/${catalog.apk.replace(".apk", ".png")}"
            val appUrl = "${baseUrl}apk/${catalog.apk}"
            val jarUrl = "${baseUrl}jar/${catalog.apk.replace(".apk", ".jar")}"

            CatalogRemote(
                name = catalog.name,
                description = catalog.description,
                sourceId = catalog.id,
                pkgName = catalog.pkg,
                versionName = catalog.version,
                versionCode = catalog.code,
                lang = catalog.lang,
                pkgUrl = appUrl,
                iconUrl = iconUrl,
                nsfw = catalog.nsfw,
                jarUrl = jarUrl,
                repositoryType = "IREADER"
            )
        }
    }

    private fun parseLNReaderFormat(response: String, repoUrl: String): List<CatalogRemote> {
        val lnReaderCatalogs = json.decodeFromString<List<LNReaderPluginModel>>(response)

        return lnReaderCatalogs.map { plugin ->
            val baseHash = plugin.id.hashCode().toLong()
            val numericId = 1_000_000_000_000L + (if (baseHash < 0) -baseHash else baseHash)
            val languageCode = convertLanguageNameToCode(plugin.lang)
            val iconUrl = fixLNReaderIconUrl(plugin.iconUrl, plugin.id, languageCode, repoUrl)

            CatalogRemote(
                name = plugin.name,
                description = plugin.description ?: "LNReader Plugin",
                sourceId = numericId,
                pkgName = plugin.id,
                versionName = plugin.version,
                versionCode = plugin.version.replace(".", "").toIntOrNull() ?: 1,
                lang = languageCode,
                pkgUrl = plugin.url,
                iconUrl = iconUrl,
                nsfw = false,
                jarUrl = plugin.url,
                repositoryType = "LNREADER"
            )
        }
    }

    private fun convertLanguageNameToCode(languageName: String?): String {
        if (languageName.isNullOrBlank()) return "en"

        val normalized = languageName
            .trim()
            .replace("\u200E", "").replace("\u200F", "").replace("\u200B", "")
            .replace("\u200C", "").replace("\u200D", "").replace("\uFEFF", "")
            .trim()

        if (normalized.isBlank()) return "en"

        if (normalized.length == 2 && normalized.all { it.isLetter() || it.isDigit() }) {
            return normalized.lowercase()
        }

        val lowercased = normalized.lowercase()

        return when {
            normalized == "العربية" || normalized == "العربیة" || lowercased == "arabic" -> "ar"
            normalized == "中文" || lowercased == "chinese" -> "zh"
            lowercased == "english" -> "en"
            lowercased == "español" || lowercased == "spanish" -> "es"
            lowercased == "français" || lowercased == "french" -> "fr"
            lowercased == "bahasa indonesia" || lowercased == "indonesian" -> "id"
            normalized == "日本語" || lowercased == "japanese" -> "ja"
            normalized == "한국어" || lowercased == "korean" -> "ko"
            lowercased == "português" || lowercased == "portuguese" -> "pt"
            normalized == "русский" || lowercased == "russian" -> "ru"
            normalized == "ไทย" || lowercased == "thai" -> "th"
            lowercased == "türkçe" || lowercased == "turkish" -> "tr"
            lowercased == "tiếng việt" || lowercased == "vietnamese" -> "vi"
            lowercased == "deutsch" || lowercased == "german" -> "de"
            lowercased == "italiano" || lowercased == "italian" -> "it"
            lowercased == "polski" || lowercased == "polish" -> "pl"
            normalized == "українська" || lowercased == "ukrainian" -> "uk"
            lowercased == "filipino" || lowercased == "tagalog" -> "tl"
            lowercased == "magyar" || lowercased == "hungarian" -> "hu"
            lowercased == "čeština" || lowercased == "czech" -> "cs"
            lowercased == "română" || lowercased == "romanian" -> "ro"
            lowercased == "nederlands" || lowercased == "dutch" -> "nl"
            lowercased == "svenska" || lowercased == "swedish" -> "sv"
            lowercased == "norsk" || lowercased == "norwegian" -> "no"
            lowercased == "dansk" || lowercased == "danish" -> "da"
            lowercased == "suomi" || lowercased == "finnish" -> "fi"
            normalized == "ελληνικά" || lowercased == "greek" -> "el"
            normalized == "עברית" || lowercased == "hebrew" -> "he"
            normalized == "हिन्दी" || lowercased == "hindi" -> "hi"
            normalized == "বাংলা" || lowercased == "bengali" -> "bn"
            normalized == "فارسی" || lowercased == "persian" || lowercased == "farsi" -> "fa"
            normalized == "اردو" || lowercased == "urdu" -> "ur"
            lowercased == "multi" || lowercased == "multilingual" -> "multi"
            else -> {
                if (normalized.length <= 5 && normalized.all { it.isLetter() || it == '-' }) {
                    normalized.lowercase()
                } else {
                    "en"
                }
            }
        }
    }

    private fun fixLNReaderIconUrl(
        iconUrl: String?,
        pluginId: String,
        lang: String?,
        repoUrl: String
    ): String {
        if (!iconUrl.isNullOrBlank()) {
            if (iconUrl.contains("github.com") && iconUrl.contains("/tree/")) {
                return iconUrl
                    .replace("github.com", "raw.githubusercontent.com")
                    .replace("/tree/", "/")
            }
            if (iconUrl.startsWith("http://") || iconUrl.startsWith("https://")) {
                return iconUrl
            }
        }

        val repoBaseUrl = repoUrl.substringBefore("/plugins/")
        val language = lang ?: "en"
        return "$repoBaseUrl/raw/main/src/$language/$pluginId/icon.png"
    }
}

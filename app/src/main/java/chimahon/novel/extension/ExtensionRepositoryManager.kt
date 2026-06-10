package chimahon.novel.extension

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

@Serializable
data class InstalledExtension(
    val id: String,
    val name: String,
    val lang: String,
    val version: String,
    val filePath: String,
    val isEnabled: Boolean = true,
    val repositoryType: String = "LNREADER",
    val iconUrl: String = ""
)

data class ExtensionUpdate(
    val installed: InstalledExtension,
    val remote: CatalogRemote,
    val currentVersion: String,
    val newVersion: String
)

class ExtensionRepositoryManager(private val context: Context) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val catalogApi = CatalogGithubApi(client)

    private val extensionsDir: File by lazy {
        File(context.filesDir, "novel_extensions").apply { mkdirs() }
    }

    private val installedFile: File by lazy {
        File(extensionsDir, "installed.json")
    }

    suspend fun fetchAvailableExtensions(): List<CatalogRemote> {
        return catalogApi.fetchCatalogs()
    }

    suspend fun checkForUpdates(): List<ExtensionUpdate> {
        val installed = getInstalledExtensions()
        if (installed.isEmpty()) return emptyList()

        val remote = try { catalogApi.fetchCatalogs() } catch (e: Exception) { return emptyList() }
        val remoteMap = remote.associateBy { it.pkgName }

        return installed.mapNotNull { ext ->
            val remoteExt = remoteMap[ext.id] ?: return@mapNotNull null
            val currentVer = ext.version.replace(".", "").toIntOrNull() ?: 0
            val remoteVer = remoteExt.versionName.replace(".", "").toIntOrNull() ?: 0
            if (remoteVer > currentVer) {
                ExtensionUpdate(
                    installed = ext,
                    remote = remoteExt,
                    currentVersion = ext.version,
                    newVersion = remoteExt.versionName
                )
            } else null
        }
    }

    suspend fun downloadExtension(entry: CatalogRemote): Result<String> {
        return try {
            val downloadUrl = when (entry.repositoryType) {
                "IREADER" -> entry.pkgUrl.takeIf { it.isNotBlank() } ?: entry.jarUrl.takeIf { it.isNotBlank() }
                else -> entry.jarUrl.takeIf { it.isNotBlank() } ?: entry.pkgUrl.takeIf { it.isNotBlank() }
            } ?: return Result.failure(Exception("No download URL"))

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Chimahon/1.0")
                .build()

            val response = client.newCall(request).execute()
            val bytes = response.body?.bytes() ?: return Result.failure(Exception("Empty response"))

            val fileName = generateFileName(entry.pkgName, entry.repositoryType)
            val file = File(extensionsDir, fileName)
            file.writeBytes(bytes)

            val installed = getInstalledExtensions().toMutableList()
            installed.removeAll { it.id == entry.pkgName }
            installed.add(
                InstalledExtension(
                    id = entry.pkgName,
                    name = entry.name,
                    lang = entry.lang,
                    version = entry.versionName,
                    filePath = file.absolutePath,
                    repositoryType = entry.repositoryType,
                    iconUrl = entry.iconUrl
                )
            )
            saveInstalledExtensions(installed)

            Result.success(file.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getInstalledExtensions(): List<InstalledExtension> {
        if (!installedFile.exists()) return emptyList()

        return try {
            json.decodeFromString<List<InstalledExtension>>(installedFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun uninstallExtension(extensionId: String) {
        val installed = getInstalledExtensions().toMutableList()
        val extension = installed.find { it.id == extensionId }

        if (extension != null) {
            File(extension.filePath).delete()
            installed.removeAll { it.id == extensionId }
            saveInstalledExtensions(installed)
        }
    }

    fun toggleExtension(extensionId: String, enabled: Boolean) {
        val installed = getInstalledExtensions().toMutableList()
        val index = installed.indexOfFirst { it.id == extensionId }
        if (index != -1) {
            installed[index] = installed[index].copy(isEnabled = enabled)
            saveInstalledExtensions(installed)
        }
    }

    private fun saveInstalledExtensions(extensions: List<InstalledExtension>) {
        installedFile.writeText(json.encodeToString(extensions))
    }

    private fun generateFileName(pkgName: String, repoType: String): String {
        val hash = MessageDigest.getInstance("MD5")
            .digest(pkgName.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
        val ext = if (repoType == "LNREADER") "js" else "apk"
        return "ext_${hash}.$ext"
    }
}

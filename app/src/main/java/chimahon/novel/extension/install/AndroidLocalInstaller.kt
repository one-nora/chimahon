package chimahon.novel.extension.install

import android.app.Application
import android.util.Log
import chimahon.novel.extension.CatalogRemote
import chimahon.novel.extension.InstalledExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

class AndroidLocalInstaller(
    private val context: Application,
    private val httpClient: OkHttpClient,
    private val installationChanges: AndroidCatalogInstallationChanges,
) : CatalogInstaller {

    private val extensionsDir: File by lazy {
        File(context.filesDir, "novel_extensions").apply { mkdirs() }
    }
    private val iconsDir: File by lazy {
        File(context.filesDir, "novel_extension_icons").apply { mkdirs() }
    }

    override fun install(catalog: CatalogRemote): Flow<InstallStep> = flow {
        emit(InstallStep.Downloading)
        try {
            val downloadUrl = when (catalog.repositoryType) {
                "IREADER" -> catalog.pkgUrl.takeIf { it.isNotBlank() }
                    ?: catalog.jarUrl.takeIf { it.isNotBlank() }
                else -> catalog.jarUrl.takeIf { it.isNotBlank() }
                    ?: catalog.pkgUrl.takeIf { it.isNotBlank() }
            } ?: throw Exception("No download URL")

            val bytes = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "Chimahon/1.0")
                    .build()
                httpClient.newCall(request).execute().body?.bytes()
                    ?: throw Exception("Empty response")
            }

            val fileName = generateFileName(catalog.pkgName, catalog.repositoryType)
            val file = File(extensionsDir, fileName)
            file.writeBytes(bytes)

            cacheIcon(catalog)

            val installed = InstalledExtension(
                id = catalog.pkgName,
                name = catalog.name,
                lang = catalog.lang,
                version = catalog.versionName,
                filePath = file.absolutePath,
                repositoryType = catalog.repositoryType,
                iconUrl = catalog.iconUrl,
            )
            emit(InstallStep.Success)
            installationChanges.notifyAppInstall(catalog.pkgName)
        } catch (e: Throwable) {
            Log.e(TAG, "Error installing extension locally", e)
            emit(InstallStep.Error(e.message ?: "Local install failed"))
        }
    }

    override suspend fun uninstall(pkgName: String): InstallStep {
        return try {
            val installed = loadInstalledExtensions()
            val extension = installed.find { it.id == pkgName }
            if (extension != null) {
                File(extension.filePath).delete()
                deleteIcon(pkgName)
                saveInstalledExtensions(installed - extension)
            }
            installationChanges.notifyAppUninstall(pkgName)
            InstallStep.Success
        } catch (e: Throwable) {
            InstallStep.Error(e.message ?: "Local uninstall failed")
        }
    }

    private fun cacheIcon(catalog: CatalogRemote) {
        if (catalog.iconUrl.isBlank()) return
        try {
            val request = Request.Builder()
                .url(catalog.iconUrl)
                .header("User-Agent", "Chimahon/1.0")
                .build()
            val response = httpClient.newCall(request).execute()
            val bytes = response.body?.bytes() ?: return
            val iconFile = File(iconsDir, "${catalog.pkgName}.png")
            iconFile.writeBytes(bytes)
        } catch (_: Exception) {}
    }

    private fun deleteIcon(pkgName: String) {
        File(iconsDir, "$pkgName.png").delete()
    }

    private fun loadInstalledExtensions(): List<InstalledExtension> {
        val installedFile = File(extensionsDir, "installed.json")
        if (!installedFile.exists()) return emptyList()
        return try {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<List<InstalledExtension>>(installedFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveInstalledExtensions(extensions: List<InstalledExtension>) {
        val installedFile = File(extensionsDir, "installed.json")
        installedFile.writeText(
            kotlinx.serialization.json.Json { prettyPrint = true }
                .encodeToString(extensions),
        )
    }

    private fun generateFileName(pkgName: String, repoType: String): String {
        val hash = MessageDigest.getInstance("MD5")
            .digest(pkgName.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
        val ext = if (repoType == "LNREADER") "js" else "apk"
        return "ext_$hash.$ext"
    }

    companion object {
        private const val TAG = "AndroidLocalInstaller"
    }
}

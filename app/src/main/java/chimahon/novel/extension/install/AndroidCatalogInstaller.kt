package chimahon.novel.extension.install

import android.app.Application
import android.util.Log
import chimahon.novel.extension.CatalogRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class AndroidCatalogInstaller(
    private val context: Application,
    private val httpClient: OkHttpClient,
    private val installationChanges: AndroidCatalogInstallationChanges,
    private val packageInstaller: SystemPackageInstaller,
) : CatalogInstaller {

    override fun install(catalog: CatalogRemote): Flow<InstallStep> = flow {
        emit(InstallStep.Downloading)
        val secureCache = File(context.codeCacheDir, "secure_installations").apply { mkdirs() }
        val tmpApkFile = File(secureCache, "${catalog.pkgName}.apk")
        val tmpIconFile = File(secureCache, "${catalog.pkgName}.png")
        try {
            val apkBytes = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(catalog.pkgUrl)
                    .header("User-Agent", "Chimahon/1.0")
                    .build()
                httpClient.newCall(request).execute().body?.bytes()
                    ?: throw Exception("Empty APK response")
            }
            tmpApkFile.writeBytes(apkBytes)

            runCatching {
                val iconRequest = Request.Builder()
                    .url(catalog.iconUrl)
                    .header("User-Agent", "Chimahon/1.0")
                    .build()
                val iconBytes = httpClient.newCall(iconRequest).execute().body?.bytes()
                if (iconBytes != null) {
                    tmpIconFile.writeBytes(iconBytes)
                    val extDir = File(secureCache, catalog.pkgName).apply { mkdirs() }
                    val iconFile = File(extDir, tmpIconFile.name)
                    tmpIconFile.copyTo(iconFile, overwrite = true)
                }
            }

            val secureApkDir = File(context.codeCacheDir, "secure_installations")
            secureApkDir.mkdirs()
            val secureApkFile = File(secureApkDir, "${catalog.pkgName}.apk")
            if (secureApkFile.exists()) secureApkFile.delete()
            tmpApkFile.copyTo(secureApkFile, overwrite = true)
            secureApkFile.setReadable(true)
            secureApkFile.setWritable(true)

            emit(InstallStep.Idle)
            val result = packageInstaller.install(secureApkFile, catalog.pkgName)
            if (result is InstallStep.Success) {
                installationChanges.notifyAppInstall(catalog.pkgName)
            }
            emit(result)
        } catch (e: Throwable) {
            Log.e(TAG, "Error installing package", e)
            emit(InstallStep.Error(e.message ?: "Unknown error"))
        } finally {
            tmpApkFile.delete()
            tmpIconFile.delete()
        }
    }

    override suspend fun uninstall(pkgName: String): InstallStep {
        return try {
            val result = packageInstaller.uninstall(pkgName)
            if (result is InstallStep.Success) {
                installationChanges.notifyAppUninstall(pkgName)
            }
            result
        } catch (e: Throwable) {
            InstallStep.Error(e.message ?: "Uninstall failed")
        }
    }

    companion object {
        private const val TAG = "AndroidCatalogInstaller"
    }
}

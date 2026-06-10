package chimahon.novel.extension.ireader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import chimahon.novel.extension.NovelExtension
import chimahon.source.ireader.adapter.IReaderCompatSourceAdapter
import dalvik.system.DexClassLoader
import dalvik.system.InMemoryDexClassLoader
import dalvik.system.PathClassLoader
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.util.lang.Hash
import ireader.core.prefs.InMemoryPreferenceStore
import ireader.core.source.CatalogSource
import ireader.core.source.Dependencies
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

class IReaderApkLoader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val trustExtension: TrustExtension,
) {
    private val packageManager = context.packageManager
    private val secureExtensionsDir = File(context.codeCacheDir, "novel_ireader_exts").apply { mkdirs() }
    private val dexCacheDir = File(context.codeCacheDir, "novel_ireader_dex").apply { mkdirs() }

    fun loadInstalled(files: List<File>): List<LoadResult> {
        val privateResults = files.mapNotNull { file ->
            if (!file.isFile || file.extension != "apk") return@mapNotNull null
            val info = packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
                ?.takeIf(::isIReaderExtension)
                ?: return@mapNotNull null
            info.applicationInfo?.fixBasePaths(file.absolutePath)
            ExtensionInfo(info, file, isShared = false)
        }

        val systemResults = getSystemIReaderPackages()
        return (systemResults + privateResults)
            .distinctBy { it.packageInfo.packageName }
            .map { load(it) }
    }

    fun loadPackageFile(file: File): LoadResult {
        val info = packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            ?.takeIf(::isIReaderExtension)
            ?: return LoadResult.Error("Not an IReader extension APK")
        info.applicationInfo?.fixBasePaths(file.absolutePath)
        return load(ExtensionInfo(info, file, isShared = false))
    }

    private fun getSystemIReaderPackages(): List<ExtensionInfo> {
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(PACKAGE_FLAGS)
        }
        return packages
            .filter(::isIReaderExtension)
            .mapNotNull { info ->
                val sourceDir = info.applicationInfo?.sourceDir ?: return@mapNotNull null
                ExtensionInfo(info, File(sourceDir), isShared = true)
            }
    }

    private fun load(info: ExtensionInfo): LoadResult {
        val pkgInfo = info.packageInfo
        val appInfo = pkgInfo.applicationInfo ?: return LoadResult.Error("Missing application info")
        val pkgName = pkgInfo.packageName
        val versionName = pkgInfo.versionName.orEmpty()
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
        val sourceName = appInfo.metaData?.getString(METADATA_SOURCE_NAME)
            ?: packageManager.getApplicationLabel(appInfo).toString().substringAfter("IReader: ").substringBefore(" (")
        val lang = appInfo.metaData?.getString(METADATA_SOURCE_LANG).orEmpty()
        val nsfw = appInfo.metaData?.getInt(METADATA_SOURCE_NSFW, 0) == 1
        val sourceClass = appInfo.metaData?.getString(METADATA_SOURCE_CLASS)?.trim().orEmpty()
        if (sourceClass.isBlank()) return LoadResult.Error("Missing source.class")

        val signatures = getSignatures(pkgInfo).orEmpty()
        if (signatures.isEmpty()) return LoadResult.Error("Unsigned IReader extension")
        if (!runCatching { kotlinx.coroutines.runBlocking { trustExtension.isTrusted(pkgInfo, signatures) } }.getOrDefault(false)) {
            return LoadResult.Untrusted(
                NovelExtension.Untrusted(
                    name = sourceName,
                    pkgName = pkgName,
                    versionName = versionName,
                    versionCode = versionCode,
                    lang = lang,
                    isNsfw = nsfw,
                    signatureHash = signatures.last(),
                ),
            )
        }

        val loader = runCatching { createClassLoader(info) }.getOrElse { error ->
            logcat(LogPriority.ERROR, error) { "Failed to create IReader classloader for $pkgName" }
            return LoadResult.Error(error.message ?: "Classloader failed")
        }
        val source = runCatching {
            val className = if (sourceClass.startsWith(".")) pkgName + sourceClass else sourceClass
            val deps = Dependencies(
                httpClients = ChimahonIReaderHttpClients(okHttpClient),
                preferences = InMemoryPreferenceStore(),
            )
            Class.forName(className, false, loader)
                .getConstructor(Dependencies::class.java)
                .newInstance(deps) as CatalogSource
        }.getOrElse { error ->
            logcat(LogPriority.ERROR, error) { "Failed to instantiate IReader source $pkgName" }
            return LoadResult.Error(error.message ?: "Source failed")
        }

        val novelSources: List<NovelSource> = listOf(IReaderCompatSourceAdapter(source))
        return LoadResult.Success(
            NovelExtension.Installed(
                name = source.name.ifBlank { sourceName },
                pkgName = pkgName,
                versionName = versionName,
                versionCode = versionCode,
                lang = source.lang.ifBlank { lang },
                isNsfw = nsfw,
                repositoryType = "IREADER",
                sources = novelSources,
                icon = runCatching { appInfo.loadIcon(packageManager) }.getOrNull(),
                isShared = info.isShared,
                signatureHash = signatures.last(),
            ),
        )
    }

    private fun createClassLoader(info: ExtensionInfo): ClassLoader {
        if (info.isShared) {
            return PathClassLoader(info.file.absolutePath, null, context.classLoader)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                ZipFile(info.file).use { zip ->
                    val dexEntry = zip.getEntry("classes.dex") ?: error("No classes.dex in ${info.file.name}")
                    val buffer = ByteBuffer.wrap(zip.getInputStream(dexEntry).readBytes())
                    return InMemoryDexClassLoader(buffer, context.classLoader)
                }
            }
        }

        val secureApk = File(secureExtensionsDir, "${info.packageInfo.packageName}.apk")
        if (secureApk.exists()) secureApk.delete()
        info.file.copyTo(secureApk, overwrite = true)
        secureApk.setReadOnly()
        val outputDir = File(dexCacheDir, info.packageInfo.packageName).apply { mkdirs() }
        return DexClassLoader(secureApk.absolutePath, outputDir.absolutePath, null, context.classLoader)
    }

    private fun isIReaderExtension(pkgInfo: PackageInfo): Boolean {
        return pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE || it.name == EXTENSION_FEATURE_LEGACY }
    }

    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }?.map { Hash.sha256(it.toByteArray()) }
    }

    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) sourceDir = apkPath
        if (publicSourceDir == null) publicSourceDir = apkPath
    }

    sealed interface LoadResult {
        data class Success(val extension: NovelExtension.Installed) : LoadResult
        data class Untrusted(val extension: NovelExtension.Untrusted) : LoadResult
        data class Error(val reason: String) : LoadResult
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val file: File,
        val isShared: Boolean,
    )

    companion object {
        private const val EXTENSION_FEATURE = "ireader.extension"
        private const val EXTENSION_FEATURE_LEGACY = "ireader"
        private const val METADATA_SOURCE_CLASS = "source.class"
        private const val METADATA_SOURCE_NAME = "source.name"
        private const val METADATA_SOURCE_LANG = "source.lang"
        private const val METADATA_SOURCE_NSFW = "source.nsfw"

        @Suppress("DEPRECATION")
        private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
    }
}

package chimahon.novel.extension.install

import android.content.Context
import android.content.SharedPreferences
import chimahon.novel.extension.CatalogRemote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class InstallerMode(val key: String) {
    LOCAL("local"),
    ANDROID_PACKAGE_MANAGER("android_pm"),
    HYBRID("hybrid"),
}

class InstallCatalogImpl(
    private val context: Context,
    private val androidLocalInstaller: AndroidLocalInstaller,
    private val androidCatalogInstaller: AndroidCatalogInstaller,
) {
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("novel_extension_installer", Context.MODE_PRIVATE)

    fun setInstallerMode(mode: InstallerMode) {
        prefs.edit().putString(PREF_INSTALLER_MODE, mode.key).apply()
    }

    fun getInstallerMode(): InstallerMode {
        val key = prefs.getString(PREF_INSTALLER_MODE, InstallerMode.HYBRID.key) ?: InstallerMode.HYBRID.key
        return InstallerMode.entries.find { it.key == key } ?: InstallerMode.HYBRID
    }

    fun install(catalog: CatalogRemote): Flow<InstallStep> {
        return when {
            catalog.repositoryType.equals("LNREADER", ignoreCase = true) -> {
                androidLocalInstaller.install(catalog)
            }
            getInstallerMode() == InstallerMode.LOCAL -> {
                androidLocalInstaller.install(catalog)
            }
            getInstallerMode() == InstallerMode.ANDROID_PACKAGE_MANAGER -> {
                androidCatalogInstaller.install(catalog)
            }
            else -> {
                installWithFallback(catalog)
            }
        }
    }

    private fun installWithFallback(catalog: CatalogRemote): Flow<InstallStep> = flow {
        var capturedLocal: InstallStep? = null
        try {
            androidLocalInstaller.install(catalog).collect { step ->
                capturedLocal = step
            }
        } catch (_: Exception) {}

        val localOutcome = capturedLocal
        if (localOutcome is InstallStep.Success) {
            emit(localOutcome)
            return@flow
        }

        var systemOutcome: InstallStep? = null
        try {
            androidCatalogInstaller.install(catalog).collect { step ->
                systemOutcome = step
                emit(step)
            }
        } catch (_: Exception) {}

        if (systemOutcome == null) {
            emit(InstallStep.Error("Both local and system install failed"))
        }
    }

    companion object {
        private const val PREF_INSTALLER_MODE = "installer_mode"
    }
}

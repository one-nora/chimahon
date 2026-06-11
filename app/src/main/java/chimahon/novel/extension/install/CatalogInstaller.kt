package chimahon.novel.extension.install

import chimahon.novel.extension.CatalogRemote
import kotlinx.coroutines.flow.Flow

interface CatalogInstaller {
    fun install(catalog: CatalogRemote): Flow<InstallStep>
    suspend fun uninstall(pkgName: String): InstallStep
}

package chimahon.novel.extension.install

import chimahon.novel.extension.NovelExtension

class UninstallCatalogImpl(
    private val androidLocalInstaller: AndroidLocalInstaller,
    private val androidCatalogInstaller: AndroidCatalogInstaller,
) : UninstallCatalogs {

    override suspend fun uninstall(catalog: NovelExtension.Installed): InstallStep {
        return when (catalog) {
            is NovelExtension.Installed.SystemWide -> androidCatalogInstaller.uninstall(catalog.pkgName)
            is NovelExtension.Installed.Locally -> androidLocalInstaller.uninstall(catalog.pkgName)
        }
    }
}

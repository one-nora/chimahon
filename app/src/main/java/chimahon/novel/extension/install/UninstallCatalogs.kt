package chimahon.novel.extension.install

import chimahon.novel.extension.NovelExtension

interface UninstallCatalogs {
    suspend fun uninstall(catalog: NovelExtension.Installed): InstallStep
}

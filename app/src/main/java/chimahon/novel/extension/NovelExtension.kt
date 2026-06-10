package chimahon.novel.extension

import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.sourcenovel.NovelSource

sealed class NovelExtension {
    abstract val name: String
    abstract val pkgName: String
    abstract val versionName: String
    abstract val versionCode: Long
    abstract val lang: String
    abstract val isNsfw: Boolean
    abstract val repositoryType: String

    data class Installed(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val lang: String,
        override val isNsfw: Boolean,
        override val repositoryType: String,
        val sources: List<NovelSource>,
        val icon: Drawable? = null,
        val hasUpdate: Boolean = false,
        val isObsolete: Boolean = false,
        val isShared: Boolean = false,
        val repoName: String? = null,
        val repoUrl: String? = null,
        val signatureHash: String = "",
    ) : NovelExtension()

    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val lang: String,
        override val isNsfw: Boolean,
        override val repositoryType: String,
        val sourceId: Long,
        val description: String,
        val pkgUrl: String,
        val iconUrl: String,
        val jarUrl: String,
        val repoName: String = "",
        val repoUrl: String = "",
    ) : NovelExtension()

    data class Untrusted(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val lang: String = "",
        override val isNsfw: Boolean = false,
        override val repositoryType: String = "IREADER",
        val signatureHash: String,
        val repoName: String? = null,
    ) : NovelExtension()
}

data class NovelExtensionInstallState(
    val pkgName: String,
    val step: InstallStep,
)

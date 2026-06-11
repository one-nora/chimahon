package chimahon.novel.extension

import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.sourcenovel.NovelSource

sealed class NovelExtension {
    abstract val name: String
    abstract val pkgName: String
    abstract val versionName: String
    abstract val versionCode: Long
    abstract val lang: String
    abstract val isNsfw: Boolean
    abstract val repositoryType: String
    abstract val signatureHash: String

    val compositeKey: String get() = "$pkgName:$signatureHash"

    sealed class Installed : NovelExtension() {
        abstract val sources: List<NovelSource>
        abstract val icon: Drawable?
        abstract val hasUpdate: Boolean
        abstract val isObsolete: Boolean
        abstract val repoName: String?
        abstract val repoUrl: String?
        abstract val installDir: String?

        data class SystemWide(
            override val name: String,
            override val pkgName: String,
            override val versionName: String,
            override val versionCode: Long,
            override val lang: String,
            override val isNsfw: Boolean,
            override val repositoryType: String,
            override val sources: List<NovelSource>,
            override val icon: Drawable? = null,
            override val hasUpdate: Boolean = false,
            override val isObsolete: Boolean = false,
            override val repoName: String? = null,
            override val repoUrl: String? = null,
            override val signatureHash: String = "",
            override val installDir: String? = null,
        ) : Installed()

        data class Locally(
            override val name: String,
            override val pkgName: String,
            override val versionName: String,
            override val versionCode: Long,
            override val lang: String,
            override val isNsfw: Boolean,
            override val repositoryType: String,
            override val sources: List<NovelSource>,
            override val icon: Drawable? = null,
            override val hasUpdate: Boolean = false,
            override val isObsolete: Boolean = false,
            override val repoName: String? = null,
            override val repoUrl: String? = null,
            override val signatureHash: String = "",
            override val installDir: String,
            val isShared: Boolean = false,
        ) : Installed()
    }

    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val lang: String,
        override val isNsfw: Boolean,
        override val repositoryType: String,
        override val signatureHash: String = "",
        val sourceId: Long,
        val description: String,
        val pkgUrl: String,
        val iconUrl: String,
        val jarUrl: String,
        val repoName: String = "",
        val repoUrl: String = "",
        val sources: List<SourceMeta> = emptyList(),
    ) : NovelExtension()

    data class SourceMeta(
        val name: String,
        val id: Long,
        val lang: String = "all",
    )

    data class Untrusted(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val lang: String = "",
        override val isNsfw: Boolean = false,
        override val repositoryType: String = "IREADER",
        override val signatureHash: String,
        val repoName: String? = null,
    ) : NovelExtension()
}

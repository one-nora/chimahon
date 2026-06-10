package chimahon.novel.manager

import chimahon.novel.extension.NovelExtensionManager
import chimahon.novel.model.NovelServer
import chimahon.novel.model.NovelServerStorage
import chimahon.novel.model.NovelServerType
import chimahon.novel.source.opds.OpdsSource
import chimahon.source.kavita.KavitaNovelSource
import chimahon.source.komga.KomgaNovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class NovelSourceWithServer(
    val server: NovelServer,
    val source: NovelSource,
)

class NovelSourceManager(
    private val serverStorage: NovelServerStorage,
    private val extensionManager: NovelExtensionManager
) {
    private val sourceEntries = mutableMapOf<Long, NovelSourceWithServer>()
    private val extensionSourceEntries = mutableMapOf<Long, NovelSource>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        rebuildSources(emptyList())
        scope.launch {
            extensionManager.loadedNovelSources.collect { extensionSources ->
                rebuildExtensionSources(extensionSources)
            }
        }
    }

    fun rebuildSources(servers: List<NovelServer>) {
        sourceEntries.clear()
        for (server in servers.filter { it.enabled }) {
            val source = createSource(server) ?: continue
            sourceEntries[source.id] = NovelSourceWithServer(server, source)
        }
    }

    fun rebuildExtensionSources(extensionSources: List<NovelSource>) {
        extensionSourceEntries.clear()
        for (source in extensionSources) {
            extensionSourceEntries[source.id] = source
        }
    }

    fun getNovelSource(sourceId: Long): NovelSource? {
        return sourceEntries[sourceId]?.source ?: extensionSourceEntries[sourceId]
    }

    fun getSourceEntry(sourceId: Long): NovelSourceWithServer? = sourceEntries[sourceId]

    fun getCatalogueSources(): List<NovelsPageSource> {
        val builtIn = sourceEntries.values.map { it.source }.filterIsInstance<NovelsPageSource>()
        val extension = extensionSourceEntries.values.filterIsInstance<NovelsPageSource>()
        return builtIn + extension
    }

    fun getAllEntries(): List<NovelSourceWithServer> = sourceEntries.values.toList()

    fun getExtensionSources(): List<NovelSource> = extensionSourceEntries.values.toList()

    fun getEntriesFlow(): Flow<List<NovelSourceWithServer>> {
        return serverStorage.getAllServers().map { servers ->
            rebuildSources(servers)
            getAllEntries()
        }
    }

    private fun createSource(server: NovelServer): NovelSource? {
        if (!server.enabled) return null
        return when (server.type) {
            NovelServerType.OPDS -> OpdsSource(server)
            NovelServerType.KOMGA -> KomgaNovelSource(server)
            NovelServerType.KAVITA -> KavitaNovelSource(server)
        }
    }
}

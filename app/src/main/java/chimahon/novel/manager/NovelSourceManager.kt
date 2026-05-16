package chimahon.novel.manager

import chimahon.novel.model.NovelServer
import chimahon.novel.model.NovelServerStorage
import chimahon.novel.model.NovelServerType
import chimahon.source.kavita.KavitaSource
import chimahon.source.komga.KomgaSource
import chimahon.novel.source.opds.OpdsSource
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NovelSourceManager(
    private val serverStorage: NovelServerStorage,
) {
    private val sources = mutableMapOf<Long, NovelSource>()

    init {
        rebuildSources(emptyList())
    }

    fun rebuildSources(servers: List<NovelServer>) {
        sources.clear()
        for (server in servers.filter { it.enabled }) {
            val source = createSource(server)
            sources[source.id] = source
        }
    }

    fun getNovelSource(sourceId: Long): NovelSource? = sources[sourceId]

    fun getCatalogueSources(): List<NovelsPageSource> = sources.values.filterIsInstance<NovelsPageSource>()

    fun getAllSources(): List<NovelSource> = sources.values.toList()

    fun getSourcesFlow(): Flow<List<NovelSource>> {
        return serverStorage.getAllServers().map { servers ->
            rebuildSources(servers)
            getAllSources()
        }
    }

    private fun createSource(server: NovelServer): NovelSource {
        return when (server.type) {
            NovelServerType.OPDS -> OpdsSource(server)
            NovelServerType.KOMGA -> KomgaSource(server)
            NovelServerType.KAVITA -> KavitaSource(server)
        }
    }
}

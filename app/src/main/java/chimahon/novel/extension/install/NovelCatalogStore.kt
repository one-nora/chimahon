package chimahon.novel.extension.install

import chimahon.novel.extension.NovelExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private sealed class CatalogUpdate {
    data class ReplaceAll(val catalogs: List<NovelExtension.Installed>) : CatalogUpdate()
}

class NovelCatalogStore(
    private val installationChanges: AndroidCatalogInstallationChanges,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _catalogs = MutableStateFlow<List<NovelExtension.Installed>>(emptyList())
    private val updates = Channel<CatalogUpdate>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val catalogs: StateFlow<List<NovelExtension.Installed>> = _catalogs.asStateFlow()

    init {
        scope.launch { collectInstallationChanges() }
        scope.launch { processBatchUpdates() }
    }

    fun setCatalogs(catalogs: List<NovelExtension.Installed>) {
        updates.trySend(CatalogUpdate.ReplaceAll(catalogs))
    }

    private suspend fun collectInstallationChanges() {
        installationChanges.flow.collect { change ->
            when (change) {
                is CatalogInstallationChange.SystemInstall,
                is CatalogInstallationChange.SystemUninstall,
                is CatalogInstallationChange.LocalInstall,
                is CatalogInstallationChange.LocalUninstall,
                -> { /* Store lets manager handle full reloads via setCatalogs */ }
            }
        }
    }

    private suspend fun processBatchUpdates() {
        updates.consumeAsFlow()
            .debounce(100)
            .collect { update ->
                when (update) {
                    is CatalogUpdate.ReplaceAll -> _catalogs.value = update.catalogs
                }
            }
    }
}

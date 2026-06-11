package chimahon.novel.extension.install

import chimahon.novel.extension.CatalogRemote
import chimahon.novel.extension.NovelExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExtensionState(
    val installSteps: Map<String, InstallStep> = emptyMap(),
    val isLoading: Boolean = false,
)

sealed class ExtensionCommand {
    data class Install(val catalog: CatalogRemote) : ExtensionCommand()
    data class Uninstall(val catalog: NovelExtension.Installed) : ExtensionCommand()
    data class Update(val catalog: NovelExtension.Installed, val remote: CatalogRemote) : ExtensionCommand()
    data class Cancel(val pkgName: String) : ExtensionCommand()
    object ClearError : ExtensionCommand()
}

sealed class ExtensionEvent {
    data class InstallCompleted(val pkgName: String, val repositoryType: String = "") : ExtensionEvent()
    data class UninstallCompleted(val pkgName: String) : ExtensionEvent()
    data class InstallFailed(val pkgName: String, val reason: String) : ExtensionEvent()
    data class UninstallFailed(val pkgName: String, val reason: String) : ExtensionEvent()
}

class ExtensionController(
    private val installCatalog: InstallCatalogImpl,
    private val uninstallCatalog: UninstallCatalogs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ExtensionState())
    private val _events = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 8)
    private val installerJobs = mutableMapOf<String, Job>()

    val state: StateFlow<ExtensionState> = _state.asStateFlow()
    val events: SharedFlow<ExtensionEvent> = _events.asSharedFlow()

    fun dispatch(command: ExtensionCommand) {
        scope.launch {
            mutex.withLock {
                try {
                    when (command) {
                        is ExtensionCommand.Install -> processInstall(command.catalog)
                        is ExtensionCommand.Uninstall -> processUninstall(command.catalog)
                        is ExtensionCommand.Update -> processUpdate(command.catalog, command.remote)
                        is ExtensionCommand.Cancel -> processCancel(command.pkgName)
                        is ExtensionCommand.ClearError -> _state.value = _state.value.copy(isLoading = false)
                    }
                } catch (e: Exception) {
                    _events.tryEmit(ExtensionEvent.InstallFailed("", e.message ?: "Unknown error"))
                }
            }
        }
    }

    private suspend fun processInstall(catalog: CatalogRemote) {
        val pkgName = catalog.pkgName
        val job = scope.launch {
            _state.value = _state.value.copy(
                installSteps = _state.value.installSteps + (pkgName to InstallStep.Downloading),
                isLoading = true,
            )
            installCatalog.install(catalog).collect { step ->
                _state.value = _state.value.copy(
                    installSteps = _state.value.installSteps + (pkgName to step),
                )
            }
            val finalStep = _state.value.installSteps[pkgName]
            when (finalStep) {
                is InstallStep.Success -> {
                    _events.tryEmit(ExtensionEvent.InstallCompleted(pkgName, catalog.repositoryType))
                    _state.value = _state.value.copy(
                        installSteps = _state.value.installSteps - pkgName,
                        isLoading = false,
                    )
                }
                is InstallStep.Error -> {
                    _events.tryEmit(ExtensionEvent.InstallFailed(pkgName, finalStep.error))
                    _state.value = _state.value.copy(isLoading = false)
                }
                else -> {
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }
        installerJobs[pkgName] = job
        job.join()
        installerJobs.remove(pkgName)
    }

    private suspend fun processUninstall(catalog: NovelExtension.Installed) {
        _state.value = _state.value.copy(isLoading = true)
        val step = uninstallCatalog.uninstall(catalog)
        _state.value = _state.value.copy(
            installSteps = _state.value.installSteps - catalog.pkgName,
            isLoading = false,
        )
        if (step is InstallStep.Success) {
            _events.tryEmit(ExtensionEvent.UninstallCompleted(catalog.pkgName))
        } else {
            _events.tryEmit(
                ExtensionEvent.UninstallFailed(
                    catalog.pkgName,
                    (step as? InstallStep.Error)?.error ?: "Unknown",
                ),
            )
        }
    }

    private suspend fun processUpdate(installed: NovelExtension.Installed, remote: CatalogRemote) {
        processInstall(remote)
    }

    private fun processCancel(pkgName: String) {
        installerJobs[pkgName]?.cancel()
        installerJobs.remove(pkgName)
        _state.value = _state.value.copy(
            installSteps = _state.value.installSteps - pkgName,
            isLoading = false,
        )
    }

    fun getInstallStep(pkgName: String): InstallStep? {
        return _state.value.installSteps[pkgName]
    }
}

package chimahon.plugin.api

interface PluginRegistry {
    fun getInstalledPlugins(): List<PluginInfo>
    fun getPluginsByType(type: PluginType): List<PluginInfo>
    fun getPlugin(pluginId: String): PluginInfo?
    fun getEnabledPlugins(): List<PluginInfo>
    fun isInstalled(pluginId: String): Boolean
    fun isEnabled(pluginId: String): Boolean
    suspend fun enablePlugin(pluginId: String): PluginOperationResult
    suspend fun disablePlugin(pluginId: String): PluginOperationResult
    suspend fun installPlugin(pluginFile: ByteArray): PluginOperationResult
    suspend fun uninstallPlugin(pluginId: String): PluginOperationResult
    suspend fun checkForUpdates(): List<PluginUpdateInfo>
    fun <T : Plugin> getPluginInstance(pluginId: String): T?
    fun addListener(listener: PluginRegistryListener)
    fun removeListener(listener: PluginRegistryListener)
}

data class PluginInfo(
    val manifest: PluginManifest,
    val state: PluginState,
    val installedAt: Long,
    val updatedAt: Long,
    val filePath: String,
    val sizeBytes: Long,
    val updateAvailable: Boolean = false,
    val latestVersion: String? = null
)

enum class PluginState {
    ENABLED,
    DISABLED,
    INSTALLING,
    UPDATING,
    ERROR,
    DEPENDENCIES_NOT_MET
}

data class PluginUpdateInfo(
    val pluginId: String,
    val currentVersion: String,
    val latestVersion: String,
    val changelog: String? = null,
    val downloadUrl: String,
    val sizeBytes: Long
)

sealed class PluginOperationResult {
    data class Success(val message: String? = null) : PluginOperationResult()
    data class Error(val error: PluginOperationError) : PluginOperationResult()
    fun isSuccess(): Boolean = this is Success
}

sealed class PluginOperationError {
    data class PluginNotFound(val pluginId: String) : PluginOperationError()
    data class AlreadyInstalled(val pluginId: String) : PluginOperationError()
    data class IncompatibleVersion(val required: String, val actual: String) : PluginOperationError()
    data class InstallationFailed(val reason: String) : PluginOperationError()
    data class Unknown(val reason: String) : PluginOperationError()
}

interface PluginRegistryListener {
    fun onPluginInstalled(pluginInfo: PluginInfo)
    fun onPluginUninstalled(pluginId: String)
    fun onPluginEnabled(pluginId: String)
    fun onPluginDisabled(pluginId: String)
    fun onPluginUpdated(pluginInfo: PluginInfo)
    fun onPluginError(pluginId: String, error: PluginOperationError)
}

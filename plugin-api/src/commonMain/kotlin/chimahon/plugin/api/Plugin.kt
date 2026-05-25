package chimahon.plugin.api

interface Plugin {
    val manifest: PluginManifest
    fun initialize(context: PluginContext)
    fun cleanup()
}

interface PluginContext {
    val pluginId: String
    val permissions: List<PluginPermission>
    fun getDataDir(): String
    fun getCacheDir(): String
    fun hasPermission(permission: PluginPermission): Boolean
    fun log(level: LogLevel, message: String, throwable: Throwable? = null)
    fun getAppVersion(): AppVersionInfo
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class AppVersionInfo(
    val versionName: String,
    val versionCode: Int,
    val buildType: String
)

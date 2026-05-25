package chimahon.novel.extension

import android.content.Context
import chimahon.novel.extension.js.JsNovelSource
import chimahon.novel.extension.js.JsSourceEngine
import chimahon.novel.extension.js.JsSourceMetadata
import chimahon.source.ireader.source.CatalogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class NovelExtensionManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repoManager = ExtensionRepositoryManager(context)
    private var jsEngine: JsSourceEngine? = null
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _loadedSources = MutableStateFlow<List<CatalogSource>>(emptyList())
    val loadedSources: StateFlow<List<CatalogSource>> = _loadedSources.asStateFlow()

    private val _availableExtensions = MutableStateFlow<List<CatalogRemote>>(emptyList())
    val availableExtensions: StateFlow<List<CatalogRemote>> = _availableExtensions.asStateFlow()

    private val _installedExtensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installedExtensions: StateFlow<List<InstalledExtension>> = _installedExtensions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val loadedSourceMap = mutableMapOf<String, JsNovelSource>()

    private val extensionsDir: File by lazy {
        File(context.filesDir, "novel_extensions").apply { mkdirs() }
    }

    fun initialize() {
        scope.launch {
            _isLoading.value = true
            try {
                // Phase 1: Load stubs from cached metadata (instant, no JS engine)
                val stubs = loadStubSources()
                _loadedSources.value = stubs

                // Phase 2: Initialize JS engine on main thread
                val engine = JsSourceEngine(context)
                val ready = withContext(Dispatchers.Main) {
                    engine.initialize()
                }

                if (ready) {
                    jsEngine = engine
                    // Phase 3: Load full sources in parallel (replaces stubs)
                    val fullSources = loadFullSourcesParallel(engine)
                    if (fullSources.isNotEmpty()) {
                        _loadedSources.value = fullSources
                    }
                }

                refreshAvailableExtensions()
            } catch (e: Exception) {
                android.util.Log.e("ExtensionManager", "Failed to initialize", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Phase 1: Load stub sources from cached metadata files.
     * These show immediately in the UI without needing the JS engine.
     */
    private fun loadStubSources(): List<CatalogSource> {
        val installed = repoManager.getInstalledExtensions()
        _installedExtensions.value = installed

        return installed.filter { it.isEnabled && it.repositoryType != "IREADER" }.mapNotNull { ext ->
            val file = File(ext.filePath)
            if (!file.exists()) return@mapNotNull null

            val metadata = loadCachedMetadata(file) ?: extractAndCacheMetadata(file) ?: return@mapNotNull null

            StubNovelSource(metadata)
        }
    }

    /**
     * Phase 3: Load full sources in parallel with controlled concurrency.
     */
    private suspend fun loadFullSourcesParallel(engine: JsSourceEngine): List<CatalogSource> {
        val installed = _installedExtensions.value.filter { it.isEnabled && it.repositoryType != "IREADER" }
        val semaphore = Semaphore(4) // Max 4 concurrent JS loads
        val sources = mutableListOf<CatalogSource>()

        val jobs = installed.map { ext ->
            scope.async {
                semaphore.withPermit {
                    loadJsSource(ext, engine)
                }
            }
        }

        for (job in jobs) {
            val source = job.await()
            if (source != null) {
                sources.add(source)
            }
        }

        return sources
    }

    /**
     * Load cached metadata from .meta.json sidecar file.
     */
    private fun loadCachedMetadata(jsFile: File): JsSourceMetadata? {
        val metaFile = File(jsFile.parent, "${jsFile.nameWithoutExtension}.meta.json")
        if (!metaFile.exists()) return null

        return try {
            json.decodeFromString<JsSourceMetadata>(metaFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract metadata from JS code via regex and cache to .meta.json.
     */
    private fun extractAndCacheMetadata(jsFile: File): JsSourceMetadata? {
        return try {
            val jsCode = jsFile.readText()
            val metadata = JsSourceEngine.extractMetadataFromCode(jsCode, jsFile.nameWithoutExtension)

            if (metadata != null) {
                val metaFile = File(jsFile.parent, "${jsFile.nameWithoutExtension}.meta.json")
                metaFile.writeText(json.encodeToString(JsSourceMetadata.serializer(), metadata))
            }

            metadata
        } catch (e: Exception) {
            null
        }
    }

    suspend fun refreshAvailableExtensions() {
        try {
            val extensions = repoManager.fetchAvailableExtensions()
            _availableExtensions.value = extensions
        } catch (e: Exception) {
            android.util.Log.e("ExtensionManager", "Failed to refresh extensions", e)
        }
    }

    fun loadInstalledExtensions() {
        scope.launch {
            val installed = repoManager.getInstalledExtensions()
            _installedExtensions.value = installed

            val engine = jsEngine
            if (engine != null) {
                val sources = loadFullSourcesParallel(engine)
                _loadedSources.value = sources
            } else {
                val stubs = loadStubSources()
                _loadedSources.value = stubs
            }
        }
    }

    private suspend fun loadJsSource(extension: InstalledExtension, engine: JsSourceEngine): JsNovelSource? {
        val file = File(extension.filePath)
        if (!file.exists()) return null

        val script = file.readText()
        val metadata = JsSourceMetadata(
            id = extension.id,
            name = extension.name,
            version = extension.version,
            lang = extension.lang
        )

        val source = JsNovelSource(engine, metadata, script)
        val success = source.initialize()

        return if (success) {
            loadedSourceMap[extension.id] = source
            source
        } else {
            null
        }
    }

    fun installExtension(entry: CatalogRemote) {
        scope.launch {
            _isLoading.value = true
            try {
                val result = repoManager.downloadExtension(entry)
                result.onSuccess { filePath ->
                    // Extract and cache metadata immediately
                    val file = File(filePath)
                    extractAndCacheMetadata(file)
                    loadInstalledExtensions()
                }
                result.onFailure { error ->
                    android.util.Log.e("ExtensionManager", "Failed to install ${entry.name}", error)
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun uninstallExtension(extensionId: String) {
        scope.launch {
            loadedSourceMap.remove(extensionId)
            repoManager.uninstallExtension(extensionId)
            loadInstalledExtensions()
        }
    }

    fun toggleExtension(extensionId: String, enabled: Boolean) {
        scope.launch {
            repoManager.toggleExtension(extensionId, enabled)
            loadInstalledExtensions()
        }
    }

    fun getSource(sourceId: Long): CatalogSource? {
        return _loadedSources.value.find { it.id == sourceId }
    }

    fun getCatalogueSources(): List<CatalogSource> {
        return _loadedSources.value
    }

    fun destroy() {
        jsEngine?.destroy()
        jsEngine = null
    }
}

/**
 * Stub source that shows in UI immediately while JS engine loads.
 * Displays metadata from cached .meta.json but cannot browse/search yet.
 */
class StubNovelSource(
    private val metadata: JsSourceMetadata
) : CatalogSource {
    override val id: Long by lazy {
        chimahon.source.ireader.source.HttpSource.generateSourceId("${metadata.name.lowercase()}/${metadata.lang}/1")
    }
    override val name: String get() = metadata.name
    override val lang: String get() = metadata.lang

    override suspend fun getMangaList(sort: chimahon.source.ireader.model.Listing?, page: Int) =
        chimahon.source.ireader.model.MangasPageInfo.empty()

    override suspend fun getMangaList(filters: List<chimahon.source.ireader.model.Filter<*>>, page: Int) =
        chimahon.source.ireader.model.MangasPageInfo.empty()

    override suspend fun getMangaDetails(manga: chimahon.source.ireader.model.MangaInfo, commands: List<chimahon.source.ireader.model.Command<*>>) = manga

    override suspend fun getChapterList(manga: chimahon.source.ireader.model.MangaInfo, commands: List<chimahon.source.ireader.model.Command<*>>) =
        emptyList<chimahon.source.ireader.model.ChapterInfo>()

    override suspend fun getPageList(chapter: chimahon.source.ireader.model.ChapterInfo, commands: List<chimahon.source.ireader.model.Command<*>>) =
        emptyList<chimahon.source.ireader.model.Page>()

    override fun getListings(): List<chimahon.source.ireader.model.Listing> = emptyList()
    override fun getFilters(): List<chimahon.source.ireader.model.Filter<*>> = emptyList()
    override fun getCommands(): List<chimahon.source.ireader.model.Command<*>> = emptyList()

    fun isStub() = true
}

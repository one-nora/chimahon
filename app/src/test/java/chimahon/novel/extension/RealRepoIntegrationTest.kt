package chimahon.novel.extension

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json

class RealRepoIntegrationTest {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `fetch real IReader extension index and parse sources`() = runBlocking {
        val url = "https://raw.githubusercontent.com/IReaderorg/IReader-extensions/repov2/index.min.json"
        val request = Request.Builder().url(url).header("User-Agent", "Chimahon/1.0").build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        assertTrue(body.isNotBlank(), "Response body should not be blank")
        assertTrue(body.startsWith("["), "Response should be a JSON array")

        val catalogs = json.decodeFromString<List<CatalogRemoteApiModel>>(body)

        assertTrue(catalogs.isNotEmpty(), "Should have at least one source")

        val first = catalogs.first()
        assertTrue(first.name.isNotBlank(), "Source name should not be blank")
        assertTrue(first.pkg.isNotBlank(), "Source package should not be blank")
        assertTrue(first.apk.isNotBlank(), "Source APK should not be blank")
        assertTrue(first.id > 0, "Source ID should be positive")

        println("IReader repo: ${catalogs.size} sources found")
        println("First source: ${first.name} (${first.pkg}) lang=${first.lang}")
    }

    @Test
    fun `fetch real LNReader plugin index and parse sources`() = runBlocking {
        val url = "https://raw.githubusercontent.com/kazemcodes/lnreader-plugins-unminified/refs/heads/repo/plugins/plugins.min.json"
        val request = Request.Builder().url(url).header("User-Agent", "Chimahon/1.0").build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        assertTrue(body.isNotBlank(), "Response body should not be blank")
        assertTrue(body.startsWith("["), "Response should be a JSON array")

        val plugins = json.decodeFromString<List<LNReaderPluginModel>>(body)

        assertTrue(plugins.isNotEmpty(), "Should have at least one plugin")

        val first = plugins.first()
        assertTrue(first.name.isNotBlank(), "Plugin name should not be blank")
        assertTrue(first.id.isNotBlank(), "Plugin ID should not be blank")
        assertTrue(first.url.isNotBlank(), "Plugin URL should not be blank")
        assertTrue(first.url.endsWith(".js"), "Plugin URL should point to a .js file")

        println("LNReader repo: ${plugins.size} plugins found")
        println("First plugin: ${first.name} (${first.id}) lang=${first.lang}")
    }

    @Test
    fun `full CatalogGithubApi fetch returns LNReader results`() = runBlocking {
        val api = CatalogGithubApi(client)
        val catalogs = api.fetchCatalogs()

        assertTrue(catalogs.isNotEmpty(), "Should fetch at least one source from repos")

        val lnreaderSources = catalogs.filter { it.repositoryType == "LNREADER" }

        assertTrue(lnreaderSources.isNotEmpty(), "Should have LNReader sources")

        println("Total: ${catalogs.size} sources (${lnreaderSources.size} LNReader)")

        lnreaderSources.take(5).forEach { src ->
            println("  LNReader: ${src.name} [${src.lang}] url=${src.pkgUrl}")
        }
    }

    @Test
    fun `download a real LNReader JS source file`() = runBlocking {
        val url = "https://raw.githubusercontent.com/kazemcodes/lnreader-plugins-unminified/refs/heads/repo/plugins/plugins.min.json"
        val request = Request.Builder().url(url).header("User-Agent", "Chimahon/1.0").build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        val plugins = json.decodeFromString<List<LNReaderPluginModel>>(body)
        val firstPlugin = plugins.first()

        println("Downloading JS source: ${firstPlugin.name} from ${firstPlugin.url}")

        val jsRequest = Request.Builder().url(firstPlugin.url).header("User-Agent", "Chimahon/1.0").build()
        val jsResponse = client.newCall(jsRequest).execute()
        val jsBody = jsResponse.body?.string() ?: ""

        assertTrue(jsBody.isNotBlank(), "JS source file should not be blank")
        assertTrue(jsBody.length > 100, "JS source file should have substantial content")

        val hasId = jsBody.contains("id") || jsBody.contains("sourceId")
        val hasName = jsBody.contains("name") || jsBody.contains("sourceName")
        assertTrue(hasId || hasName, "JS source should contain id or name")

        println("Downloaded ${jsBody.length} bytes from ${firstPlugin.name}")
        println("First 200 chars: ${jsBody.take(200)}")
    }
}

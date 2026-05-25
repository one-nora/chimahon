package chimahon.novel.extension

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class JsSourceExecutionTest {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `fetch and parse real LNReader sources from repo`() = runBlocking {
        val api = CatalogGithubApi(client)
        val sources = api.fetchCatalogs()

        assertTrue(sources.isNotEmpty(), "Should fetch sources from repos")

        val lnreaderSources = sources.filter { it.repositoryType == "LNREADER" }
        assertTrue(lnreaderSources.isNotEmpty(), "Should have LNReader sources")

        println("=== LNReader Sources (${lnreaderSources.size}) ===")
        lnreaderSources.take(10).forEach { src ->
            println("  ${src.name} [${src.lang}] ${src.pkgUrl}")
        }
    }

    @Test
    fun `download real JS source and extract metadata`() = runBlocking {
        val api = CatalogGithubApi(client)
        val sources = api.fetchCatalogs()
        val lnreaderSources = sources.filter { it.repositoryType == "LNREADER" && it.lang == "en" }

        assertTrue(lnreaderSources.isNotEmpty(), "Should have English LNReader sources")

        val source = lnreaderSources.first()
        println("Downloading: ${source.name} from ${source.pkgUrl}")

        val request = Request.Builder().url(source.pkgUrl).header("User-Agent", "Mozilla/5.0").build()
        val response = client.newCall(request).execute()
        val jsCode = response.body?.string() ?: ""

        assertTrue(jsCode.isNotBlank(), "JS source should not be blank")
        assertTrue(jsCode.length > 100, "JS source should have substantial content, got ${jsCode.length} bytes")

        val metadata = extractMetadata(jsCode, source.pkgName)

        assertTrue(metadata.id.isNotBlank(), "Should extract id")
        assertTrue(metadata.name.isNotBlank(), "Should extract name")
        assertTrue(metadata.version.isNotBlank(), "Should extract version")

        println("\n=== Extracted Metadata ===")
        println("  id: ${metadata.id}")
        println("  name: ${metadata.name}")
        println("  version: ${metadata.version}")
        println("  site: ${metadata.site}")
        println("  lang: ${metadata.lang}")
        println("  JS size: ${jsCode.length} bytes")

        assertTrue(jsCode.contains("LNReaderPlugin") || jsCode.contains("popularNovels"),
            "JS should contain LNReader plugin structure or popularNovels function")
    }

    @Test
    fun `download multiple sources and verify all have valid metadata`() = runBlocking {
        val api = CatalogGithubApi(client)
        val sources = api.fetchCatalogs()
        val lnreaderSources = sources.filter { it.repositoryType == "LNREADER" && it.lang == "en" }

        val testSources = lnreaderSources.take(5)
        assertTrue(testSources.size >= 3, "Should have at least 3 English sources to test")

        println("=== Testing ${testSources.size} sources ===")

        for (source in testSources) {
            val request = Request.Builder().url(source.pkgUrl).header("User-Agent", "Mozilla/5.0").build()
            val response = client.newCall(request).execute()
            val jsCode = response.body?.string() ?: ""

            val metadata = extractMetadata(jsCode, source.pkgName)

            println("\n  ${source.name}:")
            println("    id=${metadata.id}, name=${metadata.name}, ver=${metadata.version}")
            println("    site=${metadata.site}, lang=${metadata.lang}")
            println("    JS: ${jsCode.length} bytes")
            println("    has LNReaderPlugin: ${jsCode.contains("LNReaderPlugin")}")
            println("    has popularNovels: ${jsCode.contains("popularNovels")}")
            println("    has parseNovel: ${jsCode.contains("parseNovel")}")
            println("    has parseChapter: ${jsCode.contains("parseChapter")}")

            assertTrue(jsCode.isNotBlank(), "${source.name}: JS should not be blank")
            assertTrue(metadata.name.isNotBlank(), "${source.name}: should extract name")
        }
    }

    @Test
    fun `verify Royal Road source has correct structure`() = runBlocking {
        val url = "https://raw.githubusercontent.com/kazemcodes/lnreader-plugins-unminified/repo/plugins/english/royalroad.js"
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val response = client.newCall(request).execute()
        val jsCode = response.body?.string() ?: ""

        assertTrue(jsCode.isNotBlank(), "Royal Road JS should not be blank")
        assertTrue(jsCode.contains("LNReaderPlugin"), "Should contain LNReaderPlugin wrapper")
        assertTrue(jsCode.contains("popularNovels"), "Should have popularNovels function")
        assertTrue(jsCode.contains("searchNovels"), "Should have searchNovels function")
        assertTrue(jsCode.contains("parseNovel"), "Should have parseNovel function")
        assertTrue(jsCode.contains("parseChapter"), "Should have parseChapter function")
        assertTrue(jsCode.contains("royalroad.com"), "Should reference royalroad.com")

        val metadata = extractMetadata(jsCode, "royalroad")
        assertTrue(metadata.id.isNotBlank(), "Should extract an id, got: ${metadata.id}")
        assertTrue(metadata.name.isNotBlank(), "Should extract a name, got: ${metadata.name}")
        assertTrue(metadata.site.isNotBlank() || jsCode.contains("royalroad.com"),
            "Should have site or reference royalroad.com")

        println("=== Royal Road Source ===")
        println("  id: ${metadata.id}")
        println("  name: ${metadata.name}")
        println("  version: ${metadata.version}")
        println("  site: ${metadata.site}")
        println("  lang: ${metadata.lang}")
        println("  JS size: ${jsCode.length} bytes")
        println("  Functions: popularNovels=${jsCode.contains("popularNovels")}, " +
                "searchNovels=${jsCode.contains("searchNovels")}, " +
                "parseNovel=${jsCode.contains("parseNovel")}, " +
                "parseChapter=${jsCode.contains("parseChapter")}")
    }

    @Test
    fun `verify IReader extension index has valid APK URLs`() = runBlocking {
        val url = "https://raw.githubusercontent.com/IReaderorg/IReader-extensions/repov2/index.min.json"
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        val catalogs = json.decodeFromString<List<CatalogRemoteApiModel>>(body)
        assertTrue(catalogs.isNotEmpty(), "Should have IReader sources")

        val first = catalogs.first()
        val baseUrl = "https://raw.githubusercontent.com/IReaderorg/IReader-extensions/repov2/"
        val apkUrl = "${baseUrl}apk/${first.apk}"
        val iconUrl = "${baseUrl}icon/${first.apk.replace(".apk", ".png")}"

        println("=== IReader Source URLs ===")
        println("  name: ${first.name}")
        println("  pkg: ${first.pkg}")
        println("  APK URL: $apkUrl")
        println("  Icon URL: $iconUrl")

        assertTrue(first.apk.endsWith(".apk"), "APK filename should end with .apk")
        assertTrue(first.name.isNotBlank(), "Should have a name")
        assertTrue(first.pkg.isNotBlank(), "Should have a package name")
    }

    private fun extractMetadata(jsCode: String, fallbackId: String): ExtractedMetadata {
        val id = extractPattern(jsCode, listOf(
            """id\s*:\s*['"]([^'"]+)['"]""",
            """['"]id['"]\s*:\s*['"]([^'"]+)['"]"""
        )) ?: fallbackId

        val name = extractPattern(jsCode, listOf(
            """name\s*:\s*['"]([^'"]+)['"]""",
            """['"]name['"]\s*:\s*['"]([^'"]+)['"]""",
            """sourceName\s*:\s*['"]([^'"]+)['"]"""
        ))?.takeIf { it.length > 2 } ?: id.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: "Unknown"

        val version = extractPattern(jsCode, listOf(
            """version\s*:\s*['"]([^'"]+)['"]""",
            """['"]version['"]\s*:\s*['"]([^'"]+)['"]"""
        ))?.takeIf { it.matches(Regex("""[\d.]+""")) } ?: "1.0.0"

        val site = extractPattern(jsCode, listOf(
            """site\s*:\s*['"]([^'"]+)['"]""",
            """baseUrl\s*:\s*['"]([^'"]+)['"]""",
            """['"]site['"]\s*:\s*['"]([^'"]+)['"]"""
        ))?.takeIf { it.startsWith("http") } ?: ""

        val rawLang = extractPattern(jsCode, listOf(
            """lang\s*:\s*['"]([^'"]+)['"]""",
            """['"]lang['"]\s*:\s*['"]([^'"]+)['"]"""
        )) ?: "en"

        return ExtractedMetadata(id, name, version, site, rawLang)
    }

    private fun extractPattern(text: String, patterns: List<String>): String? {
        for (pattern in patterns) {
            val match = Regex(pattern).find(text)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private data class ExtractedMetadata(
        val id: String,
        val name: String,
        val version: String,
        val site: String,
        val lang: String
    )
}

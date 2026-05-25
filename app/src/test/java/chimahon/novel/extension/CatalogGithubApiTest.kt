package chimahon.novel.extension

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CatalogGithubApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parseIReaderFormat parses valid JSON`() {
        val response = """[
            {
                "name": "Test Source",
                "pkg": "test.source",
                "version": "1.0.0",
                "code": 1,
                "lang": "en",
                "apk": "test-source.apk",
                "id": 12345,
                "description": "A test source",
                "nsfw": false
            }
        ]"""

        val catalogs = json.decodeFromString<List<CatalogRemoteApiModel>>(response)

        assertEquals(1, catalogs.size)
        assertEquals("Test Source", catalogs[0].name)
        assertEquals("test.source", catalogs[0].pkg)
        assertEquals("1.0.0", catalogs[0].version)
        assertEquals(1, catalogs[0].code)
        assertEquals("en", catalogs[0].lang)
        assertEquals("test-source.apk", catalogs[0].apk)
        assertEquals(12345L, catalogs[0].id)
        assertEquals("A test source", catalogs[0].description)
        assertEquals(false, catalogs[0].nsfw)
    }

    @Test
    fun `parseLNReaderFormat parses valid JSON`() {
        val response = """[
            {
                "id": "novelupdates",
                "name": "Novel Updates",
                "version": "1.0.0",
                "url": "https://example.com/novelupdates.js",
                "description": "Novel Updates source",
                "lang": "English",
                "iconUrl": "https://example.com/icon.png"
            }
        ]"""

        val catalogs = json.decodeFromString<List<LNReaderPluginModel>>(response)

        assertEquals(1, catalogs.size)
        assertEquals("novelupdates", catalogs[0].id)
        assertEquals("Novel Updates", catalogs[0].name)
        assertEquals("1.0.0", catalogs[0].version)
        assertEquals("https://example.com/novelupdates.js", catalogs[0].url)
        assertEquals("Novel Updates source", catalogs[0].description)
        assertEquals("English", catalogs[0].lang)
        assertEquals("https://example.com/icon.png", catalogs[0].iconUrl)
    }

    @Test
    fun `parseLNReaderFormat handles missing optional fields`() {
        val response = """[
            {
                "id": "minimal",
                "name": "Minimal Source",
                "version": "1.0.0",
                "url": "https://example.com/minimal.js"
            }
        ]"""

        val catalogs = json.decodeFromString<List<LNReaderPluginModel>>(response)

        assertEquals(1, catalogs.size)
        assertEquals("minimal", catalogs[0].id)
        assertEquals("Minimal Source", catalogs[0].name)
        assertEquals(null, catalogs[0].description)
        assertEquals(null, catalogs[0].lang)
        assertEquals(null, catalogs[0].iconUrl)
    }

    @Test
    fun `parseIReaderFormat handles multiple entries`() {
        val response = """[
            {
                "name": "Source 1",
                "pkg": "source.1",
                "version": "1.0.0",
                "code": 1,
                "lang": "en",
                "apk": "source1.apk",
                "id": 1,
                "description": "First source",
                "nsfw": false
            },
            {
                "name": "Source 2",
                "pkg": "source.2",
                "version": "2.0.0",
                "code": 2,
                "lang": "ja",
                "apk": "source2.apk",
                "id": 2,
                "description": "Second source",
                "nsfw": true
            }
        ]"""

        val catalogs = json.decodeFromString<List<CatalogRemoteApiModel>>(response)

        assertEquals(2, catalogs.size)
        assertEquals("Source 1", catalogs[0].name)
        assertEquals("Source 2", catalogs[1].name)
        assertEquals(true, catalogs[1].nsfw)
    }

    @Test
    fun `InstalledExtension serializes correctly`() {
        val extension = InstalledExtension(
            id = "test.source",
            name = "Test Source",
            lang = "en",
            version = "1.0.0",
            filePath = "/path/to/file.js",
            isEnabled = true,
            repositoryType = "LNREADER",
            iconUrl = "https://example.com/icon.png"
        )

        val jsonStr = json.encodeToString(InstalledExtension.serializer(), extension)
        val decoded = json.decodeFromString<InstalledExtension>(jsonStr)

        assertEquals(extension.id, decoded.id)
        assertEquals(extension.name, decoded.name)
        assertEquals(extension.lang, decoded.lang)
        assertEquals(extension.version, decoded.version)
        assertEquals(extension.filePath, decoded.filePath)
        assertEquals(extension.isEnabled, decoded.isEnabled)
        assertEquals(extension.repositoryType, decoded.repositoryType)
        assertEquals(extension.iconUrl, decoded.iconUrl)
    }

    @Test
    fun `InstalledExtension handles default values`() {
        val extension = InstalledExtension(
            id = "test",
            name = "Test",
            lang = "en",
            version = "1.0.0",
            filePath = "/path"
        )

        assertEquals(true, extension.isEnabled)
        assertEquals("LNREADER", extension.repositoryType)
        assertEquals("", extension.iconUrl)
    }
}

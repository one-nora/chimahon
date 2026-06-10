package chimahon.novel.extension.ireader

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import ireader.core.http.HttpClientsInterface
import ireader.core.http.NetworkConfig
import ireader.core.http.SSLConfiguration
import ireader.core.http.SimpleBrowserEngine
import ireader.core.http.SimpleCookieSynchronizer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class ChimahonIReaderHttpClients(
    okHttpClient: OkHttpClient,
) : HttpClientsInterface {
    override val browser = SimpleBrowserEngine()
    override val config = NetworkConfig()
    override val sslConfig = SSLConfiguration()
    override val cookieSynchronizer = SimpleCookieSynchronizer()

    override val default: HttpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override val cloudflareClient: HttpClient = default
}

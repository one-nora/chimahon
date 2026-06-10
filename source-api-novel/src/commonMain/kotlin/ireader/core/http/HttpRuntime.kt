package ireader.core.http

import io.ktor.client.HttpClient

const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

interface BrowserEngine
interface CookieSynchronizer

data class NetworkConfig(
    val userAgent: String = DEFAULT_USER_AGENT,
)

data class SSLConfiguration(
    val enabled: Boolean = true,
)

interface HttpClientsInterface {
    val browser: BrowserEngine
    val default: HttpClient
    val cloudflareClient: HttpClient
    val config: NetworkConfig
    val sslConfig: SSLConfiguration
    val cookieSynchronizer: CookieSynchronizer
}

class SimpleBrowserEngine : BrowserEngine
class SimpleCookieSynchronizer : CookieSynchronizer

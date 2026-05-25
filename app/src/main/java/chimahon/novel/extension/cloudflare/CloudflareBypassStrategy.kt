package chimahon.novel.extension.cloudflare

interface CloudflareBypassStrategy {
    val priority: Int
    val name: String
    suspend fun canHandle(challenge: CloudflareChallenge): Boolean
    suspend fun bypass(url: String, challenge: CloudflareChallenge, config: BypassConfig): BypassResult
}

data class BypassConfig(
    val timeout: Long = 60000L,
    val userAgent: String? = null,
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1000L
)

sealed class BypassResult {
    data class Success(val cookie: ClearanceCookie) : BypassResult()
    data class CachedCookie(val cookie: ClearanceCookie) : BypassResult()
    object NotNeeded : BypassResult()
    data class Failed(val reason: String, val challenge: CloudflareChallenge? = null, val canRetry: Boolean = false) : BypassResult()
    data class UserInteractionRequired(val challenge: CloudflareChallenge, val message: String) : BypassResult()

    fun isSuccess(): Boolean = this is Success || this is CachedCookie || this is NotNeeded
    fun extractCookie(): ClearanceCookie? = when (this) {
        is Success -> cookie
        is CachedCookie -> cookie
        else -> null
    }
}

class CookieReplayStrategy(private val cookieStore: CloudflareCookieStore) : CloudflareBypassStrategy {
    override val priority = 200
    override val name = "CookieReplay"

    override suspend fun canHandle(challenge: CloudflareChallenge): Boolean {
        return challenge !is CloudflareChallenge.None
    }

    override suspend fun bypass(url: String, challenge: CloudflareChallenge, config: BypassConfig): BypassResult {
        val domain = url.extractDomain()
        val cookie = cookieStore.getClearanceCookie(domain)
        return if (cookie != null && cookieStore.isValid(cookie)) {
            if (config.userAgent == null || config.userAgent == cookie.userAgent) {
                BypassResult.CachedCookie(cookie)
            } else {
                BypassResult.Failed("User agent mismatch", challenge, canRetry = true)
            }
        } else {
            BypassResult.Failed("No valid cached cookie", challenge, canRetry = true)
        }
    }
}

fun String.extractDomain(): String {
    return this.lowercase()
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("www.")
        .substringBefore("/")
        .substringBefore(":")
}

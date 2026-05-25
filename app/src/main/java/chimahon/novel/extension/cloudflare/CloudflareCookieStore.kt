package chimahon.novel.extension.cloudflare

data class ClearanceCookie(
    val cfClearance: String,
    val cfBm: String? = null,
    val userAgent: String,
    val timestamp: Long,
    val expiresAt: Long,
    val domain: String
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
    fun remainingValidityMs(): Long = maxOf(0, expiresAt - System.currentTimeMillis())
    fun isExpiringSoon(): Boolean = remainingValidityMs() < 5 * 60 * 1000

    companion object {
        const val DEFAULT_VALIDITY_MS = 30 * 60 * 1000L
        const val MAX_VALIDITY_MS = 2 * 60 * 60 * 1000L
    }
}

interface CloudflareCookieStore {
    suspend fun getClearanceCookie(domain: String): ClearanceCookie?
    suspend fun saveClearanceCookie(domain: String, cookie: ClearanceCookie)
    suspend fun isValid(cookie: ClearanceCookie): Boolean
    suspend fun invalidate(domain: String)
    suspend fun clearAll()
}

class InMemoryCloudflareCookieStore : CloudflareCookieStore {
    private val cookies = mutableMapOf<String, ClearanceCookie>()

    override suspend fun getClearanceCookie(domain: String): ClearanceCookie? {
        val cookie = cookies[domain.normalizeDomain()]
        return if (cookie != null && !cookie.isExpired()) cookie else null
    }

    override suspend fun saveClearanceCookie(domain: String, cookie: ClearanceCookie) {
        cookies[domain.normalizeDomain()] = cookie
    }

    override suspend fun isValid(cookie: ClearanceCookie): Boolean {
        return !cookie.isExpired() && cookie.cfClearance.isNotBlank()
    }

    override suspend fun invalidate(domain: String) {
        cookies.remove(domain.normalizeDomain())
    }

    override suspend fun clearAll() {
        cookies.clear()
    }

    private fun String.normalizeDomain(): String {
        return this.lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("www.")
            .substringBefore("/")
            .substringBefore(":")
    }
}

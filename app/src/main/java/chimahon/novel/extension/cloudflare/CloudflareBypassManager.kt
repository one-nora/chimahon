package chimahon.novel.extension.cloudflare

class CloudflareBypassManager(
    private val strategies: List<CloudflareBypassStrategy>,
    private val cookieStore: CloudflareCookieStore
) {
    suspend fun bypass(url: String, statusCode: Int, headers: Map<String, String>, body: String): BypassResult {
        val challenge = CloudflareDetector.detect(statusCode, headers, body)
        if (challenge is CloudflareChallenge.None) return BypassResult.NotNeeded

        val domain = url.extractDomain()
        val cachedCookie = cookieStore.getClearanceCookie(domain)
        if (cachedCookie != null && cookieStore.isValid(cachedCookie)) {
            return BypassResult.CachedCookie(cachedCookie)
        }

        val sorted = strategies.sortedByDescending { it.priority }
        for (strategy in sorted) {
            if (!strategy.canHandle(challenge)) continue
            val result = strategy.bypass(url, challenge, BypassConfig())
            when (result) {
                is BypassResult.Success -> {
                    cookieStore.saveClearanceCookie(domain, result.cookie)
                    return result
                }
                is BypassResult.CachedCookie -> return result
                is BypassResult.NotNeeded -> return result
                is BypassResult.UserInteractionRequired -> return result
                is BypassResult.Failed -> {
                    if (!result.canRetry) return result
                }
            }
        }

        return BypassResult.Failed("All strategies failed", challenge)
    }

    companion object {
        fun createDefault(cookieStore: CloudflareCookieStore): CloudflareBypassManager {
            return CloudflareBypassManager(
                strategies = listOf(CookieReplayStrategy(cookieStore)),
                cookieStore = cookieStore
            )
        }
    }
}

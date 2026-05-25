package chimahon.novel.extension.cloudflare

sealed class CloudflareChallenge {
    object None : CloudflareChallenge()
    data class JSChallenge(val rayId: String? = null) : CloudflareChallenge()
    data class ManagedChallenge(val rayId: String? = null) : CloudflareChallenge()
    data class TurnstileChallenge(val siteKey: String, val rayId: String? = null) : CloudflareChallenge()
    data class CaptchaChallenge(val siteKey: String, val rayId: String? = null) : CloudflareChallenge()
    data class RateLimited(val retryAfter: Long?, val rayId: String? = null) : CloudflareChallenge()
    data class BlockedIP(val rayId: String? = null) : CloudflareChallenge()
    data class Unknown(val rayId: String? = null, val hints: List<String> = emptyList()) : CloudflareChallenge()
}

object CloudflareDetector {
    private val CF_HEADERS = listOf("cf-ray", "cf-cache-status", "cf-request-id")
    private val JS_CHALLENGE_PATTERNS = listOf(
        "Just a moment", "Checking your browser", "cf-browser-verification",
        "_cf_chl_opt", "cf_chl_prog", "challenge-platform"
    )
    private val BLOCK_PATTERNS = listOf(
        "cf-error-details", "Access denied", "Sorry, you have been blocked",
        "You have been blocked", "Error 1020"
    )

    fun detect(statusCode: Int, headers: Map<String, String>, body: String): CloudflareChallenge {
        val rayId = headers["cf-ray"]

        if (!isCloudflareResponse(statusCode, headers, body)) {
            return CloudflareChallenge.None
        }

        if (statusCode == 429) {
            val retryAfter = headers["retry-after"]?.toLongOrNull()
            return CloudflareChallenge.RateLimited(retryAfter, rayId)
        }

        if (statusCode == 403 && BLOCK_PATTERNS.any { body.contains(it, ignoreCase = true) }) {
            return CloudflareChallenge.BlockedIP(rayId)
        }

        if (statusCode in listOf(403, 503) && JS_CHALLENGE_PATTERNS.any { body.contains(it, ignoreCase = true) }) {
            return CloudflareChallenge.JSChallenge(rayId)
        }

        if (rayId != null && statusCode in listOf(403, 503)) {
            return CloudflareChallenge.Unknown(rayId, listOf("Cloudflare response with unknown challenge"))
        }

        return CloudflareChallenge.None
    }

    private fun isCloudflareResponse(statusCode: Int, headers: Map<String, String>, body: String): Boolean {
        if (statusCode !in listOf(403, 429, 503)) return false
        val hasCfHeaders = CF_HEADERS.any { headers[it] != null }
        val serverIsCf = headers["server"]?.contains("cloudflare", ignoreCase = true) == true
        val hasCfBody = body.contains("cloudflare", ignoreCase = true) ||
                body.contains("cf-browser-verification", ignoreCase = true)
        return hasCfHeaders || serverIsCf || hasCfBody
    }
}

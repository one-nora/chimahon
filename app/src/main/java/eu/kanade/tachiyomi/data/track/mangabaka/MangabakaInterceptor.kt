package eu.kanade.tachiyomi.data.track.mangabaka

import eu.kanade.tachiyomi.data.track.Tracker
import okhttp3.Interceptor
import okhttp3.Response

class MangabakaInterceptor(private val tracker: Tracker) : Interceptor {

    private var apiKey: String? = null

    fun newAuth(token: String?) {
        apiKey = token
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()

        val key = apiKey ?: tracker.getPassword().takeIf { it.isNotBlank() }
        if (key != null) {
            request = request.newBuilder()
                .header("x-api-key", key)
                .build()
        }

        return chain.proceed(request)
    }
}

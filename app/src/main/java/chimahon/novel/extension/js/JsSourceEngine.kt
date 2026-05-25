package chimahon.novel.extension.js

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class JsSourceMetadata(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val site: String = "",
    val lang: String = "en",
    val iconUrl: String? = null,
    val nsfw: Boolean = false
)

class JsSourceEngine(private val context: Context) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private var webView: WebView? = null
    private var isReady = CompletableDeferred<Boolean>()
    private var currentSourceId: String? = null

    suspend fun initialize(): Boolean {
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true

            addJavascriptInterface(BridgeInterface(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    isReady.complete(true)
                }
            }

            loadDataWithBaseURL(
                "file:///android_asset/",
                getBootstrapHtml(),
                "text/html",
                "UTF-8",
                null
            )
        }

        return withTimeoutOrNull(10000) {
            isReady.await()
        } ?: false
    }

    suspend fun loadSource(script: String, sourceId: String): JsSourceMetadata? {
        currentSourceId = sourceId
        val result = executeJs("""
            (function() {
                try {
                    $script

                    var plugin = null;
                    if (typeof LNReaderPlugin !== 'undefined' && LNReaderPlugin.default) {
                        plugin = LNReaderPlugin.default;
                    } else if (typeof module !== 'undefined' && module.exports) {
                        plugin = module.exports;
                    }

                    if (plugin) {
                        return JSON.stringify({
                            id: plugin.id || '$sourceId',
                            name: plugin.name || 'Unknown',
                            version: plugin.version || '1.0.0',
                            site: plugin.site || '',
                            lang: plugin.lang || 'en'
                        });
                    }

                    return JSON.stringify({
                        id: typeof id !== 'undefined' ? id : '$sourceId',
                        name: typeof name !== 'undefined' ? name : 'Unknown',
                        version: typeof version !== 'undefined' ? version : '1.0.0',
                        site: typeof site !== 'undefined' ? site : '',
                        lang: typeof lang !== 'undefined' ? lang : 'en'
                    });
                } catch(e) {
                    return JSON.stringify({error: e.message});
                }
            })()
        """)

        return result?.let { parseMetadata(it) }
    }

    suspend fun callFunction(functionName: String, vararg args: Any?): String? {
        val argsJson = args.joinToString(",") { arg ->
            when (arg) {
                is String -> "\"${arg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")}\""
                is Int, is Long, is Float, is Double -> arg.toString()
                is Boolean -> arg.toString()
                null -> "null"
                else -> "\"${arg.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
            }
        }

        return executeJs("""
            (async function() {
                try {
                    var fn = null;
                    if (typeof LNReaderPlugin !== 'undefined' && LNReaderPlugin.default && typeof LNReaderPlugin.default.$functionName === 'function') {
                        fn = LNReaderPlugin.default.$functionName.bind(LNReaderPlugin.default);
                    } else if (typeof $functionName === 'function') {
                        fn = $functionName;
                    }

                    if (fn) {
                        var result = await fn($argsJson);
                        return JSON.stringify(result);
                    }
                    return JSON.stringify({error: 'Function $functionName not found'});
                } catch(e) {
                    return JSON.stringify({error: e.message, stack: e.stack});
                }
            })()
        """)
    }

    suspend fun callFunctionWithFilters(functionName: String, page: Int, filters: String): String? {
        return executeJs("""
            (async function() {
                try {
                    var fn = null;
                    if (typeof LNReaderPlugin !== 'undefined' && LNReaderPlugin.default && typeof LNReaderPlugin.default.$functionName === 'function') {
                        fn = LNReaderPlugin.default.$functionName.bind(LNReaderPlugin.default);
                    } else if (typeof $functionName === 'function') {
                        fn = $functionName;
                    }

                    if (fn) {
                        var result = await fn($page, {filters: $filters});
                        return JSON.stringify(result);
                    }
                    return JSON.stringify({error: 'Function $functionName not found'});
                } catch(e) {
                    return JSON.stringify({error: e.message, stack: e.stack});
                }
            })()
        """)
    }

    private suspend fun executeJs(script: String): String? {
        val deferred = CompletableDeferred<String?>()
        webView?.post {
            webView?.evaluateJavascript(script) { result ->
                val cleaned = result?.removeSurrounding("\"")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                deferred.complete(cleaned)
            }
        }
        return withTimeoutOrNull(30000) { deferred.await() }
    }

    private fun parseMetadata(jsonStr: String): JsSourceMetadata? {
        return try {
            val cleaned = jsonStr.replace("\\\"", "\"").removeSurrounding("\"")
            json.decodeFromString<JsSourceMetadata>(cleaned)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /**
         * Extract metadata from JS source code WITHOUT executing it.
         * Uses regex patterns to find id, name, version, site, lang, icon.
         * This allows showing sources in UI instantly without waiting for JS engine.
         */
        fun extractMetadataFromCode(jsCode: String, fallbackId: String): JsSourceMetadata? {
            if (jsCode.isBlank()) return null
            if (jsCode.contains("404") && jsCode.contains("Not Found") && jsCode.length < 1000) return null
            if (jsCode.trim().startsWith("<!DOCTYPE") || jsCode.trim().startsWith("<html")) return null

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

            val icon = extractPattern(jsCode, listOf(
                """icon\s*:\s*['"]([^'"]+)['"]""",
                """['"]icon['"]\s*:\s*['"]([^'"]+)['"]"""
            )) ?: ""

            return JsSourceMetadata(
                id = id,
                name = name,
                version = version,
                site = site,
                lang = rawLang,
                iconUrl = icon.takeIf { it.isNotBlank() }
            )
        }

        private fun extractPattern(text: String, patterns: List<String>): String? {
            for (pattern in patterns) {
                val match = Regex(pattern).find(text)
                if (match != null) return match.groupValues[1]
            }
            return null
        }
    }

    fun destroy() {
        webView?.destroy()
        webView = null
    }

    private fun getBootstrapHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <script>
                    var console = {
                        log: function() { AndroidBridge.log(Array.from(arguments).join(' ')); },
                        error: function() { AndroidBridge.log('ERROR: ' + Array.from(arguments).join(' ')); },
                        warn: function() { AndroidBridge.log('WARN: ' + Array.from(arguments).join(' ')); }
                    };

                    var _fetchCallbacks = {};
                    var _fetchCallbackId = 0;

                    async function fetch(url, options) {
                        return new Promise(function(resolve, reject) {
                            try {
                                var method = (options && options.method) || 'GET';
                                var headers = (options && options.headers) || {};
                                var body = (options && options.body) || null;
                                var cbId = '_fetchCb_' + (++_fetchCallbackId);

                                _fetchCallbacks[cbId] = function(response) {
                                    delete _fetchCallbacks[cbId];
                                    try {
                                        var data = JSON.parse(response);
                                        resolve({
                                            ok: data.status >= 200 && data.status < 300,
                                            status: data.status,
                                            statusText: data.statusText || '',
                                            headers: data.headers || {},
                                            text: function() { return Promise.resolve(data.body); },
                                            json: function() { return Promise.resolve(JSON.parse(data.body)); }
                                        });
                                    } catch(e) {
                                        reject(e);
                                    }
                                };

                                AndroidBridge.httpRequest(url, method, JSON.stringify(headers), body, cbId);
                            } catch(e) {
                                reject(e);
                            }
                        });
                    }

                    window._resolveFetchCallback = function(cbId, response) {
                        if (_fetchCallbacks[cbId]) {
                            _fetchCallbacks[cbId](response);
                        }
                    };

                    var Storage = function() { this._data = {}; };
                    Storage.prototype.get = function(key, defaultValue) {
                        return this._data.hasOwnProperty(key) ? this._data[key] : (defaultValue !== undefined ? defaultValue : null);
                    };
                    Storage.prototype.set = function(key, value) { this._data[key] = value; };
                    var storage = new Storage();
                </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
    }

    inner class BridgeInterface {
        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("JsSource", message)
        }

        @JavascriptInterface
        fun httpRequest(url: String, method: String, headersJson: String, body: String?, callbackId: String) {
            Thread {
                try {
                    val requestBuilder = Request.Builder().url(url)

                    try {
                        val headers = json.parseToJsonElement(headersJson).jsonObject
                        headers.forEach { (key, value) ->
                            requestBuilder.addHeader(key, value.jsonPrimitive.content)
                        }
                    } catch (_: Exception) {}

                    when (method.uppercase()) {
                        "POST" -> {
                            val requestBody = body?.toRequestBody("application/json".toMediaType())
                                ?: "".toRequestBody("application/json".toMediaType())
                            requestBuilder.post(requestBody)
                        }
                        "PUT" -> {
                            val requestBody = body?.toRequestBody("application/json".toMediaType())
                                ?: "".toRequestBody("application/json".toMediaType())
                            requestBuilder.put(requestBody)
                        }
                        "DELETE" -> requestBuilder.delete()
                    }

                    val response = client.newCall(requestBuilder.build()).execute()
                    val responseBody = response.body?.string() ?: ""
                    val responseHeaders = response.headers.toMultimap()

                    val resultJson = json.encodeToString(
                        kotlinx.serialization.serializer(),
                        mapOf(
                            "status" to response.code.toString(),
                            "statusText" to response.message,
                            "body" to responseBody,
                            "headers" to responseHeaders.mapValues { it.value.joinToString(",") }
                        )
                    )

                    val escapedResult = resultJson.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

                    webView?.post {
                        webView?.evaluateJavascript(
                            "window._resolveFetchCallback('$callbackId', '$escapedResult')",
                            null
                        )
                    }
                } catch (e: Exception) {
                    val errorJson = """{"status":0,"body":"${e.message?.replace("\"", "\\\"") ?: "Unknown error"}"}"""
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window._resolveFetchCallback('$callbackId', '$errorJson')",
                            null
                        )
                    }
                }
            }.start()
        }
    }
}

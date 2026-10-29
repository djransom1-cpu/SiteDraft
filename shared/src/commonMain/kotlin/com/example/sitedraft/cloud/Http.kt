package com.example.sitedraft.cloud

/** A finished HTTP exchange. [body] is the response text, whatever the status. */
class HttpResponse(val status: Int, val body: String) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * One HTTP request, off the main thread, with the platform's own client (HttpURLConnection on
 * the JVM, fetch in the browser, NSURLSession on iOS). Throws [CloudException] with
 * `offline = true` when the request never reached a server.
 */
expect suspend fun httpRequest(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): HttpResponse

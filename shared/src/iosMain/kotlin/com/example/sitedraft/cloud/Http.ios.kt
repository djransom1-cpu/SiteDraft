package com.example.sitedraft.cloud

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** NSURLSession. iOS uses the REST cloud service, so this is its whole network layer. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
    suspendCancellableCoroutine { continuation ->
        val nsUrl = NSURL.URLWithString(url) ?: run {
            continuation.resumeWithException(CloudException("Bad URL: $url"))
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest(uRL = nsUrl)
        request.setHTTPMethod(method)
        headers.forEach { (k, v) -> request.setValue(v, forHTTPHeaderField = k) }
        if (body != null) {
            @Suppress("CAST_NEVER_SUCCEEDS")
            request.setHTTPBody((body as NSString).dataUsingEncoding(NSUTF8StringEncoding))
        }
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            if (error != null) {
                continuation.resumeWithException(CloudException("No connection: ${error.localizedDescription}", offline = true))
                return@dataTaskWithRequest
            }
            val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            val text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() } ?: ""
            continuation.resume(HttpResponse(status, text))
        }
        task.resume()
        continuation.invokeOnCancellation { task.cancel() }
    }

package com.example.sitedraft.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HttpURLConnection, shared by Android and the desktop JVM. */
actual suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
    withContext(Dispatchers.IO) {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw CloudException("No connection: ${e.message}", e, offline = true)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.encodeToByteArray()) }
            }
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                throw CloudException("No connection: ${e.message}", e, offline = true)
            }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { it.readBytes().decodeToString() } ?: ""
            HttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

package com.example.sitedraft.cloud

import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.Promise

// fetch() in the browser. The status and body come back as one JSON string so only strings
// cross the Wasm/JS boundary; a network failure resolves to status 0.
@JsFun(
    """(method, url, headersJson, body, hasBody) => fetch(url, {
        method: method,
        headers: JSON.parse(headersJson),
        body: hasBody ? body : undefined
    }).then(r => r.text().then(t => JSON.stringify({status: r.status, body: t})))
      .catch(e => JSON.stringify({status: 0, body: String(e)}))"""
)
private external fun jsFetch(method: String, url: String, headersJson: String, body: String, hasBody: Boolean): Promise<JsString>

actual suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
    val headersJson = buildJsonObject { headers.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()
    val raw = jsFetch(method, url, headersJson, body ?: "", body != null).await<JsString>().toString()
    val parsed = Json.parseToJsonElement(raw).jsonObject
    val status = parsed["status"]?.jsonPrimitive?.intOrNull ?: 0
    val text = parsed["body"]?.jsonPrimitive?.contentOrNull ?: ""
    if (status == 0) throw CloudException("No connection: $text", offline = true)
    return HttpResponse(status, text)
}

package com.example.sitedraft.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Calls the `onCall` Cloud Functions in functions/index.js over plain HTTPS, the way the
 * Firebase client SDKs do it: POST `{"data": ...}` with the user's ID token as a bearer token,
 * answer `{"result": ...}` or `{"error": {"message": ...}}`. One implementation for every
 * target, no extra SDK module.
 */
object CloudFunctions {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun call(cloud: CloudService, name: String, data: JsonObject = JsonObject(emptyMap())): JsonElement {
        val token = cloud.idToken() ?: throw CloudException("Sign in first.")
        val url = "${CloudConfig.functionsBaseUrl}/$name"
        val body = buildJsonObject { put("data", data) }.toString()
        val response = httpRequest(
            "POST", url,
            mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token"),
            body
        )
        val parsed = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        val error = parsed?.get("error")?.let { it as? JsonObject }
        if (!response.isSuccess || error != null) {
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull
                ?: "The server answered ${response.status}."
            throw CloudException(message)
        }
        return parsed?.get("result") ?: JsonPrimitive(null as String?)
    }

    /** A Stripe Checkout URL for a new subscription for the signed-in user. */
    suspend fun createCheckoutUrl(cloud: CloudService, returnUrl: String?): String {
        val result = call(cloud, "createCheckoutSession", buildJsonObject {
            if (returnUrl != null) put("returnUrl", JsonPrimitive(returnUrl))
        })
        return result.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: throw CloudException("No checkout link came back.")
    }

    /** A Stripe customer-portal URL (change card, cancel) for the signed-in user. */
    suspend fun createPortalUrl(cloud: CloudService, returnUrl: String?): String {
        val result = call(cloud, "createPortalSession", buildJsonObject {
            if (returnUrl != null) put("returnUrl", JsonPrimitive(returnUrl))
        })
        return result.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: throw CloudException("No portal link came back.")
    }
}

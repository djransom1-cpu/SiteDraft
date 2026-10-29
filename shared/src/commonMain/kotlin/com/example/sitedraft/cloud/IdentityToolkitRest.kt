package com.example.sitedraft.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The two Firebase Authentication emails over the Identity Toolkit REST API, for SDKs that
 * do not implement them (GitLive's desktop port). Same endpoints RestCloudService uses.
 */
object IdentityToolkitRest {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun sendOobCode(body: JsonObject) {
        val response = httpRequest(
            "POST",
            "https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=${CloudConfig.apiKey}",
            mapOf("Content-Type" to "application/json"),
            body.toString()
        )
        if (!response.isSuccess) {
            val message = runCatching {
                json.parseToJsonElement(response.body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw CloudException(friendlyAuthError(message ?: "HTTP ${response.status}", false))
        }
    }

    suspend fun sendEmailVerification(idToken: String) = sendOobCode(buildJsonObject {
        put("requestType", JsonPrimitive("VERIFY_EMAIL"))
        put("idToken", JsonPrimitive(idToken))
    })

    suspend fun sendPasswordReset(email: String) = sendOobCode(buildJsonObject {
        put("requestType", JsonPrimitive("PASSWORD_RESET"))
        put("email", JsonPrimitive(email))
    })
}

/**
 * Whether the user's email is verified: [direct] when the SDK implements it, otherwise the
 * `email_verified` claim of the ID token [token] returns. False when neither can tell.
 */
suspend fun emailVerifiedOf(direct: () -> Boolean, token: suspend () -> String?): Boolean = try {
    direct()
} catch (e: NotImplementedError) {
    token()?.let { Jwt.booleanClaim(it, "email_verified") } ?: false
}

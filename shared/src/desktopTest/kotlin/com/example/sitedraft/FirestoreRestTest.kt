package com.example.sitedraft

import com.example.sitedraft.cloud.FirestoreValues
import com.example.sitedraft.cloud.friendlyAuthError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The REST wire format the web and iOS targets speak to Firestore. */
class FirestoreRestTest {
    @Test
    fun encodesEveryValueKindTheAppUses() {
        val fields = buildJsonObject {
            put("name", JsonPrimitive("Kitchen"))
            put("updatedAt", JsonPrimitive(1_800_000_000_000L))
            put("chunkCount", JsonPrimitive(2))
            put("flag", JsonPrimitive(true))
            put("ratio", JsonPrimitive(0.5))
            put("sub", buildJsonObject { put("status", JsonPrimitive("active")) })
            put("tags", buildJsonArray { add(JsonPrimitive("a")) })
        }
        val encoded = FirestoreValues.encodeFields(fields)
        assertEquals("Kitchen", encoded["name"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        // integerValue is a string on the wire, and 64-bit safe.
        assertEquals("1800000000000", encoded["updatedAt"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content)
        assertEquals("2", encoded["chunkCount"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content)
        assertEquals("true", encoded["flag"]!!.jsonObject["booleanValue"]!!.jsonPrimitive.content)
        assertEquals("0.5", encoded["ratio"]!!.jsonObject["doubleValue"]!!.jsonPrimitive.content)
        assertEquals("active", encoded["sub"]!!.jsonObject["mapValue"]!!.jsonObject["fields"]!!.jsonObject["status"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        assertEquals(fields, FirestoreValues.decodeFields(encoded))
    }

    @Test
    fun decodesAFirestoreDocumentBody() {
        val body = Json.parseToJsonElement(
            """{"name":"projects/p/databases/(default)/documents/users/u1",
                "fields":{"email":{"stringValue":"a@b.c"},
                          "subscription":{"mapValue":{"fields":{"status":{"stringValue":"trialing"},"currentPeriodEnd":{"integerValue":"1800000000000"}}}},
                          "ts":{"timestampValue":"2026-10-29T00:00:00Z"}}}"""
        ).jsonObject
        val fields = FirestoreValues.decodeFields(body["fields"]!!.jsonObject)
        assertEquals("a@b.c", fields["email"]!!.jsonPrimitive.content)
        assertEquals(1_800_000_000_000L, fields["subscription"]!!.jsonObject["currentPeriodEnd"]!!.jsonPrimitive.content.toLong())
        assertEquals("u1", FirestoreValues.documentId(body["name"]!!.jsonPrimitive.content))
    }

    @Test
    fun authErrorsBecomePlainEnglish() {
        assertEquals("Email or password is incorrect.", friendlyAuthError("INVALID_LOGIN_CREDENTIALS", false))
        assertEquals("An account with that email already exists. Sign in instead.", friendlyAuthError("EMAIL_EXISTS", true))
        assertEquals("Password must be at least 6 characters.", friendlyAuthError("WEAK_PASSWORD : Password should be at least 6 characters", true))
        assertEquals("Could not sign in.", friendlyAuthError(null, false))
    }
}

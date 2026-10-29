package com.example.sitedraft.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The Firestore REST wire format: a document is `{"name": ..., "fields": {k: {"stringValue": v}}}`
 * with every value wrapped in a typed holder. These helpers turn plain JSON values into that
 * form and back. Pure functions, unit-tested in FirestoreRestTest.
 */
object FirestoreValues {
    /** Plain JSON -> Firestore typed value. Integers stay integers (integerValue is a string). */
    fun encode(value: JsonElement): JsonObject = when (value) {
        is JsonNull -> buildJsonObject { put("nullValue", JsonNull) }
        is JsonPrimitive -> when {
            value.isString -> buildJsonObject { put("stringValue", value) }
            value.booleanOrNull != null -> buildJsonObject { put("booleanValue", value) }
            value.longOrNull != null -> buildJsonObject { put("integerValue", JsonPrimitive(value.content)) }
            else -> buildJsonObject { put("doubleValue", JsonPrimitive(value.doubleOrNull ?: 0.0)) }
        }
        is JsonArray -> buildJsonObject { put("arrayValue", buildJsonObject { put("values", JsonArray(value.map { encode(it) })) }) }
        is JsonObject -> buildJsonObject { put("mapValue", buildJsonObject { put("fields", encodeFields(value)) }) }
    }

    fun encodeFields(fields: JsonObject): JsonObject = JsonObject(fields.mapValues { encode(it.value) })

    /** Firestore typed value -> plain JSON. Timestamps and references come back as strings. */
    fun decode(value: JsonElement): JsonElement {
        val obj = value as? JsonObject ?: return JsonNull
        obj["stringValue"]?.let { return it }
        obj["booleanValue"]?.let { return it }
        obj["integerValue"]?.let { return JsonPrimitive(it.jsonPrimitive.content.toLongOrNull() ?: 0L) }
        obj["doubleValue"]?.let { return it }
        obj["timestampValue"]?.let { return it }
        obj["referenceValue"]?.let { return it }
        obj["mapValue"]?.let { m -> return decodeFields(m.jsonObject["fields"] as? JsonObject ?: JsonObject(emptyMap())) }
        obj["arrayValue"]?.let { a -> return JsonArray((a.jsonObject["values"] as? JsonArray)?.map { decode(it) } ?: emptyList()) }
        return JsonNull
    }

    fun decodeFields(fields: JsonObject): JsonObject = JsonObject(fields.mapValues { decode(it.value) })

    /** The last path segment of a document resource name: `.../documents/users/abc` -> `abc`. */
    fun documentId(resourceName: String): String = resourceName.substringAfterLast('/')
}

/** Reads a decoded document into the typed classes the app uses. */
internal object CloudDocs {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun userFrom(fields: JsonObject): UserDoc = json.decodeFromJsonElement(UserDoc.serializer(), fields)

    fun projectMetaFrom(name: String, fields: JsonObject): ProjectMeta = ProjectMeta(
        name = fields["name"]?.jsonPrimitive?.contentOrNull ?: name,
        updatedAt = fields["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        device = fields["device"]?.jsonPrimitive?.contentOrNull,
        chunkCount = fields["chunkCount"]?.jsonPrimitive?.longOrNull?.toInt() ?: 1,
    )

    /** Fields of the project document; the first chunk rides on it as `json`. */
    fun projectFields(name: String, firstChunk: String, chunkCount: Int, updatedAt: Long, device: String): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("updatedAt", JsonPrimitive(updatedAt))
        put("device", JsonPrimitive(device))
        put("chunkCount", JsonPrimitive(chunkCount))
        put("json", JsonPrimitive(firstChunk))
    }

    fun chunkFields(index: Int, text: String): JsonObject = buildJsonObject {
        put("index", JsonPrimitive(index))
        put("json", JsonPrimitive(text))
    }

    fun touchFields(email: String?, displayName: String?, device: String, now: Long): JsonObject = buildJsonObject {
        if (email != null) put("email", JsonPrimitive(email))
        if (displayName != null) put("displayName", JsonPrimitive(displayName))
        put("lastSeenAt", JsonPrimitive(now))
        put("lastDevice", JsonPrimitive(device))
    }

    fun usersPath(uid: String) = "users/$uid"
    fun projectsPath(uid: String) = "users/$uid/projects"
    fun projectPath(uid: String, name: String) = "users/$uid/projects/${encodeDocId(name)}"
    fun chunksPath(uid: String, name: String) = "${projectPath(uid, name)}/chunks"
    fun allowlistPath(email: String) = "allowlist/${email.trim().lowercase()}"

    /**
     * Project names are the document ids. A slash would make a sub-path and "." / ".." are
     * reserved, so those few characters are replaced; everything else (spaces, unicode) is fine.
     */
    fun encodeDocId(name: String): String {
        val cleaned = name.replace('/', '∕')
        return if (cleaned == "." || cleaned == ".." || cleaned.isBlank()) "_$cleaned" else cleaned
    }
}

/**
 * Firebase Authentication (Identity Toolkit) and Firestore over their REST APIs, for targets
 * without a usable Firebase SDK: the browser (Kotlin/Wasm) and iOS. The session (refresh
 * token) is kept in the platform prefs so a reload stays signed in; ID tokens are refreshed
 * through the secure-token endpoint when they are within a minute of expiring.
 */
class RestCloudService(
    private val prefs: com.example.sitedraft.platform.KeyValuePrefs,
    private val now: () -> Long = { com.example.sitedraft.platform.nowMillis() },
) : CloudService {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @kotlinx.serialization.Serializable
    private data class Session(val uid: String, val email: String?, val displayName: String? = null, val idToken: String, val refreshToken: String, val expiresAt: Long)

    private val sessionKey = "cloud.session"
    private var session: Session? = prefs.getString(sessionKey)?.takeIf { it.isNotBlank() }
        ?.let { runCatching { json.decodeFromString(Session.serializer(), it) }.getOrNull() }
        set(value) {
            field = value
            prefs.putString(sessionKey, value?.let { json.encodeToString(Session.serializer(), it) } ?: "")
            _user.value = value?.let { CloudUser(it.uid, it.email, it.displayName) }
        }

    private val _user = kotlinx.coroutines.flow.MutableStateFlow(session?.let { CloudUser(it.uid, it.email, it.displayName) })
    override val user: kotlinx.coroutines.flow.StateFlow<CloudUser?> = _user
    override val isConfigured: Boolean get() = CloudConfig.isConfigured
    override val unavailableReason: String? get() = if (isConfigured) null else UnavailableCloud.unavailableReason

    private val authBase get() = "https://identitytoolkit.googleapis.com/v1/accounts"
    private val docsBase get() = "https://firestore.googleapis.com/v1/projects/${CloudConfig.projectId}/databases/(default)/documents"

    private suspend fun authCall(action: String, body: JsonObject, signingUp: Boolean): JsonObject {
        val response = httpRequest("POST", "$authBase:$action?key=${CloudConfig.apiKey}", mapOf("Content-Type" to "application/json"), body.toString())
        val parsed = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        if (!response.isSuccess) {
            val message = parsed?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            throw CloudException(friendlyAuthError(message ?: "HTTP ${response.status}", signingUp))
        }
        return parsed ?: JsonObject(emptyMap())
    }

    private fun sessionFrom(result: JsonObject): Session {
        val expiresIn = result["expiresIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        return Session(
            uid = result["localId"]?.jsonPrimitive?.contentOrNull ?: throw CloudException("The server sent no user id."),
            email = result["email"]?.jsonPrimitive?.contentOrNull,
            displayName = result["displayName"]?.jsonPrimitive?.contentOrNull,
            idToken = result["idToken"]?.jsonPrimitive?.contentOrNull ?: "",
            refreshToken = result["refreshToken"]?.jsonPrimitive?.contentOrNull ?: "",
            expiresAt = now() + expiresIn * 1000,
        )
    }

    override suspend fun signIn(email: String, password: String) {
        requireConfigured()
        val result = authCall("signInWithPassword", buildJsonObject {
            put("email", JsonPrimitive(email.trim())); put("password", JsonPrimitive(password)); put("returnSecureToken", JsonPrimitive(true))
        }, signingUp = false)
        session = sessionFrom(result)
    }

    override suspend fun signUp(email: String, password: String) {
        requireConfigured()
        val result = authCall("signUp", buildJsonObject {
            put("email", JsonPrimitive(email.trim())); put("password", JsonPrimitive(password)); put("returnSecureToken", JsonPrimitive(true))
        }, signingUp = true)
        session = sessionFrom(result)
    }

    override suspend fun sendPasswordReset(email: String) {
        requireConfigured()
        authCall("sendOobCode", buildJsonObject {
            put("requestType", JsonPrimitive("PASSWORD_RESET")); put("email", JsonPrimitive(email.trim()))
        }, signingUp = false)
    }

    override suspend fun signOut() {
        session = null
    }

    private fun requireConfigured() {
        if (!isConfigured) throw CloudException(UnavailableCloud.unavailableReason)
    }

    override suspend fun idToken(): String? {
        val s = session ?: return null
        if (s.idToken.isNotEmpty() && s.expiresAt - now() > 60_000) return s.idToken
        val response = httpRequest(
            "POST", "https://securetoken.googleapis.com/v1/token?key=${CloudConfig.apiKey}",
            mapOf("Content-Type" to "application/json"),
            buildJsonObject { put("grant_type", JsonPrimitive("refresh_token")); put("refresh_token", JsonPrimitive(s.refreshToken)) }.toString()
        )
        val parsed = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        if (!response.isSuccess) {
            val code = parsed?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
            // A revoked or expired refresh token means the session is over; anything else
            // (network) keeps it so the offline cache can still apply.
            if (code.contains("TOKEN_EXPIRED") || code.contains("USER_NOT_FOUND") || code.contains("INVALID_REFRESH_TOKEN") || code.contains("USER_DISABLED")) {
                session = null
                return null
            }
            throw CloudException("Could not refresh the sign-in: ${code.ifBlank { "HTTP ${response.status}" }}", offline = response.status == 0)
        }
        val refreshed = s.copy(
            idToken = parsed?.get("id_token")?.jsonPrimitive?.contentOrNull ?: s.idToken,
            refreshToken = parsed?.get("refresh_token")?.jsonPrimitive?.contentOrNull ?: s.refreshToken,
            expiresAt = now() + (parsed?.get("expires_in")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L) * 1000,
        )
        session = refreshed
        return refreshed.idToken
    }

    // ------------------------------------------------------------------ Firestore

    private suspend fun firestore(method: String, pathAndQuery: String, body: String? = null): HttpResponse {
        val token = idToken() ?: throw CloudException("Sign in first.")
        return httpRequest(method, "$docsBase/$pathAndQuery", mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token"), body)
    }

    private fun failure(response: HttpResponse, what: String): CloudException {
        val message = runCatching { json.parseToJsonElement(response.body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
        return CloudException("$what failed: ${message ?: "HTTP ${response.status}"}")
    }

    private suspend fun getDocument(path: String): JsonObject? {
        val response = firestore("GET", path)
        if (response.status == 404) return null
        if (!response.isSuccess) throw failure(response, "Reading $path")
        val doc = json.parseToJsonElement(response.body).jsonObject
        return FirestoreValues.decodeFields(doc["fields"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    /** PATCH with an update mask of exactly the given fields = a merge set. */
    private suspend fun mergeDocument(path: String, fields: JsonObject) {
        val mask = fields.keys.joinToString("&") { "updateMask.fieldPaths=$it" }
        val body = buildJsonObject { put("fields", FirestoreValues.encodeFields(fields)) }.toString()
        val response = firestore("PATCH", "$path?$mask", body)
        if (!response.isSuccess) throw failure(response, "Writing $path")
    }

    private suspend fun listCollection(path: String): List<Pair<String, JsonObject>> {
        val out = ArrayList<Pair<String, JsonObject>>()
        var pageToken: String? = null
        do {
            val query = "pageSize=300" + (pageToken?.let { "&pageToken=$it" } ?: "")
            val response = firestore("GET", "$path?$query")
            if (response.status == 404) return out
            if (!response.isSuccess) throw failure(response, "Listing $path")
            val page = json.parseToJsonElement(response.body).jsonObject
            (page["documents"] as? JsonArray)?.forEach { d ->
                val obj = d.jsonObject
                val id = FirestoreValues.documentId(obj["name"]?.jsonPrimitive?.content ?: return@forEach)
                out += id to FirestoreValues.decodeFields(obj["fields"] as? JsonObject ?: JsonObject(emptyMap()))
            }
            pageToken = page["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (pageToken != null)
        return out
    }

    /** Atomic multi-document write through `:commit`. Each write replaces its document. */
    private suspend fun commit(writes: List<Pair<String, JsonObject?>>) {
        val root = "projects/${CloudConfig.projectId}/databases/(default)/documents"
        val body = buildJsonObject {
            put("writes", JsonArray(writes.map { (path, fields) ->
                if (fields == null) buildJsonObject { put("delete", JsonPrimitive("$root/$path")) }
                else buildJsonObject {
                    put("update", buildJsonObject {
                        put("name", JsonPrimitive("$root/$path"))
                        put("fields", FirestoreValues.encodeFields(fields))
                    })
                }
            }))
        }.toString()
        val token = idToken() ?: throw CloudException("Sign in first.")
        val response = httpRequest("POST", "$docsBase:commit", mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token"), body)
        if (!response.isSuccess) throw failure(response, "Saving")
    }

    override val store: CloudStore = object : CloudStore {
        override suspend fun readUser(uid: String): UserDoc? = getDocument(CloudDocs.usersPath(uid))?.let { CloudDocs.userFrom(it) }

        override suspend fun touchUser(uid: String, email: String?, displayName: String?, device: String) =
            mergeDocument(CloudDocs.usersPath(uid), CloudDocs.touchFields(email, displayName, device, now()))

        override suspend fun isAllowlisted(email: String): Boolean {
            if (email.isBlank()) return false
            return try {
                getDocument(CloudDocs.allowlistPath(email)) != null
            } catch (e: CloudException) {
                // The rules deny reading someone else's entry with 403, which for our purposes
                // is "not listed"; a 5xx or network failure is still an error.
                if (e.message?.contains("PERMISSION_DENIED") == true || e.message?.contains("HTTP 403") == true) false else throw e
            }
        }

        override suspend fun listProjects(uid: String): List<ProjectMeta> =
            listCollection(CloudDocs.projectsPath(uid)).map { (id, fields) -> CloudDocs.projectMetaFrom(id, fields) }

        override suspend fun readProject(uid: String, name: String): ProjectDoc? {
            val fields = getDocument(CloudDocs.projectPath(uid, name)) ?: return null
            val meta = CloudDocs.projectMetaFrom(name, fields)
            val first = fields["json"]?.jsonPrimitive?.contentOrNull ?: ""
            val chunks = ArrayList<String>(meta.chunkCount).apply { add(first) }
            if (meta.chunkCount > 1) {
                val rest = listCollection(CloudDocs.chunksPath(uid, name))
                    .mapNotNull { (_, f) -> (f["index"]?.jsonPrimitive?.longOrNull?.toInt() ?: return@mapNotNull null) to (f["json"]?.jsonPrimitive?.contentOrNull ?: "") }
                    .sortedBy { it.first }
                for ((index, text) in rest) if (index >= 1) chunks += text
                if (chunks.size != meta.chunkCount) throw CloudException("Project '$name' is incomplete in the cloud (${chunks.size} of ${meta.chunkCount} parts).")
            }
            return ProjectDoc(meta.name, meta.updatedAt, meta.device, ProjectChunks.join(chunks))
        }

        override suspend fun writeProject(uid: String, name: String, json: String, updatedAt: Long, device: String) {
            val chunks = ProjectChunks.split(json)
            val previous = getDocument(CloudDocs.projectPath(uid, name))?.let { CloudDocs.projectMetaFrom(name, it) }
            val writes = ArrayList<Pair<String, JsonObject?>>()
            writes += CloudDocs.projectPath(uid, name) to CloudDocs.projectFields(name, chunks[0], chunks.size, updatedAt, device)
            for (i in 1 until chunks.size) writes += "${CloudDocs.chunksPath(uid, name)}/$i" to CloudDocs.chunkFields(i, chunks[i])
            // Chunks the new, smaller copy no longer needs.
            val oldCount = previous?.chunkCount ?: 1
            for (i in chunks.size until oldCount) writes += "${CloudDocs.chunksPath(uid, name)}/$i" to null
            commit(writes)
        }

        override suspend fun deleteProject(uid: String, name: String) {
            val previous = getDocument(CloudDocs.projectPath(uid, name))?.let { CloudDocs.projectMetaFrom(name, it) } ?: return
            val writes = ArrayList<Pair<String, JsonObject?>>()
            for (i in 1 until previous.chunkCount) writes += "${CloudDocs.chunksPath(uid, name)}/$i" to null
            writes += CloudDocs.projectPath(uid, name) to null
            commit(writes)
        }
    }
}

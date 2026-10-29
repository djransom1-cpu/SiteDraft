package com.example.sitedraft.cloud

import com.example.sitedraft.platform.nowMillis
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.AuthCredential
import dev.gitlive.firebase.auth.FirebaseUser
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.DocumentSnapshot
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Firebase through the GitLive Kotlin SDK: the real Firebase Android SDK on Android, GitLive's
 * firebase-java-sdk port on the desktop. The platform code starts Firebase (see
 * AndroidCloud.kt / DesktopCloud.kt) and passes [startupError] when that failed, so the
 * sign-in screen can say why instead of crashing on the first `Firebase.auth` call.
 */
open class GitLiveCloudService(private val startupError: String? = null) : CloudService {
    override val isConfigured: Boolean get() = CloudConfig.isConfigured && startupError == null
    override val unavailableReason: String?
        get() = when {
            !CloudConfig.isConfigured -> UnavailableCloud.unavailableReason
            startupError != null -> "Firebase could not start: $startupError"
            else -> null
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _user = MutableStateFlow<CloudUser?>(null)
    override val user: StateFlow<CloudUser?> = _user

    private fun FirebaseUser.toCloudUser() = CloudUser(uid, email, displayName)

    init {
        if (isConfigured) {
            runCatching { _user.value = Firebase.auth.currentUser?.toCloudUser() }
            scope.launch {
                runCatching {
                    Firebase.auth.authStateChanged.collect { _user.value = it?.toCloudUser() }
                }
            }
        }
    }

    private fun requireReady() {
        unavailableReason?.let { throw CloudException(it) }
    }

    private inline fun <T> auth(signingUp: Boolean, block: () -> T): T {
        requireReady()
        return try {
            block()
        } catch (e: CloudException) {
            throw e
        } catch (e: Exception) {
            throw CloudException(friendlyAuthError(e.message, signingUp), e)
        }
    }

    override suspend fun signIn(email: String, password: String) {
        auth(false) { Firebase.auth.signInWithEmailAndPassword(email.trim(), password) }
        _user.value = Firebase.auth.currentUser?.toCloudUser()
    }

    override suspend fun signUp(email: String, password: String) {
        auth(true) { Firebase.auth.createUserWithEmailAndPassword(email.trim(), password) }
        _user.value = Firebase.auth.currentUser?.toCloudUser()
    }

    override suspend fun sendPasswordReset(email: String) {
        auth(false) { Firebase.auth.sendPasswordResetEmail(email.trim()) }
    }

    /** Subclasses with a native Google flow hand the credential here. */
    protected suspend fun signInWith(credential: AuthCredential) {
        auth(false) { Firebase.auth.signInWithCredential(credential) }
        _user.value = Firebase.auth.currentUser?.toCloudUser()
    }

    override suspend fun signOut() {
        if (!isConfigured) return
        runCatching { Firebase.auth.signOut() }
        _user.value = null
    }

    override suspend fun idToken(): String? {
        if (!isConfigured) return null
        val current = Firebase.auth.currentUser ?: return null
        return try {
            current.getIdToken(false)
        } catch (e: Exception) {
            throw CloudException("Could not refresh the sign-in: ${e.message}", e, offline = true)
        }
    }

    // ------------------------------------------------------------------ Firestore

    @Serializable
    private data class TouchFields(val email: String? = null, val displayName: String? = null, val lastSeenAt: Long, val lastDevice: String)

    @Serializable
    private data class ProjectFields(val name: String, val updatedAt: Long, val device: String, val chunkCount: Int, val json: String)

    @Serializable
    private data class ChunkFields(val index: Int, val json: String)

    private inline fun <reified T> DocumentSnapshot.field(name: String): T? =
        if (contains(name)) runCatching { get<T?>(name) }.getOrNull() else null

    private fun DocumentSnapshot.toMeta(fallbackName: String) = ProjectMeta(
        name = field<String>("name") ?: fallbackName,
        updatedAt = field<Long>("updatedAt") ?: 0L,
        device = field<String>("device"),
        chunkCount = field<Long>("chunkCount")?.toInt() ?: 1,
    )

    private inline fun <T> store(what: String, block: () -> T): T {
        requireReady()
        return try {
            block()
        } catch (e: CloudException) {
            throw e
        } catch (e: Exception) {
            val offline = e.message?.contains("UNAVAILABLE", ignoreCase = true) == true || e.message?.contains("offline", ignoreCase = true) == true
            throw CloudException("$what failed: ${e.message}", e, offline)
        }
    }

    override val store: CloudStore = object : CloudStore {
        private val db get() = Firebase.firestore

        override suspend fun readUser(uid: String): UserDoc? = store("Reading the account") {
            val snap = db.collection("users").document(uid).get()
            if (!snap.exists) null else UserDoc(
                email = snap.field<String>("email"),
                displayName = snap.field<String>("displayName"),
                subscription = snap.field<SubscriptionDoc>("subscription"),
                stripeCustomerId = snap.field<String>("stripeCustomerId"),
                lastSeenAt = snap.field<Long>("lastSeenAt") ?: 0L,
                lastDevice = snap.field<String>("lastDevice"),
            )
        }

        override suspend fun touchUser(uid: String, email: String?, displayName: String?, device: String) = store("Saving the account") {
            db.collection("users").document(uid).set(TouchFields.serializer(), TouchFields(email, displayName, nowMillis(), device), merge = true)
        }

        override suspend fun isAllowlisted(email: String): Boolean {
            if (email.isBlank()) return false
            return try {
                store("Checking the allowlist") { db.collection("allowlist").document(email.trim().lowercase()).get().exists }
            } catch (e: CloudException) {
                if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) false else throw e
            }
        }

        override suspend fun listProjects(uid: String): List<ProjectMeta> = store("Listing projects") {
            db.collection("users").document(uid).collection("projects").get().documents.map { it.toMeta(it.id) }
        }

        override suspend fun readProject(uid: String, name: String): ProjectDoc? = store("Reading '$name'") {
            val ref = db.collection("users").document(uid).collection("projects").document(CloudDocs.encodeDocId(name))
            val snap = ref.get()
            if (!snap.exists) return@store null
            val meta = snap.toMeta(name)
            val chunks = ArrayList<String>(meta.chunkCount).apply { add(snap.field<String>("json") ?: "") }
            if (meta.chunkCount > 1) {
                val rest = ref.collection("chunks").get().documents
                    .mapNotNull { d -> (d.field<Long>("index")?.toInt() ?: return@mapNotNull null) to (d.field<String>("json") ?: "") }
                    .sortedBy { it.first }
                for ((index, text) in rest) if (index >= 1) chunks += text
                if (chunks.size != meta.chunkCount) throw CloudException("Project '$name' is incomplete in the cloud (${chunks.size} of ${meta.chunkCount} parts).")
            }
            ProjectDoc(meta.name, meta.updatedAt, meta.device, ProjectChunks.join(chunks))
        }

        override suspend fun writeProject(uid: String, name: String, json: String, updatedAt: Long, device: String) = store("Saving '$name'") {
            val ref = db.collection("users").document(uid).collection("projects").document(CloudDocs.encodeDocId(name))
            val chunks = ProjectChunks.split(json)
            val oldCount = ref.get().takeIf { it.exists }?.toMeta(name)?.chunkCount ?: 1
            val batch = db.batch()
            batch.set(ref, ProjectFields.serializer(), ProjectFields(name, updatedAt, device, chunks.size, chunks[0]))
            for (i in 1 until chunks.size) batch.set(ref.collection("chunks").document(i.toString()), ChunkFields.serializer(), ChunkFields(i, chunks[i]))
            for (i in chunks.size until oldCount) batch.delete(ref.collection("chunks").document(i.toString()))
            batch.commit()
        }

        override suspend fun deleteProject(uid: String, name: String) = store("Deleting '$name'") {
            val ref = db.collection("users").document(uid).collection("projects").document(CloudDocs.encodeDocId(name))
            val snap = ref.get()
            if (!snap.exists) return@store
            val batch = db.batch()
            for (i in 1 until snap.toMeta(name).chunkCount) batch.delete(ref.collection("chunks").document(i.toString()))
            batch.delete(ref)
            batch.commit()
        }
    }
}

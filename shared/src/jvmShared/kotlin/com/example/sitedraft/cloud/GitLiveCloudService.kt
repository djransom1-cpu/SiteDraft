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

    private fun FirebaseUser.toCloudUser() = CloudUser(uid, email, displayName, isEmailVerified)

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
        // Shared projects open only for verified emails; start that right away.
        runCatching { Firebase.auth.currentUser?.sendEmailVerification() }
    }

    override suspend fun sendPasswordReset(email: String) {
        auth(false) { Firebase.auth.sendPasswordResetEmail(email.trim()) }
    }

    /** Subclasses with a native Google flow hand the credential here. */
    protected suspend fun signInWith(credential: AuthCredential) {
        auth(false) { Firebase.auth.signInWithCredential(credential) }
        _user.value = Firebase.auth.currentUser?.toCloudUser()
    }

    override suspend fun sendEmailVerification() {
        auth(false) { Firebase.auth.currentUser?.sendEmailVerification() ?: throw CloudException("Sign in first.") }
    }

    override suspend fun refreshUser() {
        if (!isConfigured) return
        val current = Firebase.auth.currentUser ?: return
        runCatching { current.reload() }
        runCatching { current.getIdToken(true) }
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

    @Serializable
    private data class RedeemFields(val accessCode: AccessCodeRedemption)

    @Serializable
    private data class AllowFields(val note: String, val addedAt: Long)

    @Serializable
    private data class CodeFields(val active: Boolean, val note: String? = null)

    @Serializable
    private data class SharingFields(val ownerUid: String, val ownerEmail: String? = null, val memberEmails: List<String>, val editorEmails: List<String>)

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
                accessCode = snap.field<AccessCodeRedemption>("accessCode"),
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
            // Merge: the sharing fields on the same document must survive every save.
            batch.set(ref, ProjectFields.serializer(), ProjectFields(name, updatedAt, device, chunks.size, chunks[0]), merge = true)
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

        override suspend fun readSharing(ownerUid: String, name: String): ProjectSharing? = store("Reading the sharing of '$name'") {
            val snap = db.collection("users").document(ownerUid).collection("projects").document(CloudDocs.encodeDocId(name)).get()
            if (!snap.exists) null else ProjectSharing(
                ownerUid = snap.field<String>("ownerUid") ?: ownerUid,
                ownerEmail = snap.field<String>("ownerEmail"),
                memberEmails = snap.field<List<String>>("memberEmails") ?: emptyList(),
                editorEmails = snap.field<List<String>>("editorEmails") ?: emptyList(),
            )
        }

        override suspend fun writeSharing(ownerUid: String, name: String, sharing: ProjectSharing) = store("Sharing '$name'") {
            db.collection("users").document(ownerUid).collection("projects").document(CloudDocs.encodeDocId(name))
                .set(SharingFields.serializer(), SharingFields(sharing.ownerUid, sharing.ownerEmail, sharing.memberEmails, sharing.editorEmails), merge = true)
        }

        // ---- admin ----

        private fun DocumentSnapshot.toUserDoc() = UserDoc(
            email = field<String>("email"),
            displayName = field<String>("displayName"),
            subscription = field<SubscriptionDoc>("subscription"),
            stripeCustomerId = field<String>("stripeCustomerId"),
            lastSeenAt = field<Long>("lastSeenAt") ?: 0L,
            lastDevice = field<String>("lastDevice"),
            accessCode = field<AccessCodeRedemption>("accessCode"),
        )

        override suspend fun isAdmin(email: String): Boolean {
            if (email.isBlank()) return false
            return try {
                store("Checking admin") { db.collection("admins").document(Sharing.normalize(email)).get().exists }
            } catch (e: CloudException) {
                if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) false else throw e
            }
        }

        override suspend fun listAccounts(): List<AccountSummary> = store("Listing accounts") {
            db.collection("users").get().documents.map { AccountSummary(it.id, it.toUserDoc()) }
        }

        override suspend fun listAllowlist(): List<String> = store("Listing the allowlist") {
            db.collection("allowlist").get().documents.map { it.id }.sorted()
        }

        override suspend fun setAllowlisted(email: String, allowed: Boolean) = store("Updating the allowlist") {
            val ref = db.collection("allowlist").document(Sharing.normalize(email))
            if (allowed) ref.set(AllowFields.serializer(), AllowFields("added in SiteDraft", nowMillis())) else ref.delete()
        }

        override suspend fun listAccessCodes(): List<AccessCodeInfo> = store("Listing access codes") {
            db.collection("accessCodes").get().documents.map { d ->
                AccessCodeInfo(d.id, d.field<Boolean>("active") ?: true, d.field<String>("note"), d.contains("expires"))
            }.sortedBy { it.code }
        }

        override suspend fun saveAccessCode(code: String, active: Boolean, note: String?) = store("Saving the code") {
            db.collection("accessCodes").document(code).set(CodeFields.serializer(), CodeFields(active, note), merge = true)
        }

        override suspend fun redeemAccessCode(uid: String, code: String) {
            try {
                store("Using the code") {
                    db.collection("users").document(uid).set(RedeemFields.serializer(), RedeemFields(AccessCodeRedemption(code, nowMillis())), merge = true)
                }
            } catch (e: CloudException) {
                if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) throw CloudException("That code is not valid, or it has expired.", e)
                throw e
            }
        }

        override suspend fun isAccessCodeUsable(code: String): Boolean = try {
            store("Checking the code") { db.collection("accessCodes").document(code).get().exists }
        } catch (e: CloudException) {
            // The rules refuse to show a code that was switched off or has expired.
            if (e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true) false else throw e
        }

        override suspend fun listSharedWithMe(email: String): List<SharedProjectMeta> = store("Listing shared projects") {
            val me = Sharing.normalize(email)
            db.collectionGroup("projects").where { "memberEmails" contains me }.get().documents.mapNotNull { snap ->
                val parts = snap.reference.path.split('/')
                if (parts.size != 4 || parts[0] != "users" || parts[2] != "projects") return@mapNotNull null
                val editors = snap.field<List<String>>("editorEmails") ?: emptyList()
                SharedProjectMeta(
                    ownerUid = parts[1],
                    ownerEmail = snap.field<String>("ownerEmail"),
                    name = snap.field<String>("name") ?: snap.id,
                    updatedAt = snap.field<Long>("updatedAt") ?: 0L,
                    canEdit = me in editors.map(Sharing::normalize),
                )
            }
        }
    }
}

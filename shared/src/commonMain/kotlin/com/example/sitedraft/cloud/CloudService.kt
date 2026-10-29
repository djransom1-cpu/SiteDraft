package com.example.sitedraft.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** The signed-in Firebase user, as much of it as the app needs. */
data class CloudUser(val uid: String, val email: String?, val displayName: String? = null, val emailVerified: Boolean = false)

/** Anything that went wrong talking to Firebase; [message] is already fit to show the user. */
class CloudException(message: String, cause: Throwable? = null, val offline: Boolean = false) : Exception(message, cause)

/** `users/{uid}.subscription`, written only by the Stripe webhook Cloud Function. */
@Serializable
data class SubscriptionDoc(
    val status: String = "",
    /** Epoch milliseconds when the paid period ends. */
    val currentPeriodEnd: Long = 0L,
    val priceId: String? = null,
    val updatedAt: Long = 0L,
)

/** `users/{uid}`. The client may write everything except [subscription] and [stripeCustomerId]. */
@Serializable
data class UserDoc(
    val email: String? = null,
    val displayName: String? = null,
    val subscription: SubscriptionDoc? = null,
    val stripeCustomerId: String? = null,
    val lastSeenAt: Long = 0L,
    val lastDevice: String? = null,
)

/** One entry of `users/{uid}/projects` without its JSON (for listing). */
data class ProjectMeta(val name: String, val updatedAt: Long, val device: String?, val chunkCount: Int)

/** A whole cloud project. */
data class ProjectDoc(val name: String, val updatedAt: Long, val device: String?, val json: String)

/**
 * Who a project is shared with, stored on the owner's project document. Emails are lowercase;
 * [editorEmails] is always a subset of [memberEmails] (members not in it are view-only).
 */
@Serializable
data class ProjectSharing(
    val ownerUid: String,
    val ownerEmail: String? = null,
    val memberEmails: List<String> = emptyList(),
    val editorEmails: List<String> = emptyList(),
)

/** A project someone else shared with the signed-in user, as the "shared with me" query lists it. */
data class SharedProjectMeta(
    val ownerUid: String,
    val ownerEmail: String?,
    val name: String,
    val updatedAt: Long,
    val canEdit: Boolean,
)

/** Firestore, as the few typed operations SiteDraft needs. */
interface CloudStore {
    suspend fun readUser(uid: String): UserDoc?
    /** Creates or refreshes the user's own profile fields (never the subscription). */
    suspend fun touchUser(uid: String, email: String?, displayName: String?, device: String)
    /** True when `allowlist/{email}` exists (beta testers who may use the app without paying). */
    suspend fun isAllowlisted(email: String): Boolean
    suspend fun listProjects(uid: String): List<ProjectMeta>
    suspend fun readProject(uid: String, name: String): ProjectDoc?
    suspend fun writeProject(uid: String, name: String, json: String, updatedAt: Long, device: String)
    suspend fun deleteProject(uid: String, name: String)

    /** The sharing fields of `users/{ownerUid}/projects/{name}`, or null when there is no such project. */
    suspend fun readSharing(ownerUid: String, name: String): ProjectSharing?
    /** Merges the sharing fields into the owner's project document (only the owner may). */
    suspend fun writeSharing(ownerUid: String, name: String, sharing: ProjectSharing)
    /** Every project, in any user's account, whose memberEmails contains [email]. */
    suspend fun listSharedWithMe(email: String): List<SharedProjectMeta>
}

/** Authentication plus the store. One per platform; see [Platform.cloud]. */
interface CloudService {
    /** False when firebase.properties was missing at build time or Firebase failed to start. */
    val isConfigured: Boolean
    /** Why [isConfigured] is false, for the sign-in screen. */
    val unavailableReason: String? get() = null
    val user: StateFlow<CloudUser?>
    val supportsGoogleSignIn: Boolean get() = false
    val store: CloudStore

    suspend fun signIn(email: String, password: String)
    suspend fun signUp(email: String, password: String)
    suspend fun sendPasswordReset(email: String)
    suspend fun signInWithGoogle() { throw CloudException("Google sign-in is not available on this device") }
    suspend fun signOut()
    /** Emails a verification link; shared projects open only for verified emails. */
    suspend fun sendEmailVerification() {}
    /** Reloads the user (and a fresh token) so a just-verified email counts. */
    suspend fun refreshUser() {}
    /** A fresh Firebase ID token for calling Cloud Functions, or null when signed out. */
    suspend fun idToken(): String?
}

/** No cloud at all: unit tests, and hosts that have not wired one up yet. */
object UnavailableCloud : CloudService {
    override val isConfigured = false
    override val unavailableReason = "This build has no Firebase configuration (firebase.properties)."
    override val user: StateFlow<CloudUser?> = MutableStateFlow(null)
    override val store: CloudStore = object : CloudStore {
        override suspend fun readUser(uid: String): UserDoc? = null
        override suspend fun touchUser(uid: String, email: String?, displayName: String?, device: String) {}
        override suspend fun isAllowlisted(email: String) = false
        override suspend fun listProjects(uid: String) = emptyList<ProjectMeta>()
        override suspend fun readProject(uid: String, name: String): ProjectDoc? = null
        override suspend fun writeProject(uid: String, name: String, json: String, updatedAt: Long, device: String) {}
        override suspend fun deleteProject(uid: String, name: String) {}
        override suspend fun readSharing(ownerUid: String, name: String): ProjectSharing? = null
        override suspend fun writeSharing(ownerUid: String, name: String, sharing: ProjectSharing) {}
        override suspend fun listSharedWithMe(email: String) = emptyList<SharedProjectMeta>()
    }
    override suspend fun signIn(email: String, password: String) = throw CloudException(unavailableReason)
    override suspend fun signUp(email: String, password: String) = throw CloudException(unavailableReason)
    override suspend fun sendPasswordReset(email: String) = throw CloudException(unavailableReason)
    override suspend fun signOut() {}
    override suspend fun idToken(): String? = null
}

/**
 * Turns the raw Firebase error text into something a user can act on. Both the GitLive SDK and
 * the REST API surface the same upper-case codes somewhere in the message.
 */
fun friendlyAuthError(raw: String?, signingUp: Boolean): String {
    val text = raw.orEmpty()
    return when {
        text.contains("INVALID_LOGIN_CREDENTIALS") || text.contains("INVALID_PASSWORD") || text.contains("EMAIL_NOT_FOUND") ||
            text.contains("wrong-password", ignoreCase = true) || text.contains("user-not-found", ignoreCase = true) ||
            text.contains("incorrect, malformed", ignoreCase = true) ->
            "Email or password is incorrect."
        text.contains("EMAIL_EXISTS") || text.contains("email-already-in-use", ignoreCase = true) ->
            "An account with that email already exists. Sign in instead."
        text.contains("WEAK_PASSWORD") || text.contains("weak-password", ignoreCase = true) ->
            "Password must be at least 6 characters."
        text.contains("INVALID_EMAIL") || text.contains("invalid-email", ignoreCase = true) ->
            "That does not look like an email address."
        text.contains("TOO_MANY_ATTEMPTS") || text.contains("too-many-requests", ignoreCase = true) ->
            "Too many attempts. Wait a minute and try again."
        text.contains("USER_DISABLED") || text.contains("user-disabled", ignoreCase = true) ->
            "This account has been disabled."
        text.contains("OPERATION_NOT_ALLOWED") || text.contains("operation-not-allowed", ignoreCase = true) ->
            "This sign-in method is not enabled in the Firebase console."
        text.contains("network", ignoreCase = true) || text.contains("Unable to resolve host", ignoreCase = true) ||
            text.contains("Failed to fetch", ignoreCase = true) ->
            "No connection. Check your network and try again."
        text.isBlank() -> if (signingUp) "Could not create the account." else "Could not sign in."
        else -> text.removePrefix("Exception: ").take(200)
    }
}

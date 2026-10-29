package com.example.sitedraft.ui.cloud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.sitedraft.cloud.AccessCache
import com.example.sitedraft.cloud.AccessDecision
import com.example.sitedraft.cloud.AccessCodes
import com.example.sitedraft.cloud.AccessGate
import com.example.sitedraft.cloud.CloudException
import com.example.sitedraft.cloud.CloudFunctions
import com.example.sitedraft.cloud.CloudService
import com.example.sitedraft.cloud.CloudUser
import com.example.sitedraft.cloud.ProjectSync
import com.example.sitedraft.cloud.UserDoc
import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.nowMillis
import com.example.sitedraft.ui.canvas.DrawingManager

/**
 * Everything the screens need to know about the signed-in user: the gate's decision, the
 * subscription on file, and the actions (refresh, open Stripe, sign out). One per app; the
 * root composable owns it and the Settings panel reaches it through [LocalCloudSession].
 */
class CloudSession(
    private val platform: Platform,
    val cloud: CloudService,
    private val manager: DrawingManager,
) {
    private val cache = AccessCache(platform.prefs)

    /** Null while the first check is running (or after it failed with no cached answer). */
    var access by mutableStateOf<AccessDecision?>(null)
        private set
    var userDoc by mutableStateOf<UserDoc?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** The reason the last check could not decide, for the user. */
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** The owner (an `admins/{email}` document): sees the Admin screen in Settings. */
    var isAdmin by mutableStateOf(false)
        private set

    /** The sync engine while the user is past the gate; the Projects panel reads shares from it. */
    var sync by mutableStateOf<ProjectSync?>(null)
        private set
    private var checkedUid: String? = null

    val user: CloudUser? get() = cloud.user.value

    /**
     * Reads `users/{uid}` and the allowlist and decides. Firestore unreachable -> the cached
     * decision from the last 7 days, if it allowed; otherwise [error] explains and the gate
     * stays shut.
     */
    suspend fun check(user: CloudUser) {
        checking = true
        error = null
        try {
            try {
                cloud.store.touchUser(user.uid, user.email, user.displayName, platform.name)
            } catch (e: Exception) {
                platform.log("SiteDraftCloud", "touchUser: ${e.message}")
            }
            // A just-verified email only counts once the token says so (admin and sharing
            // rules require email_verified), so refresh while it still reads unverified.
            if (!user.emailVerified) runCatching { cloud.refreshUser() }
            val email = cloud.user.value?.email ?: user.email
            val doc = cloud.store.readUser(user.uid)
            isAdmin = email?.let { cloud.store.isAdmin(it) } ?: false
            val listed = email?.let { cloud.store.isAllowlisted(it) } ?: false
            // Checked every time, so switching a code off in the console shuts it again.
            val codeUsable = doc?.accessCode?.code?.takeIf { it.isNotBlank() }?.let { cloud.store.isAccessCodeUsable(it) } ?: false
            val decision = AccessGate.decide(doc, listed, nowMillis(), codeUsable, admin = isAdmin)
            cache.save(user.uid, decision)
            userDoc = doc
            access = decision
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            val cached = AccessGate.decideOffline(cache.load(user.uid), nowMillis())
            if (cached != null) {
                access = cached
            } else {
                access = null
                error = (e as? CloudException)?.message ?: "Could not check the subscription: ${e.message}"
            }
        } finally {
            checking = false
        }
        onAccessChanged(user)
    }

    private fun onAccessChanged(user: CloudUser) {
        if (access?.allowed == true) {
            if (sync == null || checkedUid != user.uid) {
                sync?.dispose()
                sync = ProjectSync(platform, cloud, manager).also { manager.sync = it }
                checkedUid = user.uid
            }
            sync?.syncAll()
        } else {
            detachSync()
        }
    }

    private fun detachSync() {
        manager.sync = null
        sync?.dispose()
        sync = null
        checkedUid = null
    }

    suspend fun refresh() {
        val user = user ?: return
        check(user)
    }

    /**
     * Redeems an access code typed on the Subscribe screen, then checks again. Returns an error
     * for the user, or null when the code was accepted (the gate then opens by itself).
     */
    suspend fun redeemCode(input: String): String? {
        val user = user ?: return "Sign in first."
        val code = AccessCodes.normalize(input)
        if (!AccessCodes.isWellFormed(code)) return "Codes are 3 to 32 letters, numbers or dashes."
        busy = true
        return try {
            cloud.store.redeemAccessCode(user.uid, code)
            check(user)
            if (access?.allowed == true) null else "That code is not valid, or it has expired."
        } catch (e: Exception) {
            (e as? CloudException)?.message ?: "Could not use the code: ${e.message}"
        } finally {
            busy = false
        }
    }

    /** Sends the verification email again. Returns a message for the user. */
    suspend fun resendVerification(): String = try {
        cloud.sendEmailVerification()
        "Verification email sent to ${user?.email ?: "your address"}."
    } catch (e: Exception) {
        (e as? CloudException)?.message ?: "Could not send the email: ${e.message}"
    }

    /** After the user clicked the link: reload, and fetch shares if the email now counts. */
    suspend fun recheckVerification(): String? {
        runCatching { cloud.refreshUser() }
        return if (cloud.user.value?.emailVerified == true) {
            sync?.syncAll()
            null
        } else "Not verified yet. Open the link in the email, then try again."
    }

    /** Opens Stripe Checkout in the browser. Returns a message for the user, or null when it opened. */
    suspend fun startCheckout(): String? = openLink { CloudFunctions.createCheckoutUrl(cloud, platform.checkoutReturnUrl) }

    /** Opens the Stripe customer portal (change card, cancel). */
    suspend fun openBillingPortal(): String? = openLink { CloudFunctions.createPortalUrl(cloud, platform.checkoutReturnUrl) }

    private suspend fun openLink(create: suspend () -> String): String? {
        busy = true
        return try {
            val url = create()
            if (platform.openUrl(url)) null else "Open this link in a browser: $url"
        } catch (e: Exception) {
            (e as? CloudException)?.message ?: "Could not open the payment page: ${e.message}"
        } finally {
            busy = false
        }
    }

    suspend fun signOut() {
        detachSync()
        isAdmin = false
        access = null
        userDoc = null
        error = null
        runCatching { cloud.signOut() }
    }

    /** The user signed out or changed underneath us; forget the previous decision. */
    fun reset() {
        detachSync()
        access = null
        userDoc = null
        error = null
    }
}

val LocalCloudSession = staticCompositionLocalOf<CloudSession?> { null }

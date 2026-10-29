package com.example.sitedraft.cloud

import com.example.sitedraft.platform.KeyValuePrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The gate's answer. [allowed] decides whether MainScreen is shown; [reason] is a short code
 * for the UI and the logs ("active", "trialing", "allowlist", "none", "offline-cache").
 */
@Serializable
data class AccessDecision(val allowed: Boolean, val reason: String, val checkedAt: Long)

/**
 * Who may use the app, as a pure function of what Firestore says. Kept free of I/O so the
 * rules can be unit-tested (AccessGateTest).
 */
object AccessGate {
    /** How long a previous "allowed" keeps working with no connection to Firestore. */
    const val OFFLINE_GRACE_MS: Long = 7L * 24 * 60 * 60 * 1000

    private val paidStatuses = setOf("active", "trialing")

    /**
     * A subscription counts while Stripe reports it active or trialing. A cancelled one is
     * still good until the period that was paid for ends (Stripe keeps the status "active"
     * with cancel_at_period_end, but a hard cancel reports "canceled" with a future period end).
     */
    fun isSubscriptionGood(subscription: SubscriptionDoc?, now: Long): Boolean {
        if (subscription == null) return false
        if (subscription.status in paidStatuses) return true
        return subscription.status == "canceled" && subscription.currentPeriodEnd > now
    }

    /** The decision when Firestore answered. */
    fun decide(user: UserDoc?, allowlisted: Boolean, now: Long): AccessDecision = when {
        isSubscriptionGood(user?.subscription, now) -> AccessDecision(true, user!!.subscription!!.status, now)
        allowlisted -> AccessDecision(true, "allowlist", now)
        else -> AccessDecision(false, "none", now)
    }

    /**
     * The decision when Firestore could not be reached: the last good answer, if it is recent
     * enough. A cached denial is never reused (the user may have just paid), and nothing older
     * than [OFFLINE_GRACE_MS] counts. Null means "cannot tell".
     */
    fun decideOffline(cached: AccessDecision?, now: Long): AccessDecision? {
        if (cached == null || !cached.allowed) return null
        if (now - cached.checkedAt !in 0..OFFLINE_GRACE_MS) return null
        return cached.copy(reason = "offline-cache")
    }
}

/** The last decision per user, in the platform's key/value store, for offline use. */
class AccessCache(private val prefs: KeyValuePrefs) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(uid: String) = "cloud.access.$uid"

    fun load(uid: String): AccessDecision? =
        prefs.getString(key(uid))?.let { runCatching { json.decodeFromString(AccessDecision.serializer(), it) }.getOrNull() }

    fun save(uid: String, decision: AccessDecision) = prefs.putString(key(uid), json.encodeToString(AccessDecision.serializer(), decision))

    fun clear(uid: String) = prefs.putString(key(uid), "")
}

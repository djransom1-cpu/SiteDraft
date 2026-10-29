package com.example.sitedraft

import com.example.sitedraft.cloud.AccessCache
import com.example.sitedraft.cloud.AccessDecision
import com.example.sitedraft.cloud.AccessGate
import com.example.sitedraft.cloud.SubscriptionDoc
import com.example.sitedraft.cloud.UserDoc
import com.example.sitedraft.platform.InMemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessGateTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun user(status: String, periodEnd: Long = now + 10 * day) =
        UserDoc(email = "a@b.c", subscription = SubscriptionDoc(status = status, currentPeriodEnd = periodEnd))

    @Test
    fun activeAndTrialingAreAllowed() {
        assertTrue(AccessGate.decide(user("active"), allowlisted = false, now = now).allowed)
        assertEquals("trialing", AccessGate.decide(user("trialing"), false, now).reason)
    }

    @Test
    fun noSubscriptionIsDenied() {
        val decision = AccessGate.decide(UserDoc(email = "a@b.c"), allowlisted = false, now = now)
        assertFalse(decision.allowed)
        assertEquals("none", decision.reason)
        assertFalse(AccessGate.decide(null, false, now).allowed)
    }

    @Test
    fun pastDueAndUnpaidAreDenied() {
        assertFalse(AccessGate.decide(user("past_due"), false, now).allowed)
        assertFalse(AccessGate.decide(user("unpaid"), false, now).allowed)
        assertFalse(AccessGate.decide(user("incomplete"), false, now).allowed)
    }

    @Test
    fun canceledIsGoodUntilThePaidPeriodEnds() {
        assertTrue(AccessGate.decide(user("canceled", periodEnd = now + day), false, now).allowed)
        assertFalse(AccessGate.decide(user("canceled", periodEnd = now - 1), false, now).allowed)
    }

    @Test
    fun allowlistOpensTheGateWithoutASubscription() {
        val decision = AccessGate.decide(UserDoc(email = "beta@b.c"), allowlisted = true, now = now)
        assertTrue(decision.allowed)
        assertEquals("allowlist", decision.reason)
        // A paid subscription takes precedence in the reason so the Settings panel shows it.
        assertEquals("active", AccessGate.decide(user("active"), allowlisted = true, now = now).reason)
    }

    @Test
    fun offlineReusesARecentAllowedDecisionOnly() {
        val allowed = AccessDecision(true, "active", checkedAt = now - 6 * day)
        assertEquals("offline-cache", AccessGate.decideOffline(allowed, now)?.reason)
        assertTrue(AccessGate.decideOffline(allowed, now)!!.allowed)

        val tooOld = AccessDecision(true, "active", checkedAt = now - 8 * day)
        assertNull(AccessGate.decideOffline(tooOld, now))

        val denied = AccessDecision(false, "none", checkedAt = now - day)
        assertNull(AccessGate.decideOffline(denied, now))

        val fromTheFuture = AccessDecision(true, "active", checkedAt = now + day)
        assertNull(AccessGate.decideOffline(fromTheFuture, now))
        assertNull(AccessGate.decideOffline(null, now))
    }

    @Test
    fun exactlySevenDaysStillCounts() {
        val edge = AccessDecision(true, "allowlist", checkedAt = now - AccessGate.OFFLINE_GRACE_MS)
        assertTrue(AccessGate.decideOffline(edge, now)!!.allowed)
        assertNull(AccessGate.decideOffline(edge, now + 1))
    }

    @Test
    fun cacheRoundTripsPerUser() {
        val cache = AccessCache(InMemoryPrefs())
        val decision = AccessDecision(true, "active", now)
        cache.save("u1", decision)
        assertEquals(decision, cache.load("u1"))
        assertNull(cache.load("u2"))
        cache.clear("u1")
        assertNull(cache.load("u1"))
    }
}

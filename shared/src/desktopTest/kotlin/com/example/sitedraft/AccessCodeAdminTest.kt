package com.example.sitedraft

import com.example.sitedraft.cloud.AccessCodeRedemption
import com.example.sitedraft.cloud.AccessCodes
import com.example.sitedraft.cloud.AccessGate
import com.example.sitedraft.cloud.SubscriptionDoc
import com.example.sitedraft.cloud.UserDoc
import com.example.sitedraft.ui.cloud.ago
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessCodeAdminTest {
    private val now = 1_800_000_000_000L
    private val redeemed = UserDoc(email = "t@x.com", accessCode = AccessCodeRedemption("BETA2026", now - 1000))

    @Test
    fun aUsableRedeemedCodeOpensTheGate() {
        val d = AccessGate.decide(redeemed, allowlisted = false, now = now, codeUsable = true)
        assertTrue(d.allowed)
        assertEquals("code", d.reason)
    }

    @Test
    fun aSwitchedOffOrExpiredCodeDoesNot() {
        // The rules refuse to show such a code, so the app sees codeUsable = false.
        assertFalse(AccessGate.decide(redeemed, allowlisted = false, now = now, codeUsable = false).allowed)
    }

    @Test
    fun usableWithoutARedemptionOnTheAccountDoesNothing() {
        assertFalse(AccessGate.decide(UserDoc(email = "t@x.com"), allowlisted = false, now = now, codeUsable = true).allowed)
    }

    @Test
    fun anAdminAlwaysGetsInAndSaysSo() {
        assertEquals("admin", AccessGate.decide(null, allowlisted = false, now = now, admin = true).reason)
        val paid = UserDoc(subscription = SubscriptionDoc("active", now + 1))
        assertEquals("admin", AccessGate.decide(paid, allowlisted = true, now = now, codeUsable = true, admin = true).reason)
    }

    @Test
    fun subscriptionAndAllowlistComeBeforeACode() {
        val both = redeemed.copy(subscription = SubscriptionDoc("trialing", now + 1))
        assertEquals("trialing", AccessGate.decide(both, allowlisted = true, now = now, codeUsable = true).reason)
        assertEquals("allowlist", AccessGate.decide(redeemed, allowlisted = true, now = now, codeUsable = true).reason)
    }

    @Test
    fun codesAreNormalisedTheWayTheRulesExpect() {
        assertEquals("BETA2026", AccessCodes.normalize("  beta2026 "))
        assertEquals("SPRING-CREW", AccessCodes.normalize("spring crew"))
        assertTrue(AccessCodes.isWellFormed("BETA-2026"))
        assertFalse(AccessCodes.isWellFormed("AB"))
        assertFalse(AccessCodes.isWellFormed("BETA/2026"))
        assertFalse(AccessCodes.isWellFormed("A".repeat(33)))
    }

    @Test
    fun lastSeenReadsNaturally() {
        assertEquals("never", ago(0L, now))
        assertEquals("just now", ago(now - 20_000, now))
        assertEquals("5 min ago", ago(now - 5 * 60_000, now))
        assertEquals("3 h ago", ago(now - 3 * 3_600_000, now))
        assertEquals("4 days ago", ago(now - 4 * 86_400_000L, now))
    }
}

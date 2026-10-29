package com.example.sitedraft

import com.example.sitedraft.cloud.emailVerifiedOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * GitLive's desktop SDK leaves FirebaseUser.isEmailVerified as a TODO(); the app then reads
 * the ID token's email_verified claim instead (this crashed the 1.0.6 desktop app at sign-in).
 */
class EmailVerifiedFallbackTest {
    private fun token(payload: String) = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"

    @Test
    fun theSdkAnswerIsUsedWhenItExists() = runBlocking {
        assertEquals(true, emailVerifiedOf({ true }) { error("token not needed") })
        assertEquals(false, emailVerifiedOf({ false }) { token("""{"email_verified":true}""") })
    }

    @Test
    fun aStubFallsBackToTheTokenClaim() = runBlocking {
        assertEquals(true, emailVerifiedOf({ TODO("desktop stub") }) { token("""{"email_verified":true}""") })
        assertEquals(false, emailVerifiedOf({ TODO("desktop stub") }) { token("""{"email_verified":false}""") })
    }

    @Test
    fun noTokenMeansNotVerified() = runBlocking {
        assertEquals(false, emailVerifiedOf({ TODO("desktop stub") }) { null })
    }
}

package com.example.sitedraft.cloud

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.apps
import dev.gitlive.firebase.auth.GoogleAuthProvider
import dev.gitlive.firebase.initialize

/**
 * The Activity that is currently on screen; Credential Manager needs one to show the Google
 * account chooser. MainActivity sets and clears it.
 */
object AndroidActivityHolder {
    @Volatile
    var current: Activity? = null
}

/**
 * Firebase on Android. With app/google-services.json the google-services Gradle plugin makes
 * the SDK start itself; without it (a developer build with only firebase.properties) the
 * project values are applied here. Google sign-in goes through Credential Manager and needs
 * `googleWebClientId` plus the app's SHA-1 registered in the Firebase console.
 */
class AndroidCloudService(context: Context) : GitLiveCloudService(startFirebase(context)) {
    private val app = context.applicationContext

    override val supportsGoogleSignIn: Boolean get() = isConfigured && CloudConfig.googleWebClientId.isNotBlank()

    override suspend fun signInWithGoogle() {
        if (!supportsGoogleSignIn) throw CloudException("Google sign-in needs googleWebClientId in firebase.properties.")
        val activity = AndroidActivityHolder.current ?: throw CloudException("No screen to show the Google account chooser on.")
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(CloudConfig.googleWebClientId)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(app).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            throw CloudException("Google sign-in was cancelled.", e)
        } catch (e: NoCredentialException) {
            throw CloudException("No Google account on this device. Add one in Android settings, or use email and password.", e)
        } catch (e: Exception) {
            throw CloudException("Google sign-in failed: ${e.message}", e)
        }
        val idToken = (credential as? CustomCredential)
            ?.takeIf { it.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL }
            ?.let { GoogleIdTokenCredential.createFrom(it.data).idToken }
            ?: throw CloudException("Google did not return a sign-in token.")
        signInWith(GoogleAuthProvider.credential(idToken, null))
    }

    private companion object {
        /** Returns an error message when Firebase could not start, null when it is ready. */
        fun startFirebase(context: Context): String? {
            if (!CloudConfig.isConfigured) return null
            return try {
                if (Firebase.apps(context).isEmpty()) {
                    Firebase.initialize(
                        context,
                        FirebaseOptions(
                            applicationId = CloudConfig.webAppId,
                            apiKey = CloudConfig.apiKey,
                            projectId = CloudConfig.projectId,
                            storageBucket = CloudConfig.storageBucket,
                        )
                    )
                }
                null
            } catch (e: Throwable) {
                if (e.message?.contains("already exists") == true) null else e.message ?: e.toString()
            }
        }
    }
}

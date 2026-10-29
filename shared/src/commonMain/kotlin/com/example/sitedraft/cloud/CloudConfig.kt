package com.example.sitedraft.cloud

/**
 * Firebase project values, compiled in from firebase.properties by shared/build.gradle.kts
 * (see docs/CLOUD_SETUP.md). The same values serve every target: Android (when there is no
 * google-services.json), the desktop JVM, the browser and iOS.
 */
object CloudConfig {
    val projectId: String get() = GeneratedCloudConfig.projectId
    val apiKey: String get() = GeneratedCloudConfig.apiKey
    val authDomain: String get() = GeneratedCloudConfig.authDomain.ifBlank { "$projectId.firebaseapp.com" }
    val storageBucket: String get() = GeneratedCloudConfig.storageBucket.ifBlank { "$projectId.firebasestorage.app" }
    val webAppId: String get() = GeneratedCloudConfig.webAppId
    /** OAuth web client id used by Google sign-in on Android (Credential Manager). */
    val googleWebClientId: String get() = GeneratedCloudConfig.googleWebClientId
    val functionsRegion: String get() = GeneratedCloudConfig.functionsRegion.ifBlank { "us-central1" }

    /** True once firebase.properties was present at build time with the two essential values. */
    val isConfigured: Boolean get() = projectId.isNotBlank() && apiKey.isNotBlank()

    /** Base URL of the HTTPS Cloud Functions (callable protocol, see [CloudFunctions]). */
    val functionsBaseUrl: String get() = "https://$functionsRegion-$projectId.cloudfunctions.net"
}

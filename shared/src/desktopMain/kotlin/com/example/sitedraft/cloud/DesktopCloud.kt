package com.example.sitedraft.cloud

import android.app.Application
import com.google.firebase.FirebasePlatform
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize
import java.io.File
import java.util.Properties

/**
 * Firebase on the desktop JVM, the way CrewSync does it. GitLive's jvm artifacts run on
 * dev.gitlive:firebase-java-sdk, a Java port of the Android SDK that ships its own
 * android.app.Application shim; its documented bootstrap is
 * `Firebase.initialize(context = Application(), options)`. The port also keeps auth tokens
 * and the like through a [FirebasePlatform] singleton that must be installed first, or the
 * first persisted value fails with "FirebasePlatform.firebasePlatform is null".
 */
private class DesktopFirebasePlatform(root: File) : FirebasePlatform() {
    private val storeFile = File(root, "firebase-store.properties").apply { parentFile?.mkdirs() }
    private val props = Properties().apply { if (storeFile.exists()) storeFile.inputStream().use { load(it) } }

    @Synchronized
    override fun store(key: String, value: String) {
        props.setProperty(key, value)
        storeFile.outputStream().use { props.store(it, null) }
    }

    @Synchronized
    override fun retrieve(key: String): String? = props.getProperty(key)

    @Synchronized
    override fun clear(key: String) {
        props.remove(key)
        storeFile.outputStream().use { props.store(it, null) }
    }

    override fun log(msg: String) {
        println("FIREBASE: $msg")
    }
}

class DesktopCloudService(stateDir: File = File(System.getProperty("user.home"), ".sitedraft")) : GitLiveCloudService(startFirebase(stateDir)) {
    private companion object {
        fun startFirebase(stateDir: File): String? {
            if (!CloudConfig.isConfigured) return null
            return try {
                FirebasePlatform.initializeFirebasePlatform(DesktopFirebasePlatform(stateDir))
                Firebase.initialize(
                    context = Application(),
                    options = FirebaseOptions(
                        applicationId = CloudConfig.webAppId,
                        apiKey = CloudConfig.apiKey,
                        projectId = CloudConfig.projectId,
                        storageBucket = CloudConfig.storageBucket,
                    )
                )
                null
            } catch (e: Throwable) {
                if (e.message?.contains("already exists") == true) null else e.message ?: e.toString()
            }
        }
    }
}

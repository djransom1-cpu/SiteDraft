import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
}

// Firebase project values come from firebase.properties at the repo root (gitignored; CI
// recreates it from the FIREBASE_PROPERTIES_BASE64 secret, see docs/CLOUD_SETUP.md). They are
// compiled into a generated CloudConfig object so every target (Android, desktop, web, iOS)
// reads the same values. Without the file the object holds empty strings and the app says
// "cloud not configured" on its sign-in screen - it never lets anyone past the gate.
val cloudConfigKeys = listOf("projectId", "apiKey", "authDomain", "storageBucket", "webAppId", "googleWebClientId", "functionsRegion")
val cloudConfigValues: Map<String, String> = Properties().apply {
    val file = rootProject.file("firebase.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.let { props -> cloudConfigKeys.associateWith { key -> props.getProperty(key)?.trim() ?: "" } }

val generateCloudConfig by tasks.registering {
    val values = cloudConfigValues
    val outDir = layout.buildDirectory.dir("generated/cloudConfig/commonMain/kotlin")
    inputs.property("values", values.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" })
    outputs.dir(outDir)
    doLast {
        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val file = outDir.get().file("com/example/sitedraft/cloud/GeneratedCloudConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("package com.example.sitedraft.cloud")
            appendLine()
            appendLine("// GENERATED from firebase.properties by shared/build.gradle.kts - do not edit, do not commit.")
            appendLine("internal object GeneratedCloudConfig {")
            for (key in values.keys.sorted()) appendLine("    const val $key: String = ${quote(values.getValue(key))}")
            appendLine("}")
        })
    }
}

kotlin {
    androidLibrary {
        namespace = "com.example.sitedraft.shared"
        compileSdk = 37
        minSdk = 24
    }
    jvm("desktop")
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateCloudConfig)
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Android and the desktop JVM share the GitLive Firebase implementation (and a
        // HttpURLConnection transport). The web target has no stable GitLive artifact (only a
        // 3.0 alpha), so it uses the Firebase REST APIs from commonMain instead.
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.firebase.auth)
                implementation(libs.firebase.firestore)
            }
        }
        val androidMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.activity.compose)
                implementation(libs.accompanist.permissions)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.play.services)
                implementation(libs.googleid)
            }
        }
        val desktopMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

kotlin {
    sourceSets {
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("junit:junit:4.13.2")
            }
        }
    }
}

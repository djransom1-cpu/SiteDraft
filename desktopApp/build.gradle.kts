plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.example.sitedraft.desktop.MainKt"
        // Packaging needs a full JDK with jpackage; Android Studio's bundled runtime has none, so
        // use the JDK 17 toolchain Gradle already provisions for compiling.
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            // GitLive's firebase-java-sdk reaches jdk.unsupported and java.sql (sqlite-jdbc for the
            // Firestore cache) through reflection, which jlink's module detection misses; the
            // packaged app then dies on the first Firestore call. CrewSync hit the same thing:
            // bundle the whole JDK instead of enumerating modules.
            includeAllModules = true
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "SiteDraft"
            packageVersion = "1.0.5"
            description = "Construction drafting: floor plans, elevations, laser-measured walls"
            vendor = "SiteDraft"
            // App icon: the tablet's pencil-and-square glyph on a white tile, generated from the
            // Android launcher art by make-icon.ps1 (icon.ico for Windows, icon.png for Linux;
            // macOS wants an .icns made on a Mac).
            linux { iconFile.set(project.file("icon.png")) }
            windows {
                iconFile.set(project.file("icon.ico"))
                // Without these the MSI installs silently to C:\Program Files\SiteDraft and
                // nothing shows up in the Start Menu or on the desktop - the app looked
                // "not installed" on the laptop.
                menu = true
                menuGroup = "SiteDraft"
                shortcut = true
                dirChooser = true
                upgradeUuid = "8c0a0e6e-2a0f-4f5a-9b7a-5e1b7d1c0a11"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

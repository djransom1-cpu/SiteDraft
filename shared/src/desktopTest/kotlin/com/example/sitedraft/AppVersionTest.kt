package com.example.sitedraft

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The version shown in the app matches the one the builds are stamped with. */
class AppVersionTest {
    private fun read(path: String, key: String): String =
        Regex(key + """\s*=\s*"([^"]+)"""").find(File(path).readText())?.groupValues?.get(1) ?: error("no $key in $path")

    @Test
    fun `the version shown matches the desktop and Android builds`() {
        assertEquals("desktopApp packageVersion", read("../desktopApp/build.gradle.kts", "packageVersion"), AppVersion.NAME)
        assertEquals("app versionName", read("../app/build.gradle.kts", "versionName"), AppVersion.NAME)
    }
}

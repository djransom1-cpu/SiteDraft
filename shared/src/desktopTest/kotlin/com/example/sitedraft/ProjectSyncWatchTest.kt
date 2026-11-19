package com.example.sitedraft

import com.example.sitedraft.cloud.CloudService
import com.example.sitedraft.cloud.CloudStore
import com.example.sitedraft.cloud.CloudUser
import com.example.sitedraft.cloud.ProjectDoc
import com.example.sitedraft.cloud.ProjectMeta
import com.example.sitedraft.cloud.ProjectSharing
import com.example.sitedraft.cloud.ProjectSync
import com.example.sitedraft.cloud.ProjectSyncListener
import com.example.sitedraft.cloud.SharedProjectMeta
import com.example.sitedraft.cloud.UserDoc
import com.example.sitedraft.platform.InMemoryPrefs
import com.example.sitedraft.platform.InMemoryStorage
import com.example.sitedraft.platform.KeyValuePrefs
import com.example.sitedraft.platform.PageSpec
import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.ProjectStorage
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The open project reloads by itself when Claude changes it through the Site Draft connector. */
class ProjectSyncWatchTest {
    private class FakeStore : CloudStore {
        val projects = HashMap<String, ProjectDoc>()
        val stamps = HashMap<String, Long>()
        override suspend fun readUser(uid: String): UserDoc? = null
        override suspend fun touchUser(uid: String, email: String?, displayName: String?, device: String) {}
        override suspend fun isAllowlisted(email: String) = false
        override suspend fun listProjects(uid: String) = projects.values.map { ProjectMeta(it.name, it.updatedAt, it.device, 1) }
        override suspend fun readProject(uid: String, name: String) = projects[name]
        override suspend fun writeProject(uid: String, name: String, json: String, updatedAt: Long, device: String) {
            projects[name] = ProjectDoc(name, updatedAt, device, json)
        }
        override suspend fun deleteProject(uid: String, name: String) { projects.remove(name) }
        override suspend fun projectStamp(uid: String, name: String): Long? = stamps[name]
        override suspend fun readSharing(ownerUid: String, name: String): ProjectSharing? = null
        override suspend fun writeSharing(ownerUid: String, name: String, sharing: ProjectSharing) {}
        override suspend fun listSharedWithMe(email: String) = emptyList<SharedProjectMeta>()
        override suspend fun redeemAccessCode(uid: String, code: String) {}
        override suspend fun isAccessCodeUsable(code: String) = false
    }

    private class FakeCloud(override val store: FakeStore = FakeStore()) : CloudService {
        override val isConfigured = true
        override val user: StateFlow<CloudUser?> = MutableStateFlow(CloudUser("u1", "a@b.c"))
        override suspend fun signIn(email: String, password: String) {}
        override suspend fun signUp(email: String, password: String) {}
        override suspend fun sendPasswordReset(email: String) {}
        override suspend fun signOut() {}
        override suspend fun idToken(): String? = "t"
    }

    private class TestPlatform(override val cloud: CloudService) : Platform {
        override val name = "test"
        override val storage: ProjectStorage = InMemoryStorage()
        override val prefs: KeyValuePrefs = InMemoryPrefs()
        override fun exportPage(fileName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? = null
        override fun exportText(fileName: String, text: String): String? = null
    }

    private class Screen(var open: String? = "Kitchen", var unsaved: Boolean = false) : ProjectSyncListener {
        val pulled = ArrayList<String>()
        override fun onProjectPulled(name: String) { pulled += name }
        override fun onProjectListChanged() {}
        override fun openProjectName() = open
        override fun hasUnsavedEdits() = unsaved
    }

    private fun awaitUntil(condition: () -> Boolean) = runBlocking { withTimeout(5_000) { while (!condition()) kotlinx.coroutines.delay(10) } }

    private val mine = """{"projectName": "Kitchen", "n": 1}"""
    private val claudes = """{"projectName": "Kitchen", "n": 2}"""

    /** A project saved and pushed at t=100, then changed by Claude at t=200. */
    private fun setUp(screen: Screen = Screen()): Triple<FakeCloud, TestPlatform, ProjectSync> {
        val cloud = FakeCloud()
        val platform = TestPlatform(cloud)
        val sync = ProjectSync(platform, cloud, screen)
        sync.onLocalSaved("Kitchen", mine, savedAt = 100)
        awaitUntil { sync.readMeta("Kitchen")?.remoteUpdatedAt == 100L }
        cloud.store.projects["Kitchen"] = ProjectDoc("Kitchen", 200, "Claude", claudes)
        cloud.store.stamps["Kitchen"] = 200
        return Triple(cloud, platform, sync)
    }

    @Test
    fun `a change Claude made reloads the open project`() {
        val screen = Screen()
        val (_, platform, sync) = setUp(screen)
        assertTrue(runBlocking { sync.checkOpenProject() })
        assertEquals(claudes, platform.storage.read(ProjectSync.projectFileName("Kitchen")))
        assertEquals(200L, sync.readMeta("Kitchen")!!.remoteUpdatedAt)
        awaitUntil { screen.pulled.isNotEmpty() }
        assertEquals(listOf("Kitchen"), screen.pulled)
        // Seen now: the next check does nothing.
        assertFalse(runBlocking { sync.checkOpenProject() })
        assertNull(platform.storage.read(ProjectSync.projectFileName("Kitchen (test edits)")))
    }

    @Test
    fun `nothing happens without a newer stamp, while saving, or for a project not open`() {
        val screen = Screen(unsaved = true)
        val (cloud, _, sync) = setUp(screen)
        assertFalse("waits for the autosave", runBlocking { sync.checkOpenProject() })
        screen.unsaved = false
        screen.open = "Bathroom"
        assertFalse(runBlocking { sync.checkOpenProject() })
        screen.open = "Kitchen"
        cloud.store.stamps["Kitchen"] = 100
        assertFalse("an old stamp", runBlocking { sync.checkOpenProject() })
    }

    @Test
    fun `a save made after Claude's change keeps both`() {
        val (cloud, platform, sync) = setUp()
        val dans = """{"projectName": "Kitchen", "n": 3}"""
        platform.storage.write(ProjectSync.projectFileName("Kitchen"), dans)
        sync.onLocalSaved("Kitchen", dans, savedAt = 300)
        awaitUntil { platform.storage.read(ProjectSync.projectFileName("Kitchen")) == claudes }
        // Claude's version is not overwritten in the cloud...
        assertEquals(claudes, cloud.store.projects["Kitchen"]!!.json)
        // ...and Dan's edit is kept as its own project, uploaded too.
        val copy = "Kitchen (test edits)"
        awaitUntil { cloud.store.projects.containsKey(copy) }
        assertTrue(platform.storage.read(ProjectSync.projectFileName(copy))!!.contains("\"n\": 3"))
        assertTrue(cloud.store.projects[copy]!!.json.contains("\"projectName\": \"$copy\""))
    }
}

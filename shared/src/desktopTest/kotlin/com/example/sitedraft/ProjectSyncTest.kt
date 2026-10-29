package com.example.sitedraft

import com.example.sitedraft.cloud.CloudService
import com.example.sitedraft.cloud.CloudStore
import com.example.sitedraft.cloud.CloudUser
import com.example.sitedraft.cloud.ProjectChunks
import com.example.sitedraft.cloud.ProjectDoc
import com.example.sitedraft.cloud.ProjectMeta
import com.example.sitedraft.cloud.ProjectSync
import com.example.sitedraft.cloud.ProjectSharing
import com.example.sitedraft.cloud.SharedProjectMeta
import com.example.sitedraft.cloud.ProjectSyncListener
import com.example.sitedraft.cloud.SyncAction
import com.example.sitedraft.cloud.UserDoc
import com.example.sitedraft.cloud.chooseSyncAction
import com.example.sitedraft.platform.InMemoryPrefs
import com.example.sitedraft.platform.InMemoryStorage
import com.example.sitedraft.platform.KeyValuePrefs
import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.PageSpec
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

class ProjectSyncTest {

    // ------------------------------------------------------------------ the merge rule

    @Test
    fun lastWriteWins() {
        assertEquals(SyncAction.PULL, chooseSyncAction(localUpdatedAt = 100, remoteUpdatedAt = 200))
        assertEquals(SyncAction.PUSH, chooseSyncAction(localUpdatedAt = 300, remoteUpdatedAt = 200))
        assertEquals(SyncAction.NONE, chooseSyncAction(localUpdatedAt = 200, remoteUpdatedAt = 200))
    }

    @Test
    fun missingSidesMoveTheOtherWay() {
        assertEquals(SyncAction.PULL, chooseSyncAction(localUpdatedAt = null, remoteUpdatedAt = 5))
        assertEquals(SyncAction.PUSH, chooseSyncAction(localUpdatedAt = 5, remoteUpdatedAt = null))
        assertEquals(SyncAction.NONE, chooseSyncAction(localUpdatedAt = null, remoteUpdatedAt = null))
    }

    @Test
    fun aLocalFileFromBeforeSyncLosesToAnyCloudCopy() {
        // Pre-sync projects have no meta and are treated as updatedAt = 0.
        assertEquals(SyncAction.PULL, chooseSyncAction(localUpdatedAt = 0, remoteUpdatedAt = 1))
        assertEquals(SyncAction.PUSH, chooseSyncAction(localUpdatedAt = 0, remoteUpdatedAt = null))
    }

    // ------------------------------------------------------------------ chunking

    @Test
    fun smallJsonIsOneChunkAndRoundTrips() {
        val json = """{"a":1}"""
        val chunks = ProjectChunks.split(json)
        assertEquals(1, chunks.size)
        assertEquals(json, ProjectChunks.join(chunks))
        assertEquals(listOf(""), ProjectChunks.split(""))
    }

    @Test
    fun bigJsonSplitsOnTheLimitAndRoundTrips() {
        val json = buildString { repeat(ProjectChunks.CHUNK_CHARS * 2 + 17) { append('x' + (it % 26)) } }
        val chunks = ProjectChunks.split(json)
        assertEquals(3, chunks.size)
        assertEquals(ProjectChunks.CHUNK_CHARS, chunks[0].length)
        assertEquals(ProjectChunks.CHUNK_CHARS, chunks[1].length)
        assertEquals(17, chunks[2].length)
        assertEquals(json, ProjectChunks.join(chunks))
    }

    // ------------------------------------------------------------------ the engine, with a fake cloud

    private class FakeStore : CloudStore {
        val projects = HashMap<String, ProjectDoc>()
        var writes = 0
        override suspend fun readUser(uid: String): UserDoc? = null
        override suspend fun touchUser(uid: String, email: String?, displayName: String?, device: String) {}
        override suspend fun isAllowlisted(email: String) = false
        override suspend fun listProjects(uid: String) = projects.values.map { ProjectMeta(it.name, it.updatedAt, it.device, 1) }
        override suspend fun readProject(uid: String, name: String) = projects[name]
        override suspend fun writeProject(uid: String, name: String, json: String, updatedAt: Long, device: String) {
            writes++
            projects[name] = ProjectDoc(name, updatedAt, device, json)
        }
        override suspend fun deleteProject(uid: String, name: String) { projects.remove(name) }
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

    private class Recorder : ProjectSyncListener {
        val pulled = ArrayList<String>()
        var listChanges = 0
        override fun onProjectPulled(name: String) { pulled += name }
        override fun onProjectListChanged() { listChanges++ }
    }

    private fun awaitUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun aLocalSaveIsPushedAndRemembered() {
        val cloud = FakeCloud()
        val platform = TestPlatform(cloud)
        val sync = ProjectSync(platform, cloud)
        sync.onLocalSaved("Kitchen", """{"k":1}""", savedAt = 1000)
        awaitUntil { cloud.store.projects.containsKey("Kitchen") }
        assertEquals(1000L, cloud.store.projects["Kitchen"]!!.updatedAt)
        awaitUntil { sync.readMeta("Kitchen")?.pendingPush == false }
        assertEquals(1000L, sync.readMeta("Kitchen")!!.remoteUpdatedAt)
    }

    @Test
    fun aNewerCloudCopyIsPulledAndReported() {
        val cloud = FakeCloud()
        val platform = TestPlatform(cloud)
        val recorder = Recorder()
        val sync = ProjectSync(platform, cloud, recorder)
        platform.storage.write("project_Kitchen.json", """{"old":true}""")
        platform.storage.write("project_list.txt", "Kitchen")
        sync.onLocalSaved("Kitchen", """{"old":true}""", savedAt = 1000)
        awaitUntil { cloud.store.writes == 1 }
        // Another device saved later.
        cloud.store.projects["Kitchen"] = ProjectDoc("Kitchen", 2000, "tablet", """{"new":true}""")
        cloud.store.projects["Deck"] = ProjectDoc("Deck", 50, "tablet", """{"deck":true}""")

        sync.syncAll()
        awaitUntil { recorder.pulled.contains("Kitchen") && recorder.listChanges == 1 }
        assertEquals("""{"new":true}""", platform.storage.read("project_Kitchen.json"))
        assertEquals(2000L, sync.readMeta("Kitchen")!!.updatedAt)
        assertEquals("""{"deck":true}""", platform.storage.read("project_Deck.json"))
        assertEquals("Deck\nKitchen", platform.storage.read("project_list.txt"))
        assertEquals(1, cloud.store.writes) // nothing was pushed back
    }

    @Test
    fun aNewerLocalCopyIsPushedOnSyncAll() {
        val cloud = FakeCloud()
        val platform = TestPlatform(cloud)
        val recorder = Recorder()
        val sync = ProjectSync(platform, cloud, recorder)
        cloud.store.projects["Kitchen"] = ProjectDoc("Kitchen", 1000, "desktop", """{"old":true}""")
        platform.storage.write("project_Kitchen.json", """{"local":true}""")
        platform.storage.write("project_list.txt", "Kitchen")
        platform.storage.write("sync_Kitchen.json", """{"updatedAt":3000,"pendingPush":true}""")

        sync.syncAll()
        awaitUntil { cloud.store.projects["Kitchen"]?.updatedAt == 3000L }
        assertEquals("""{"local":true}""", cloud.store.projects["Kitchen"]!!.json)
        assertTrue(recorder.pulled.isEmpty())
        assertFalse(sync.readMeta("Kitchen")!!.pendingPush)
    }

    @Test
    fun aLocalOnlyProjectFromBeforeSyncIsUploaded() {
        val cloud = FakeCloud()
        val platform = TestPlatform(cloud)
        val sync = ProjectSync(platform, cloud)
        platform.storage.write("project_Garage.json", """{"g":1}""")
        platform.storage.write("project_list.txt", "Garage")
        assertNull(sync.readMeta("Garage"))

        sync.syncAll()
        awaitUntil { cloud.store.projects.containsKey("Garage") }
        assertEquals("""{"g":1}""", cloud.store.projects["Garage"]!!.json)
        assertTrue(cloud.store.projects["Garage"]!!.updatedAt > 0)
    }

    @Test
    fun aNeverSyncedLocalProjectIsKeptAsACopyWhenTheCloudHasTheSameName() {
        // Tablet and desktop both had an "Untitled Project" before accounts existed. The
        // tablet uploaded first; the desktop must not lose its own drawing.
        val cloud = FakeCloud()
        val platform = TestPlatform(cloud)
        val recorder = Recorder()
        val sync = ProjectSync(platform, cloud, recorder)
        cloud.store.projects["Untitled Project"] = ProjectDoc("Untitled Project", 5000, "android", """{"projectName":"Untitled Project","from":"tablet"}""")
        platform.storage.write("project_Untitled_Project.json", """{"projectName":"Untitled Project","from":"desktop"}""")
        platform.storage.write("project_list.txt", "Untitled Project")

        sync.syncAll()
        awaitUntil { cloud.store.projects.containsKey("Untitled Project (test)") }
        // The open name now holds the cloud copy...
        assertTrue(platform.storage.read("project_Untitled_Project.json")!!.contains("tablet"))
        // ...and this device's drawing survives under a new name, renamed inside too, uploaded.
        val kept = platform.storage.read("project_Untitled_Project_(test).json")!!
        assertTrue(kept.contains("desktop"))
        assertTrue(kept.contains("\"projectName\": \"Untitled Project (test)\""))
        assertTrue(cloud.store.projects["Untitled Project (test)"]!!.json.contains("desktop"))
        assertEquals("Untitled Project\nUntitled Project (test)", platform.storage.read("project_list.txt"))
    }

    @Test
    fun anAlreadySyncedProjectIsOverwrittenWithoutACopy() {
        val cloud = FakeCloud()
        val platform = TestPlatform(cloud)
        val sync = ProjectSync(platform, cloud)
        cloud.store.projects["Kitchen"] = ProjectDoc("Kitchen", 5000, "android", """{"v":2}""")
        platform.storage.write("project_Kitchen.json", """{"v":1}""")
        platform.storage.write("project_list.txt", "Kitchen")
        platform.storage.write("sync_Kitchen.json", """{"updatedAt":1000,"remoteUpdatedAt":1000}""")

        sync.syncAll()
        awaitUntil { platform.storage.read("project_Kitchen.json") == """{"v":2}""" }
        assertEquals("Kitchen", platform.storage.read("project_list.txt"))
        assertEquals(1, cloud.store.projects.size)
    }

    @Test
    fun equalTimestampsMoveNothing() {
        val cloud = FakeCloud()
        val platform = TestPlatform(cloud)
        val recorder = Recorder()
        val sync = ProjectSync(platform, cloud, recorder)
        cloud.store.projects["Kitchen"] = ProjectDoc("Kitchen", 1000, "desktop", """{"same":true}""")
        platform.storage.write("project_Kitchen.json", """{"same":true}""")
        platform.storage.write("project_list.txt", "Kitchen")
        platform.storage.write("sync_Kitchen.json", """{"updatedAt":1000,"remoteUpdatedAt":1000}""")

        sync.syncAll()
        runBlocking { kotlinx.coroutines.delay(200) }
        assertEquals(0, cloud.store.writes)
        assertTrue(recorder.pulled.isEmpty())
    }
}

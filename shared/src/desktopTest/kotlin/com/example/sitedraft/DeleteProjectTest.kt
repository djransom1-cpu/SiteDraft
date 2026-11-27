package com.example.sitedraft

import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.example.sitedraft.platform.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delete Project (Dan, Nov 27): tombstone in the cloud, off every device's list, 30 days in Recently deleted, Restore. */
class DeleteProjectTest {
    private class Store : CloudStore {
        val projects = HashMap<String, ProjectDoc>()
        val deletedAt = HashMap<String, Long>()
        override suspend fun readUser(uid: String): UserDoc? = null
        override suspend fun touchUser(uid: String, email: String?, displayName: String?, device: String) {}
        override suspend fun isAllowlisted(email: String) = false
        override suspend fun listProjects(uid: String) = projects.values.map { ProjectMeta(it.name, it.updatedAt, it.device, 1, deletedAt[it.name] ?: 0L) }
        override suspend fun readProject(uid: String, name: String) = projects[name]
        override suspend fun writeProject(uid: String, name: String, json: String, updatedAt: Long, device: String) {
            projects[name] = ProjectDoc(name, updatedAt, device, json) // a merge: deletedAt stays
        }
        override suspend fun deleteProject(uid: String, name: String) { projects.remove(name); deletedAt.remove(name) }
        override suspend fun setProjectDeleted(uid: String, name: String, deletedAt: Long) { this.deletedAt[name] = deletedAt }
        override suspend fun projectUpdatedAt(uid: String, name: String) = projects[name]?.updatedAt
        override suspend fun readSharing(ownerUid: String, name: String): ProjectSharing? = null
        override suspend fun writeSharing(ownerUid: String, name: String, sharing: ProjectSharing) {}
        override suspend fun listSharedWithMe(email: String) = emptyList<SharedProjectMeta>()
        override suspend fun redeemAccessCode(uid: String, code: String) {}
        override suspend fun isAccessCodeUsable(code: String) = false
    }

    private class Cloud(override val store: Store) : CloudService {
        override val isConfigured = true
        override val user: StateFlow<CloudUser?> = MutableStateFlow(CloudUser("u1", "a@b.c"))
        override suspend fun signIn(email: String, password: String) {}
        override suspend fun signUp(email: String, password: String) {}
        override suspend fun sendPasswordReset(email: String) {}
        override suspend fun signOut() {}
        override suspend fun idToken(): String? = "t"
    }

    private class Device(override val cloud: CloudService, override val name: String) : Platform {
        override val storage: ProjectStorage = InMemoryStorage()
        override val prefs: KeyValuePrefs = InMemoryPrefs()
        override fun exportPage(fileName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? = null
        override fun exportText(fileName: String, text: String): String? = null
        fun list() = storage.read(ProjectSync.PROJECT_LIST_FILE)?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    private class Open(var name: String? = null) : ProjectSyncListener {
        override fun onProjectPulled(name: String) {}
        override fun onProjectListChanged() {}
        override fun openProjectName() = name
    }

    /** A device with [names] saved locally and in the cloud. */
    private fun device(store: Store, label: String, open: Open, vararg names: String): Pair<Device, ProjectSync> {
        val d = Device(Cloud(store), label)
        val sync = ProjectSync(d, d.cloud, open)
        for (n in names) {
            d.storage.write(ProjectSync.projectFileName(n), """{"projectName":"$n"}""")
            store.projects[n] = ProjectDoc(n, 1000L, label, """{"projectName":"$n"}""")
        }
        d.storage.write(ProjectSync.PROJECT_LIST_FILE, names.joinToString("\n"))
        return d to sync
    }

    @Test
    fun `deleting takes it off every device and nothing pushes it back`() = runBlocking {
        val store = Store()
        val (desk, deskSync) = device(store, "desktop", Open("Martin"), "Martin", "Ransom House", "Old Job")
        val tablet = Device(Cloud(store), "tablet")
        val tabletSync = ProjectSync(tablet, tablet.cloud, Open("Martin"))
        // The tablet has every job too.
        for (n in listOf("Martin", "Ransom House", "Old Job")) tablet.storage.write(ProjectSync.projectFileName(n), """{"projectName":"$n"}""")
        tablet.storage.write(ProjectSync.PROJECT_LIST_FILE, "Martin\nRansom House\nOld Job")

        assertNull(deskSync.deleteProject("Old Job"))
        assertFalse("Old Job" in desk.list())
        assertTrue("the drawing file stays on the device", desk.storage.exists(ProjectSync.projectFileName("Old Job")))
        assertTrue(store.deletedAt.getValue("Old Job") > 0L)
        assertTrue("still in the cloud for Restore", store.projects.containsKey("Old Job"))

        // The tablet's next sync takes it off its list and doesn't push its copy back.
        tabletSync.syncAll(); kotlinx.coroutines.delay(500)
        assertFalse("Old Job" in tablet.list())
        assertTrue(store.deletedAt.getValue("Old Job") > 0L)
        assertEquals(listOf("Old Job"), tabletSync.recentlyDeleted().map { it.name })
        assertEquals(30, tabletSync.recentlyDeleted().single().daysLeft())
    }

    @Test
    fun `the open job can't be deleted`() = runBlocking {
        val store = Store()
        val (desk, deskSync) = device(store, "desktop", Open("Martin"), "Martin")
        assertNotNull(deskSync.deleteProject("Martin"))
        assertTrue("Martin" in desk.list())
        assertFalse(store.deletedAt.containsKey("Martin"))
    }

    @Test
    fun `restore brings it back on the other devices too`() = runBlocking {
        val store = Store()
        val (desk, deskSync) = device(store, "desktop", Open("Martin"), "Martin", "Old Job")
        val tablet = Device(Cloud(store), "tablet")
        val tabletSync = ProjectSync(tablet, tablet.cloud, Open(null))
        assertNull(deskSync.deleteProject("Old Job"))
        tabletSync.syncAll(); kotlinx.coroutines.delay(500)
        assertFalse("Old Job" in tablet.list())

        // Restored from the tablet (it never had a copy: it downloads it).
        assertNull(tabletSync.restoreProject("Old Job"))
        assertTrue("Old Job" in tablet.list())
        assertEquals(0L, store.deletedAt["Old Job"])
        // And the desktop puts it back in its list.
        deskSync.syncAll(); kotlinx.coroutines.delay(500)
        assertTrue("Old Job" in desk.list())
        assertTrue(deskSync.recentlyDeleted().isEmpty())
    }

    @Test
    fun `after 30 days the cloud copy is removed`() = runBlocking {
        val store = Store()
        val (desk, deskSync) = device(store, "desktop", Open(null), "Old Job")
        store.deletedAt["Old Job"] = nowMillis() - ProjectSync.KEEP_DELETED_MS - 1000L
        desk.storage.write(ProjectSync.PROJECT_LIST_FILE, "")
        deskSync.syncAll(); kotlinx.coroutines.delay(500)
        assertFalse(store.projects.containsKey("Old Job"))
        assertTrue(deskSync.recentlyDeleted().isEmpty())
        assertFalse("not pulled back either", "Old Job" in desk.list())
    }

    @Test
    fun `deleted while offline, the tombstone goes up at the next sync`() = runBlocking {
        val store = Store()
        val offline = Store()
        val d = Device(Cloud(offline), "desktop")
        // Deleted against an empty "cloud" (as if offline): not marked there.
        val sync = ProjectSync(d, d.cloud, Open(null))
        d.storage.write(ProjectSync.projectFileName("Old Job"), "{}")
        d.storage.write(ProjectSync.PROJECT_LIST_FILE, "Old Job")
        assertNull(sync.deleteProject("Old Job"))
        // Back online with the real cloud, which has the job.
        store.projects["Old Job"] = ProjectDoc("Old Job", 1000L, "tablet", "{}")
        val d2 = object : Platform by d { override val cloud: CloudService = Cloud(store) }
        val sync2 = ProjectSync(d2, d2.cloud, Open(null))
        sync2.syncAll(); kotlinx.coroutines.delay(500)
        assertTrue(store.deletedAt.getValue("Old Job") > 0L)
        assertFalse("Old Job" in d.list())
    }
}

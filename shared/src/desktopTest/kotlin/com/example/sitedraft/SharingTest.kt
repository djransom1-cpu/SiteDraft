package com.example.sitedraft

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.sitedraft.cloud.CloudDocs
import com.example.sitedraft.cloud.CloudService
import com.example.sitedraft.cloud.CloudStore
import com.example.sitedraft.cloud.CloudUser
import com.example.sitedraft.cloud.FirestoreValues
import com.example.sitedraft.cloud.Jwt
import com.example.sitedraft.cloud.ProjectDoc
import com.example.sitedraft.cloud.ProjectMeta
import com.example.sitedraft.cloud.ProjectSharing
import com.example.sitedraft.cloud.ProjectSync
import com.example.sitedraft.cloud.ProjectSyncListener
import com.example.sitedraft.cloud.SharedProjectMeta
import com.example.sitedraft.cloud.Sharing
import com.example.sitedraft.cloud.SyncAction
import com.example.sitedraft.cloud.UserDoc
import com.example.sitedraft.cloud.chooseSharedSyncAction
import com.example.sitedraft.cloud.sharedLocalName
import com.example.sitedraft.platform.InMemoryPrefs
import com.example.sitedraft.platform.InMemoryStorage
import com.example.sitedraft.platform.KeyValuePrefs
import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.PageSpec
import com.example.sitedraft.platform.ProjectStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SharingTest {
    private val owner = ProjectSharing(ownerUid = "o1", ownerEmail = "owner@x.com")

    // ------------------------------------------------------------------ the sharing list

    @Test
    fun addNormalisesAndKeepsEditorsASubsetOfMembers() {
        val s = Sharing.add(owner, "  Bob@Example.COM ", canEdit = true)
        assertEquals(listOf("bob@example.com"), s.memberEmails)
        assertEquals(listOf("bob@example.com"), s.editorEmails)
        val viewer = Sharing.add(s, "amy@example.com", canEdit = false)
        assertEquals(listOf("amy@example.com", "bob@example.com"), viewer.memberEmails)
        assertEquals(listOf("bob@example.com"), viewer.editorEmails)
        assertTrue(Sharing.canEdit(viewer, "BOB@example.com"))
        assertFalse(Sharing.canEdit(viewer, "amy@example.com"))
    }

    @Test
    fun addingAgainChangesTheRole() {
        val s = Sharing.add(Sharing.add(owner, "bob@example.com", true), "bob@example.com", false)
        assertEquals(listOf("bob@example.com"), s.memberEmails)
        assertTrue(s.editorEmails.isEmpty())
    }

    @Test
    fun removeTakesThePersonOutOfBothLists() {
        val s = Sharing.remove(Sharing.add(owner, "bob@example.com", true), "Bob@example.com")
        assertTrue(s.memberEmails.isEmpty())
        assertTrue(s.editorEmails.isEmpty())
    }

    @Test
    fun badAddressesAndYourselfAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { Sharing.add(owner, "not-an-email", true) }
        assertThrows(IllegalArgumentException::class.java) { Sharing.add(owner, "OWNER@x.com", true) }
    }

    @Test
    fun sharedProjectsGetAUniqueLocalName() {
        assertEquals("Kitchen (bob)", sharedLocalName("Kitchen", "bob@example.com", emptySet()))
        assertEquals("Kitchen (bob) 2", sharedLocalName("Kitchen", "bob@example.com", setOf("Kitchen (bob)")))
        assertEquals("Kitchen (shared)", sharedLocalName("Kitchen", null, emptySet()))
    }

    // ------------------------------------------------------------------ the merge rule for shares

    @Test
    fun editorsFollowLastWriteWins() {
        assertEquals(SyncAction.PUSH, chooseSharedSyncAction(canEdit = true, localUpdatedAt = 300, remoteUpdatedAt = 200))
        assertEquals(SyncAction.PULL, chooseSharedSyncAction(canEdit = true, localUpdatedAt = 100, remoteUpdatedAt = 200))
    }

    @Test
    fun viewersNeverPushAndLoseLocalEdits() {
        assertEquals(SyncAction.PULL, chooseSharedSyncAction(canEdit = false, localUpdatedAt = 300, remoteUpdatedAt = 200))
        assertEquals(SyncAction.PULL, chooseSharedSyncAction(canEdit = false, localUpdatedAt = null, remoteUpdatedAt = 200))
        assertEquals(SyncAction.NONE, chooseSharedSyncAction(canEdit = false, localUpdatedAt = 200, remoteUpdatedAt = 200))
        assertEquals(SyncAction.NONE, chooseSharedSyncAction(canEdit = false, localUpdatedAt = 200, remoteUpdatedAt = null))
    }

    // ------------------------------------------------------------------ wire helpers

    @Test
    fun urlPathEncodesEachSegment() {
        assertEquals("users/u1/projects/Untitled%20Project", FirestoreValues.urlPath("users/u1/projects/Untitled Project"))
        assertEquals("allowlist/bob%40x.com", FirestoreValues.urlPath("allowlist/bob@x.com"))
        assertEquals("a/%23%3F%25", FirestoreValues.urlPath("a/#?%"))
        assertEquals("a/caf%C3%A9", FirestoreValues.urlPath("a/café"))
    }

    @Test
    fun jwtEmailVerifiedClaimIsRead() {
        fun token(payload: String) = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"
        assertEquals(true, Jwt.booleanClaim(token("""{"email_verified":true,"sub":"u"}"""), "email_verified"))
        assertEquals(false, Jwt.booleanClaim(token("""{"email_verified":false}"""), "email_verified"))
        assertNull(Jwt.booleanClaim(token("""{"sub":"u"}"""), "email_verified"))
        assertNull(Jwt.booleanClaim("garbage", "email_verified"))
    }

    @Test
    fun sharedQueryRowsBecomeMetas() {
        val fields = buildJsonObject {
            put("name", JsonPrimitive("Kitchen"))
            put("updatedAt", JsonPrimitive(42L))
            put("ownerEmail", JsonPrimitive("owner@x.com"))
            put("memberEmails", Json.parseToJsonElement("""["bob@x.com","amy@x.com"]"""))
            put("editorEmails", Json.parseToJsonElement("""["bob@x.com"]"""))
        }
        val bob = CloudDocs.sharedMetaFrom("users/o1/projects/Kitchen", fields, "bob@x.com")!!
        assertEquals("o1", bob.ownerUid)
        assertEquals("Kitchen", bob.name)
        assertTrue(bob.canEdit)
        assertFalse(CloudDocs.sharedMetaFrom("users/o1/projects/Kitchen", fields, "amy@x.com")!!.canEdit)
        assertNull(CloudDocs.sharedMetaFrom("teams/t/projects/Kitchen", fields, "bob@x.com"))
        assertEquals("users/o1/projects/K", FirestoreValues.relativePath("projects/p/databases/(default)/documents/users/o1/projects/K"))
    }

    // ------------------------------------------------------------------ the engine, with a fake cloud

    /** Projects keyed by "ownerUid/name"; sharing kept beside them. */
    private class FakeStore : CloudStore {
        val projects = HashMap<String, ProjectDoc>()
        val sharing = HashMap<String, ProjectSharing>()
        val writes = ArrayList<String>()
        override suspend fun readUser(uid: String): UserDoc? = null
        override suspend fun touchUser(uid: String, email: String?, displayName: String?, device: String) {}
        override suspend fun isAllowlisted(email: String) = false
        override suspend fun listProjects(uid: String) =
            projects.filterKeys { it.startsWith("$uid/") }.values.map { ProjectMeta(it.name, it.updatedAt, it.device, 1) }
        override suspend fun readProject(uid: String, name: String) = projects["$uid/$name"]
        override suspend fun writeProject(uid: String, name: String, json: String, updatedAt: Long, device: String) {
            writes += "$uid/$name"
            projects["$uid/$name"] = ProjectDoc(name, updatedAt, device, json)
        }
        override suspend fun deleteProject(uid: String, name: String) { projects.remove("$uid/$name") }
        override suspend fun readSharing(ownerUid: String, name: String) = sharing["$ownerUid/$name"]
        override suspend fun writeSharing(ownerUid: String, name: String, sharing: ProjectSharing) { this.sharing["$ownerUid/$name"] = sharing }
        override suspend fun listSharedWithMe(email: String) = sharing.filterValues { email in it.memberEmails }.map { (key, s) ->
            val doc = projects[key]!!
            SharedProjectMeta(s.ownerUid, s.ownerEmail, doc.name, doc.updatedAt, email in s.editorEmails)
        }
    }

    private class FakeCloud(val store0: FakeStore, user: CloudUser) : CloudService {
        override val isConfigured = true
        override val store: CloudStore get() = store0
        override val user = MutableStateFlow<CloudUser?>(user)
        override suspend fun signIn(email: String, password: String) {}
        override suspend fun signUp(email: String, password: String) {}
        override suspend fun sendPasswordReset(email: String) {}
        override suspend fun signOut() {}
        override suspend fun idToken(): String? = "t"
    }

    private class Device(override val cloud: CloudService) : Platform {
        override val name = "test"
        override val storage: ProjectStorage = InMemoryStorage()
        override val prefs: KeyValuePrefs = InMemoryPrefs()
        override fun exportPage(fileName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? = null
        override fun exportText(fileName: String, text: String): String? = null
    }

    private class Recorder : ProjectSyncListener {
        val pulled = ArrayList<String>()
        override fun onProjectPulled(name: String) { pulled += name }
        override fun onProjectListChanged() {}
    }

    private fun awaitUntil(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    private val bobUser = CloudUser("b1", "bob@x.com", emailVerified = true)

    private fun ownerShares(store: FakeStore, canEdit: Boolean) {
        store.projects["o1/Kitchen"] = ProjectDoc("Kitchen", 1000, "android", """{"projectName":"Kitchen","v":1}""")
        store.sharing["o1/Kitchen"] = Sharing.add(owner, "bob@x.com", canEdit)
    }

    @Test
    fun aShareArrivesUnderItsOwnNameWithTheNameInsideRenamed() {
        val store = FakeStore().also { ownerShares(it, canEdit = true) }
        val device = Device(FakeCloud(store, bobUser))
        val recorder = Recorder()
        val sync = ProjectSync(device, device.cloud, recorder)

        sync.syncAll()
        awaitUntil { recorder.pulled.contains("Kitchen (owner)") }
        val local = device.storage.read("project_Kitchen_(owner).json")!!
        assertTrue(local.contains("\"projectName\": \"Kitchen (owner)\""))
        assertTrue(device.storage.read("project_list.txt")!!.contains("Kitchen (owner)"))
        assertEquals("o1", sync.sharedRef("Kitchen (owner)")!!.ownerUid)
        // Nothing went into Bob's own account.
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun anEditorsSaveGoesToTheOwnersProjectUnderTheOriginalName() {
        val store = FakeStore().also { ownerShares(it, canEdit = true) }
        val device = Device(FakeCloud(store, bobUser))
        val recorder = Recorder()
        val sync = ProjectSync(device, device.cloud, recorder)
        sync.syncAll()
        awaitUntil { recorder.pulled.contains("Kitchen (owner)") }

        sync.onLocalSaved("Kitchen (owner)", """{"projectName":"Kitchen (owner)","v":2}""", savedAt = 2000)
        awaitUntil { store.projects["o1/Kitchen"]!!.updatedAt == 2000L }
        assertEquals(listOf("o1/Kitchen"), store.writes)
        assertTrue(store.projects["o1/Kitchen"]!!.json.contains("\"projectName\": \"Kitchen\""))
        assertTrue(store.projects.keys.none { it.startsWith("b1/") })
    }

    @Test
    fun aViewersSaveStaysLocal() {
        val store = FakeStore().also { ownerShares(it, canEdit = false) }
        val device = Device(FakeCloud(store, bobUser))
        val recorder = Recorder()
        val sync = ProjectSync(device, device.cloud, recorder)
        sync.syncAll()
        awaitUntil { recorder.pulled.contains("Kitchen (owner)") }

        sync.onLocalSaved("Kitchen (owner)", """{"projectName":"Kitchen (owner)","v":9}""", savedAt = 2000)
        runBlocking { delay(200) }
        assertTrue(store.writes.isEmpty())
        // And the next sync puts the owner's copy back.
        recorder.pulled.clear()
        sync.syncAll()
        awaitUntil { recorder.pulled.contains("Kitchen (owner)") }
        assertTrue(device.storage.read("project_Kitchen_(owner).json")!!.contains("\"v\": 1"))
    }

    @Test
    fun aWithdrawnShareLeavesTheListButTheFileStays() {
        val store = FakeStore().also { ownerShares(it, canEdit = true) }
        val device = Device(FakeCloud(store, bobUser))
        val recorder = Recorder()
        val sync = ProjectSync(device, device.cloud, recorder)
        sync.syncAll()
        awaitUntil { recorder.pulled.contains("Kitchen (owner)") }

        store.sharing["o1/Kitchen"] = Sharing.remove(store.sharing["o1/Kitchen"]!!, "bob@x.com")
        sync.syncAll()
        awaitUntil { sync.shared.value.isEmpty() }
        assertFalse(device.storage.read("project_list.txt")!!.contains("Kitchen (owner)"))
        assertTrue(device.storage.exists("project_Kitchen_(owner).json"))
        // A withdrawn share is not re-uploaded into Bob's own account either.
        runBlocking { delay(200) }
        assertTrue(store.projects.keys.none { it.startsWith("b1/") })
    }

    @Test
    fun anUnverifiedEmailSeesNoShares() {
        val store = FakeStore().also { ownerShares(it, canEdit = true) }
        val device = Device(FakeCloud(store, bobUser.copy(emailVerified = false)))
        val sync = ProjectSync(device, device.cloud)
        sync.syncAll()
        runBlocking { delay(300) }
        assertTrue(sync.shared.value.isEmpty())
        assertFalse(device.storage.exists("project_Kitchen_(owner).json"))
    }

    @Test
    fun theOwnerSharingUploadsFirstThenWritesTheList() {
        val store = FakeStore()
        val ownerDevice = Device(FakeCloud(store, CloudUser("o1", "owner@x.com", emailVerified = true)))
        val sync = ProjectSync(ownerDevice, ownerDevice.cloud)
        ownerDevice.storage.write("project_Deck.json", """{"projectName":"Deck"}""")
        runBlocking {
            val sharing = Sharing.add(sync.sharingFor("Deck"), "bob@x.com", canEdit = false)
            sync.updateSharing("Deck", sharing)
        }
        assertEquals(listOf("o1/Deck"), store.writes)
        assertEquals(listOf("bob@x.com"), store.sharing["o1/Deck"]!!.memberEmails)
        assertEquals("owner@x.com", store.sharing["o1/Deck"]!!.ownerEmail)
    }
}

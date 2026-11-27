package com.example.sitedraft

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.sitedraft.cloud.ClaudeCommandDoc
import com.example.sitedraft.cloud.ClaudePresence
import com.example.sitedraft.cloud.CloudService
import com.example.sitedraft.cloud.CloudStore
import com.example.sitedraft.cloud.CloudUser
import com.example.sitedraft.cloud.ProjectDoc
import com.example.sitedraft.cloud.ProjectMeta
import com.example.sitedraft.cloud.ProjectSharing
import com.example.sitedraft.cloud.SharedProjectMeta
import com.example.sitedraft.cloud.UserDoc
import com.example.sitedraft.platform.InMemoryPrefs
import com.example.sitedraft.platform.InMemoryStorage
import com.example.sitedraft.platform.KeyValuePrefs
import com.example.sitedraft.platform.PageSpec
import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.ProjectStorage
import com.example.sitedraft.ui.canvas.ClaudeCommands
import com.example.sitedraft.ui.canvas.DrawingElement
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import com.example.sitedraft.ui.canvas.DrawingTool
import com.example.sitedraft.ui.canvas.FloorPlan
import com.example.sitedraft.ui.canvas.Placement
import com.example.sitedraft.ui.canvas.PlanSymbols
import com.example.sitedraft.ui.cloud.ClaudeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Claude's commands: the app's own events, sent as JSON and run by the open app (ClaudeCommands, ClaudeBridge). */
class ClaudeCommandsTest {
    @Test
    fun `partial building settings keep the rest of the job's inputs`() {
        val current = com.example.sitedraft.ui.canvas.CanvasState(building = com.example.sitedraft.ui.canvas.BuildingSettings(
            blockCourses = 9, autoLayers = true, joistDepthIn = 11.25f))
        val ev = ClaudeCommands.decodeAll("""[{"action":"SetBuilding","args":{"settings":{"doorHeadIn":96}}}]""", current).single()
        val b = (ev as DrawingEvent.SetBuilding).settings
        assertEquals(96f, b.doorHeadIn, 0.001f)
        assertEquals(9, b.blockCourses)
        assertTrue(b.autoLayers)
        assertEquals(11.25f, b.joistDepthIn, 0.001f)
    }


    private fun decode(json: String): DrawingEvent =
        ClaudeCommands.decode(ClaudeCommands.json.decodeFromString(ClaudeCommands.Action.serializer(), json))

    @Test
    fun `every event the app has is readable from JSON and listed for Claude`() {
        val names = ClaudeCommands.allNames
        assertTrue("found the app's events", names.size >= 90)
        for (n in listOf("AddWall", "PlaceAt", "SelectSymbol", "SetBuilding", "UpdateElementProperty", "DimensionPlan", "Undo")) {
            assertTrue(n, n in names)
        }
        val listed = ClaudeCommands.catalog().map { it.jsonObject["action"]!!.jsonPrimitive.content }
        assertEquals(names.filter { it !in ClaudeCommands.notForClaude }, listed)
        assertFalse("files are not sent this way", "ImportPdf" in listed)
        // Each listed action says its arguments.
        val addWall = ClaudeCommands.catalog().first { it.jsonObject["action"]!!.jsonPrimitive.content == "AddWall" }.jsonObject
        assertEquals("lengthPx", addWall["args"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `commands become the app's events`() {
        assertEquals(DrawingEvent.AddWall(120f), decode("""{"action": "AddWall", "args": {"lengthPx": 120}}"""))
        assertEquals(DrawingEvent.Undo, decode("""{"action": "Undo"}"""))
        assertEquals(DrawingEvent.SelectTool(DrawingTool.SELECT), decode("""{"action": "SelectTool", "args": {"tool": "SELECT"}}"""))
        val at = decode("""{"action": "PlaceAt", "args": {"point": "10.5,20"}}""") as DrawingEvent.PlaceAt
        assertEquals(10.5f, at.point.x)
        assertEquals(20f, at.point.y)
        val room = decode(
            """{"action": "StartPlacement", "args": {"placement": {"type": "com.example.sitedraft.ui.canvas.Placement.Room",
               "name": "Kitchen", "width": 12, "depth": 10, "wallName": "2x4 Wall", "label": "Kitchen"}}}"""
        ) as DrawingEvent.StartPlacement
        assertEquals(Placement.Room("Kitchen", 12f, 10f, "2x4 Wall", "Kitchen"), room.placement)
        // The long name works too.
        assertEquals(DrawingEvent.ZoomToFit, decode("""{"action": "${ClaudeCommands.PREFIX}ZoomToFit"}"""))
    }

    @Test
    fun `wrong commands say why, and a batch with one wrong command runs nothing`() {
        fun reason(json: String): String = try {
            decode(json); fail("should not decode"); ""
        } catch (e: IllegalArgumentException) {
            e.message ?: ""
        }
        assertTrue(reason("""{"action": "MakeCoffee"}""").contains("Unknown action"))
        assertTrue(reason("""{"action": "ImportPdf", "args": {}}""").contains("isn't available"))
        assertTrue(reason("""{"action": "AddWall", "args": {}}""").startsWith("'AddWall'"))
        try {
            ClaudeCommands.decodeAll("""[{"action": "Undo"}, {"action": "AddWall", "args": {"lengthPx": "x"}}]""")
            fail("should not decode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.startsWith("Action 2:"))
        }
    }

    @Test
    fun `the app runs a batch through its own code and reports what changed`() {
        val manager = DrawingManager()
        val events = ClaudeCommands.decodeAll(
            """[{"action": "SelectSymbol", "args": {"name": "${FloorPlan.INTERIOR_WALL}", "category": "${PlanSymbols.WALLS}"}},
                {"action": "PlaceAt", "args": {"point": "200,200"}}]"""
        )
        val o = manager.runForClaude(events)
        assertEquals(2, o.ran)
        assertNull(o.error)
        val r = ClaudeCommands.result(o, events.size, saved = false, device = "test")
        val added = r["added"]!!.jsonArray
        assertEquals(1, added.size)
        assertEquals(FloorPlan.INTERIOR_WALL, added[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(2, r["ran"]!!.jsonPrimitive.int)
        assertTrue(manager.state.value.elements.any { it is DrawingElement.Symbol && it.symbolName == FloorPlan.INTERIOR_WALL })
    }

    // ------------------------------------------------------------------ the bridge

    private class FakeStore : CloudStore {
        val presence = HashMap<String, ClaudePresence>()
        val pending = HashMap<String, String>()
        val commands = HashMap<String, ClaudeCommandDoc>()
        val results = HashMap<String, Pair<String, String?>>()
        override suspend fun readUser(uid: String): UserDoc? = null
        override suspend fun touchUser(uid: String, email: String?, displayName: String?, device: String) {}
        override suspend fun isAllowlisted(email: String) = false
        override suspend fun listProjects(uid: String) = emptyList<ProjectMeta>()
        override suspend fun readProject(uid: String, name: String): ProjectDoc? = null
        override suspend fun writeProject(uid: String, name: String, json: String, updatedAt: Long, device: String) {}
        override suspend fun deleteProject(uid: String, name: String) {}
        override suspend fun readSharing(ownerUid: String, name: String): ProjectSharing? = null
        override suspend fun writeSharing(ownerUid: String, name: String, sharing: ProjectSharing) {}
        override suspend fun listSharedWithMe(email: String) = emptyList<SharedProjectMeta>()
        override suspend fun redeemAccessCode(uid: String, code: String) {}
        override suspend fun isAccessCodeUsable(code: String) = false
        override suspend fun writeClaudePresence(uid: String, deviceId: String, presence: ClaudePresence) { this.presence[deviceId] = presence }
        override suspend fun claudePendingCommand(uid: String, deviceId: String): String? = pending[deviceId]
        override suspend fun readClaudeCommand(uid: String, id: String): ClaudeCommandDoc? = commands[id]
        override suspend fun updateClaudeCommand(uid: String, id: String, status: String, result: String?, at: Long, device: String) {
            commands[id] = commands[id]!!.copy(status = status)
            results[id] = status to result
        }
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
        override val name = "Desktop"
        override val storage: ProjectStorage = InMemoryStorage()
        override val prefs: KeyValuePrefs = InMemoryPrefs()
        override fun exportPage(fileName: String, page: PageSpec, draw: DrawScope.() -> Unit): String? = null
        override fun exportText(fileName: String, text: String): String? = null
    }

    @Test
    fun `the copy the connector picked runs the command once and answers`() = runBlocking {
        val cloud = FakeCloud()
        val manager = DrawingManager()
        val bridge = ClaudeBridge(TestPlatform(cloud), cloud, manager, { null }, onMain = { it() })
        val me = bridge.deviceId
        val project = manager.state.value.projectName

        // Nothing left for it: it only says it's open.
        assertFalse(bridge.tick())
        assertEquals(project, cloud.store.presence[me]!!.project)

        cloud.store.commands["c1"] = ClaudeCommandDoc("c1", project, me, "pending", """[{"action": "ZoomToFit"}, {"action": "Undo"}]""")
        cloud.store.pending[me] = "c1"
        assertTrue(bridge.tick())
        val (status, result) = cloud.store.results["c1"]!!
        assertEquals("done", status)
        val r = Json.parseToJsonElement(result!!).jsonObject
        assertEquals(2, r["ran"]!!.jsonPrimitive.int)
        assertEquals("Desktop", r["device"]!!.jsonPrimitive.content)
        // Not run twice.
        assertFalse(bridge.tick())

        // A command meant for another copy is left alone.
        cloud.store.commands["c2"] = ClaudeCommandDoc("c2", project, "someone-else", "pending", """[{"action": "Undo"}]""")
        cloud.store.pending[me] = "c2"
        assertFalse(bridge.tick())
        assertNull(cloud.store.results["c2"])

        // A wrong batch runs nothing and says why.
        cloud.store.commands["c3"] = ClaudeCommandDoc("c3", project, me, "pending", """[{"action": "NoSuchThing"}]""")
        cloud.store.pending[me] = "c3"
        assertTrue(bridge.tick())
        assertEquals("error", cloud.store.results["c3"]!!.first)
        assertNotNull(Json.parseToJsonElement(cloud.store.results["c3"]!!.second!!).jsonObject["error"])
    }
}

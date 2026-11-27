package com.example.sitedraft.ui.cloud

import com.example.sitedraft.cloud.ClaudeCommandDoc
import com.example.sitedraft.cloud.ClaudePresence
import com.example.sitedraft.cloud.CloudService
import com.example.sitedraft.cloud.ProjectSync
import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.ioDispatcher
import com.example.sitedraft.platform.nowMillis
import com.example.sitedraft.platform.randomId
import com.example.sitedraft.ui.canvas.ClaudeCommands
import com.example.sitedraft.ui.canvas.DrawingEvent
import com.example.sitedraft.ui.canvas.DrawingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The app runs Claude's commands (Dan, Nov 25 2026). The Site Draft connector does not edit the
 * drawing itself for these: it leaves a batch of the app's own events in
 * `users/{uid}/claudeCommands/{id}` and points ONE open copy at it (`pendingCommand` on that copy's
 * `users/{uid}/claudeDevices/{deviceId}`: the copy with the project open that was used most
 * recently, from the presence each copy writes here). That copy runs the events through
 * [DrawingManager.runForClaude] - the same code a tap runs - saves, waits for the cloud copy, and
 * writes back what ran, what the app said and what changed. Any open copy (desktop, Android, web)
 * takes part; only the one the connector picked runs a command, so nothing runs twice.
 */
class ClaudeBridge(
    private val platform: Platform,
    private val cloud: CloudService,
    private val manager: DrawingManager,
    private val sync: () -> ProjectSync?,
    /** Runs [block] on the UI thread (the drawing's state lives there). Tests pass a direct call. */
    private val onMain: suspend (block: () -> Unit) -> Unit = { block -> withContext(Dispatchers.Main) { block() } },
) {
    companion object {
        const val DEVICE_ID_KEY = "claude_device_id"
        /** How often this copy looks for a command while Claude is working, and when it isn't. */
        const val ACTIVE_POLL_MS = 1_500L
        const val IDLE_POLL_MS = 6_000L
        /** "Claude is working" for this long after the last command. */
        const val ACTIVE_WINDOW_MS = 180_000L
        /** Presence is written at least this often (the connector treats older copies as closed). */
        const val PRESENCE_EVERY_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var job: Job? = null

    /** This install's id, kept in its prefs. */
    val deviceId: String by lazy {
        platform.prefs.getString(DEVICE_ID_KEY)?.takeIf { it.isNotBlank() }
            ?: randomId().also { platform.prefs.putString(DEVICE_ID_KEY, it) }
    }

    private var lastPresenceAt = 0L
    private var lastPresenceProject: String? = null
    private var lastPresenceActive = 0L
    private var lastHandled: String? = null
    private var lastCommandAt = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    platform.log("ClaudeBridge", "check failed: ${e.message}")
                }
                delay(if (nowMillis() - lastCommandAt < ACTIVE_WINDOW_MS) ACTIVE_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    fun dispose() {
        job?.cancel()
        job = null
    }

    /** One pass: presence when due, then any command left for this copy. True when one ran. */
    suspend fun tick(): Boolean {
        val uid = cloud.user.value?.uid ?: return false
        val project = manager.openProjectName() ?: return false
        val now = nowMillis()
        val active = manager.lastUserActivityAt
        if (project != lastPresenceProject || now - lastPresenceAt >= PRESENCE_EVERY_MS || active - lastPresenceActive >= 20_000L) {
            cloud.store.writeClaudePresence(uid, deviceId, ClaudePresence(platform.name, project, active, now))
            lastPresenceAt = now
            lastPresenceProject = project
            lastPresenceActive = active
        }
        val id = cloud.store.claudePendingCommand(uid, deviceId) ?: return false
        if (id == lastHandled) return false
        val cmd = cloud.store.readClaudeCommand(uid, id)
        if (cmd == null || cmd.status != "pending" || (cmd.target.isNotEmpty() && cmd.target != deviceId)) {
            lastHandled = id
            return false
        }
        lastHandled = id
        lastCommandAt = now
        cloud.store.updateClaudeCommand(uid, id, "running", null, nowMillis(), platform.name)
        val (status, result) = run(cmd)
        cloud.store.updateClaudeCommand(uid, id, status, result, nowMillis(), platform.name)
        lastCommandAt = nowMillis()
        return true
    }

    private fun fail(message: String): Pair<String, String> =
        "error" to buildJsonObject { put("device", platform.name); put("error", message); put("ran", 0) }.toString()

    /** Runs one batch; ("done" | "error", result JSON). */
    suspend fun run(cmd: ClaudeCommandDoc): Pair<String, String> {
        val events: List<DrawingEvent> = try {
            ClaudeCommands.decodeAll(cmd.actions)
        } catch (e: Exception) {
            return fail(e.message ?: "The actions could not be read.")
        }
        if (cmd.project.isNotEmpty() && cmd.project != manager.openProjectName()) {
            var opened = false
            onMain { opened = manager.openForClaude(cmd.project) }
            if (!opened) return fail("Site Draft on ${platform.name} has '${manager.openProjectName()}' open and has no project named '${cmd.project}'.")
        }
        var outcome: ClaudeCommands.RunOutcome? = null
        onMain {
            // Decoded again against the open project, so settings Claude only partly sends keep the rest (Nov 25).
            val merged = try { ClaudeCommands.decodeAll(cmd.actions, manager.state.value) } catch (e: Exception) { events }
            outcome = manager.runForClaude(merged)
        }
        val o = outcome ?: return fail("The commands did not run.")
        manager.saveForClaude()
        val saved = sync()?.awaitPushed(o.after.projectName) ?: false
        val result = ClaudeCommands.result(o, events.size, saved, platform.name)
        return (if (o.error == null) "done" else "error") to result.toString()
    }
}

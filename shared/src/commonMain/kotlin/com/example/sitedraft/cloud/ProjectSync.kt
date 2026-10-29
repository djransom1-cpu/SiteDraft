package com.example.sitedraft.cloud

import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.ProjectStorage
import com.example.sitedraft.platform.ioDispatcher
import com.example.sitedraft.platform.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What to do about one project after comparing the local and cloud copies. */
enum class SyncAction { PULL, PUSH, NONE }

/**
 * Last-write-wins. `null` means "that side has no copy". Equal timestamps mean the copies are
 * the same write, so nothing moves. Clocks are the devices' own; that is the accepted cost of
 * last-write-wins.
 */
fun chooseSyncAction(localUpdatedAt: Long?, remoteUpdatedAt: Long?): SyncAction = when {
    localUpdatedAt == null && remoteUpdatedAt == null -> SyncAction.NONE
    localUpdatedAt == null -> SyncAction.PULL
    remoteUpdatedAt == null -> SyncAction.PUSH
    remoteUpdatedAt > localUpdatedAt -> SyncAction.PULL
    localUpdatedAt > remoteUpdatedAt -> SyncAction.PUSH
    else -> SyncAction.NONE
}

/**
 * Firestore documents hold at most 1 MiB; a project with embedded photos is bigger. The JSON
 * is cut into chunks of [CHUNK_CHARS] characters (worst case 3 bytes each in UTF-8, so a
 * chunk always fits with room for the other fields) and the first chunk lives on the project
 * document itself, the rest under `chunks/{index}`.
 */
object ProjectChunks {
    const val CHUNK_CHARS = 300_000

    fun split(json: String): List<String> {
        if (json.isEmpty()) return listOf("")
        val out = ArrayList<String>((json.length + CHUNK_CHARS - 1) / CHUNK_CHARS)
        var i = 0
        while (i < json.length) {
            out += json.substring(i, minOf(i + CHUNK_CHARS, json.length))
            i += CHUNK_CHARS
        }
        return out
    }

    fun join(chunks: List<String>): String = chunks.joinToString("")
}

/** Per-project sync bookkeeping kept next to the project file as `sync_<file>.json`. */
@Serializable
data class SyncMeta(
    /** When the local copy was last written, by this device's clock. */
    val updatedAt: Long = 0L,
    /** The cloud copy's updatedAt as last seen; pushes and pulls set it equal to [updatedAt]. */
    val remoteUpdatedAt: Long? = null,
    /** True when a push failed and must be retried. */
    val pendingPush: Boolean = false,
)

/** The listener the sync engine uses to tell the drawing side that a project changed on disk. */
interface ProjectSyncListener {
    /** The local file for [name] was replaced with the cloud copy. */
    fun onProjectPulled(name: String)
    /** The list of local projects changed (new ones arrived from the cloud). */
    fun onProjectListChanged()
}

/**
 * Keeps `users/{uid}/projects/{name}` and the local project files in step. Local files remain
 * the source of truth for the drawing; the cloud copy is pushed after every save and pulled
 * at launch, at sign-in and whenever a project is opened. All I/O happens off the main thread.
 */
class ProjectSync(
    private val platform: Platform,
    private val cloud: CloudService = platform.cloud,
    private val listener: ProjectSyncListener? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Mutex()
    private var pullJob: Job? = null

    private val storage: ProjectStorage? get() = platform.storage
    private val uid: String? get() = cloud.user.value?.uid

    companion object {
        const val PROJECT_LIST_FILE = "project_list.txt"
        fun projectFileName(name: String) = "project_${name.replace(" ", "_")}.json"
        fun metaFileName(name: String) = "sync_${name.replace(" ", "_")}.json"
    }

    fun readMeta(name: String): SyncMeta? =
        storage?.read(metaFileName(name))?.let { runCatching { json.decodeFromString(SyncMeta.serializer(), it) }.getOrNull() }

    private fun writeMeta(name: String, meta: SyncMeta) {
        storage?.write(metaFileName(name), json.encodeToString(SyncMeta.serializer(), meta))
    }

    /**
     * The local copy's timestamp for the merge rule: the sync meta when there is one, else 0
     * for a project saved before sync existed (so an existing cloud copy wins, and a missing
     * one gets this copy pushed), else null when there is no local file at all.
     */
    private fun localUpdatedAt(name: String): Long? {
        val st = storage ?: return null
        if (!st.exists(projectFileName(name))) return null
        return readMeta(name)?.updatedAt ?: 0L
    }

    /** Called by the drawing side right after it wrote [json] for [name] locally. */
    fun onLocalSaved(name: String, json: String, savedAt: Long = nowMillis()) {
        val previous = readMeta(name)
        writeMeta(name, SyncMeta(updatedAt = savedAt, remoteUpdatedAt = previous?.remoteUpdatedAt, pendingPush = true))
        val user = uid ?: return
        scope.launch { push(user, name, json, savedAt) }
    }

    private suspend fun push(uid: String, name: String, json: String, updatedAt: Long) = lock.withLock {
        try {
            cloud.store.writeProject(uid, name, json, updatedAt, platform.name)
            writeMeta(name, SyncMeta(updatedAt = updatedAt, remoteUpdatedAt = updatedAt, pendingPush = false))
        } catch (e: Exception) {
            platform.log("SiteDraftSync", "push of '$name' failed: ${e.message}")
        }
    }

    /** Pulls or pushes every project once; safe to call again (a running pass is replaced). */
    fun syncAll() {
        val user = uid ?: return
        pullJob?.cancel()
        pullJob = scope.launch { syncAllNow(user) }
    }

    /** Pulls one project if the cloud copy is newer; the drawing side calls this when opening it. */
    fun syncProject(name: String) {
        val user = uid ?: return
        scope.launch { syncOne(user, name, remoteUpdatedAt = null, knownRemote = false) }
    }

    private suspend fun syncAllNow(uid: String) {
        val st = storage ?: return
        val remote: List<ProjectMeta> = try {
            cloud.store.listProjects(uid)
        } catch (e: Exception) {
            platform.log("SiteDraftSync", "list failed: ${e.message}")
            return
        }
        val localNames = st.read(PROJECT_LIST_FILE)?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val remoteByName = remote.associateBy { it.name }
        var listChanged = false
        for (meta in remote) {
            val existedLocally = st.exists(projectFileName(meta.name))
            syncOne(uid, meta.name, meta.updatedAt, knownRemote = true)
            if (!existedLocally && st.exists(projectFileName(meta.name))) listChanged = true
        }
        for (name in localNames - remoteByName.keys) {
            val text = st.read(projectFileName(name)) ?: continue
            val at = readMeta(name)?.updatedAt?.takeIf { it > 0 } ?: nowMillis()
            push(uid, name, text, at)
        }
        if (listChanged) {
            val merged = (localNames + remoteByName.keys).sorted()
            st.write(PROJECT_LIST_FILE, merged.joinToString("\n"))
            withContext(Dispatchers.Main) { listener?.onProjectListChanged() }
        }
    }

    /**
     * [remoteUpdatedAt] is the listed timestamp when known; otherwise the document is read
     * first. [knownRemote] false means "may not exist in the cloud".
     */
    private suspend fun syncOne(uid: String, name: String, remoteUpdatedAt: Long?, knownRemote: Boolean) {
        val st = storage ?: return
        var remoteAt = remoteUpdatedAt
        var remoteDoc: ProjectDoc? = null
        if (remoteAt == null) {
            remoteDoc = try {
                cloud.store.readProject(uid, name)
            } catch (e: Exception) {
                platform.log("SiteDraftSync", "read of '$name' failed: ${e.message}")
                return
            }
            remoteAt = remoteDoc?.updatedAt
            if (remoteDoc == null && knownRemote) return
        }
        val localAt = localUpdatedAt(name)
        when (chooseSyncAction(localAt, remoteAt)) {
            SyncAction.PULL -> {
                val doc = remoteDoc ?: try {
                    cloud.store.readProject(uid, name) ?: return
                } catch (e: Exception) {
                    platform.log("SiteDraftSync", "read of '$name' failed: ${e.message}")
                    return
                }
                var keptCopy: Pair<String, String>? = null
                lock.withLock {
                    keptCopy = keepNeverSyncedLocalCopy(name, doc.json)
                    st.write(projectFileName(name), doc.json)
                    writeMeta(name, SyncMeta(updatedAt = doc.updatedAt, remoteUpdatedAt = doc.updatedAt, pendingPush = false))
                    val list = st.read(PROJECT_LIST_FILE)?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                    val extra = listOfNotNull(name, keptCopy?.first)
                    if (!list.containsAll(extra)) st.write(PROJECT_LIST_FILE, (list + extra).sorted().joinToString("\n"))
                }
                keptCopy?.let { (copyName, copyJson) -> push(uid, copyName, copyJson, readMeta(copyName)?.updatedAt ?: nowMillis()) }
                withContext(Dispatchers.Main) {
                    listener?.onProjectPulled(name)
                    if (keptCopy != null) listener?.onProjectListChanged()
                }
            }
            SyncAction.PUSH -> {
                val text = st.read(projectFileName(name)) ?: return
                val at = localAt?.takeIf { it > 0 } ?: nowMillis()
                push(uid, name, text, at)
            }
            SyncAction.NONE -> {
                // Same write on both sides; make sure a stale pendingPush flag does not linger.
                val meta = readMeta(name)
                if (meta != null && meta.pendingPush) writeMeta(name, meta.copy(pendingPush = false, remoteUpdatedAt = remoteAt))
            }
        }
    }

    /**
     * A project saved before sync existed has no sync meta. If the cloud already holds a
     * different project under the same name (two devices both had "Untitled Project"), plain
     * last-write-wins would overwrite this device's drawing. Instead it is kept as a new
     * project, "<name> (<device>)", with projectName inside the JSON renamed to match, and
     * uploaded. Returns (copy name, copy JSON), or null when nothing needed keeping.
     * Called under [lock].
     */
    private fun keepNeverSyncedLocalCopy(name: String, incomingJson: String): Pair<String, String>? {
        val st = storage ?: return null
        if (readMeta(name) != null) return null
        val localJson = st.read(projectFileName(name)) ?: return null
        if (localJson == incomingJson) return null
        val base = "$name (${platform.name})"
        var copyName = base
        var n = 2
        while (st.exists(projectFileName(copyName))) copyName = "$base ${n++}"
        val renamed = renameProjectJson(localJson, copyName)
        st.write(projectFileName(copyName), renamed)
        writeMeta(copyName, SyncMeta(updatedAt = nowMillis(), pendingPush = true))
        platform.log("SiteDraftSync", "kept the local '$name' as '$copyName' instead of overwriting it")
        return copyName to renamed
    }

    /** The project JSON with its projectName field set to [newName]; unchanged if it will not parse. */
    private fun renameProjectJson(text: String, newName: String): String = try {
        val obj = json.parseToJsonElement(text) as JsonObject
        prettyJson.encodeToString(JsonObject.serializer(), JsonObject(obj + ("projectName" to JsonPrimitive(newName))))
    } catch (e: Exception) {
        text
    }

    private val prettyJson = Json { prettyPrint = true }

    fun dispose() {
        pullJob?.cancel()
    }
}

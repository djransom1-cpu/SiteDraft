package com.example.sitedraft.cloud

import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.ProjectStorage
import com.example.sitedraft.platform.ioDispatcher
import com.example.sitedraft.platform.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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

/**
 * A project another account shared with this user, as it is kept on this device. The local
 * file is `project_<localName>.json`; the cloud copy is `users/{ownerUid}/projects/{cloudName}`.
 * The `projectName` inside the JSON is [localName] on this device and [cloudName] in the cloud,
 * so the owner's devices never save it under the wrong name.
 */
@Serializable
data class SharedRef(
    val localName: String,
    val ownerUid: String,
    val ownerEmail: String? = null,
    val cloudName: String,
    val canEdit: Boolean = false,
)

/** The listener the sync engine uses to tell the drawing side that a project changed on disk. */
interface ProjectSyncListener {
    /** The local file for [name] was replaced with the cloud copy. */
    fun onProjectPulled(name: String)
    /** The list of local projects changed (new ones arrived from the cloud). */
    fun onProjectListChanged()
}

/**
 * Keeps the local project files in step with the cloud: the user's own projects at
 * `users/{uid}/projects/{name}`, and projects other accounts shared with them. Local files remain
 * the source of truth for the drawing; the cloud copy is pushed after every save and pulled
 * at launch, at sign-in and whenever a project is opened. All I/O happens off the main thread.
 */
class ProjectSync(
    private val platform: Platform,
    private val cloud: CloudService = platform.cloud,
    private val listener: ProjectSyncListener? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Mutex()
    private var pullJob: Job? = null

    private val storage: ProjectStorage? get() = platform.storage
    private val uid: String? get() = cloud.user.value?.uid

    companion object {
        const val PROJECT_LIST_FILE = "project_list.txt"
        const val SHARED_FILE = "shared_projects.json"
        fun projectFileName(name: String) = "project_${name.replace(" ", "_")}.json"
        fun metaFileName(name: String) = "sync_${name.replace(" ", "_")}.json"
    }

    private val _shared = MutableStateFlow(loadShared())
    /** Projects shared with this user that are known on this device. */
    val shared: StateFlow<List<SharedRef>> = _shared

    fun sharedRef(localName: String): SharedRef? = _shared.value.firstOrNull { it.localName == localName }

    private fun loadShared(): List<SharedRef> =
        storage?.read(SHARED_FILE)?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(ListSerializer(SharedRef.serializer()), it) }.getOrNull() }
            ?: emptyList()

    private fun saveShared(refs: List<SharedRef>) {
        storage?.write(SHARED_FILE, json.encodeToString(ListSerializer(SharedRef.serializer()), refs))
        _shared.value = refs
    }

    fun readMeta(name: String): SyncMeta? =
        storage?.read(metaFileName(name))?.let { runCatching { json.decodeFromString(SyncMeta.serializer(), it) }.getOrNull() }

    private fun writeMeta(name: String, meta: SyncMeta) {
        storage?.write(metaFileName(name), json.encodeToString(SyncMeta.serializer(), meta))
    }

    private fun readList(): Set<String> =
        storage?.read(PROJECT_LIST_FILE)?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun writeList(names: Set<String>) {
        storage?.write(PROJECT_LIST_FILE, names.sorted().joinToString("\n"))
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
        val ref = sharedRef(name)
        val previous = readMeta(name)
        val willPush = ref == null || ref.canEdit
        writeMeta(name, SyncMeta(updatedAt = savedAt, remoteUpdatedAt = previous?.remoteUpdatedAt, pendingPush = willPush))
        val user = uid ?: return
        if (!willPush) return // view-only: the change stays on this device
        scope.launch {
            if (ref == null) push(user, name, json, savedAt, localName = name)
            else push(ref.ownerUid, ref.cloudName, renameProjectJson(json, ref.cloudName), savedAt, localName = name)
        }
    }

    /** Writes one project to the cloud and records it in the local meta of [localName]. */
    private suspend fun push(ownerUid: String, cloudName: String, json: String, updatedAt: Long, localName: String) = lock.withLock {
        try {
            cloud.store.writeProject(ownerUid, cloudName, json, updatedAt, platform.name)
            writeMeta(localName, SyncMeta(updatedAt = updatedAt, remoteUpdatedAt = updatedAt, pendingPush = false))
        } catch (e: Exception) {
            platform.log("SiteDraftSync", "push of '$cloudName' failed: ${e.message}")
        }
    }

    /** Pulls or pushes every project once; safe to call again (a running pass is replaced). */
    fun syncAll() {
        val user = uid ?: return
        pullJob?.cancel()
        pullJob = scope.launch {
            syncAllNow(user)
            syncSharedNow()
        }
    }

    /** Pulls one project if the cloud copy is newer; the drawing side calls this when opening it. */
    fun syncProject(name: String) {
        val user = uid ?: return
        val ref = sharedRef(name)
        scope.launch {
            if (ref != null) syncShared(ref, remoteUpdatedAt = null) else syncOne(user, name, remoteUpdatedAt = null, knownRemote = false)
        }
    }

    /**
     * Fetches a shared project now (for opening it from the "Shared with me" list) and returns
     * an error for the user, or null when the local copy is ready.
     */
    suspend fun fetchShared(localName: String): String? = withContext(ioDispatcher) {
        val ref = sharedRef(localName) ?: return@withContext "That project is no longer shared with you."
        syncShared(ref, remoteUpdatedAt = null)
        if (storage?.exists(projectFileName(localName)) == true) null else "Could not download '${ref.cloudName}'. Check your connection."
    }

    private suspend fun syncAllNow(uid: String) {
        val st = storage ?: return
        val remote: List<ProjectMeta> = try {
            cloud.store.listProjects(uid)
        } catch (e: Exception) {
            platform.log("SiteDraftSync", "list failed: ${e.message}")
            return
        }
        val sharedNames = _shared.value.map { it.localName }.toSet()
        val localNames = readList() - sharedNames
        val remoteByName = remote.associateBy { it.name }
        var listChanged = false
        for (meta in remote) {
            val existedLocally = st.exists(projectFileName(meta.name))
            if (meta.name in sharedNames) continue // an own project may not shadow a share's local name
            syncOne(uid, meta.name, meta.updatedAt, knownRemote = true)
            if (!existedLocally && st.exists(projectFileName(meta.name))) listChanged = true
        }
        for (name in localNames - remoteByName.keys) {
            val text = st.read(projectFileName(name)) ?: continue
            val at = readMeta(name)?.updatedAt?.takeIf { it > 0 } ?: nowMillis()
            push(uid, name, text, at, localName = name)
        }
        if (listChanged) {
            lock.withLock { writeList(readList() + remoteByName.keys.filter { st.exists(projectFileName(it)) }) }
            withContext(Dispatchers.Main) { listener?.onProjectListChanged() }
        }
    }

    /**
     * Refreshes the "shared with me" list and syncs every share. A share the owner withdrew is
     * dropped from the project list (the file stays on disk, nothing is deleted). Needs a
     * verified email: the security rules hide shares from unverified accounts.
     */
    private suspend fun syncSharedNow() {
        val user = cloud.user.value ?: return
        val email = user.email?.let(Sharing::normalize) ?: return
        if (!user.emailVerified) return
        val found: List<SharedProjectMeta> = try {
            cloud.store.listSharedWithMe(email).filter { it.ownerUid != user.uid }
        } catch (e: Exception) {
            platform.log("SiteDraftSync", "shared list failed: ${e.message}")
            return
        }
        var listChanged = false
        val refs: List<SharedRef> = lock.withLock {
            val previous = _shared.value
            val taken = readList().toMutableSet()
            val next = ArrayList<SharedRef>()
            for (meta in found) {
                val existing = previous.firstOrNull { it.ownerUid == meta.ownerUid && it.cloudName == meta.name }
                if (existing != null) {
                    next += existing.copy(canEdit = meta.canEdit, ownerEmail = meta.ownerEmail ?: existing.ownerEmail)
                } else {
                    val localName = sharedLocalName(meta.name, meta.ownerEmail, taken + next.map { it.localName })
                    next += SharedRef(localName, meta.ownerUid, meta.ownerEmail, meta.name, meta.canEdit)
                }
            }
            val revoked = previous.filter { old -> next.none { it.localName == old.localName } }.map { it.localName }.toSet()
            if (revoked.isNotEmpty()) {
                writeList(readList() - revoked)
                listChanged = true
            }
            saveShared(next)
            next
        }
        val metaByKey = found.associateBy { it.ownerUid to it.name }
        for (ref in refs) {
            val had = storage?.exists(projectFileName(ref.localName)) == true && ref.localName in readList()
            syncShared(ref, metaByKey[ref.ownerUid to ref.cloudName]?.updatedAt)
            if (!had && storage?.exists(projectFileName(ref.localName)) == true) listChanged = true
        }
        if (listChanged) withContext(Dispatchers.Main) { listener?.onProjectListChanged() }
    }

    private suspend fun syncShared(ref: SharedRef, remoteUpdatedAt: Long?) {
        val st = storage ?: return
        var remoteAt = remoteUpdatedAt
        var doc: ProjectDoc? = null
        if (remoteAt == null) {
            doc = try {
                cloud.store.readProject(ref.ownerUid, ref.cloudName)
            } catch (e: Exception) {
                platform.log("SiteDraftSync", "read of shared '${ref.cloudName}' failed: ${e.message}")
                return
            } ?: return
            remoteAt = doc.updatedAt
        }
        when (chooseSharedSyncAction(ref.canEdit, localUpdatedAt(ref.localName), remoteAt)) {
            SyncAction.PULL -> {
                val pulled = doc ?: try {
                    cloud.store.readProject(ref.ownerUid, ref.cloudName) ?: return
                } catch (e: Exception) {
                    platform.log("SiteDraftSync", "read of shared '${ref.cloudName}' failed: ${e.message}")
                    return
                }
                lock.withLock {
                    st.write(projectFileName(ref.localName), renameProjectJson(pulled.json, ref.localName))
                    writeMeta(ref.localName, SyncMeta(updatedAt = pulled.updatedAt, remoteUpdatedAt = pulled.updatedAt))
                    val list = readList()
                    if (ref.localName !in list) writeList(list + ref.localName)
                }
                withContext(Dispatchers.Main) { listener?.onProjectPulled(ref.localName) }
            }
            SyncAction.PUSH -> {
                val text = st.read(projectFileName(ref.localName)) ?: return
                val at = localUpdatedAt(ref.localName)?.takeIf { it > 0 } ?: nowMillis()
                push(ref.ownerUid, ref.cloudName, renameProjectJson(text, ref.cloudName), at, localName = ref.localName)
            }
            SyncAction.NONE -> Unit
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
                    val list = readList()
                    val extra = listOfNotNull(name, keptCopy?.first)
                    if (!list.containsAll(extra)) writeList(list + extra)
                }
                keptCopy?.let { (copyName, copyJson) -> push(uid, copyName, copyJson, readMeta(copyName)?.updatedAt ?: nowMillis(), localName = copyName) }
                withContext(Dispatchers.Main) {
                    listener?.onProjectPulled(name)
                    if (keptCopy != null) listener?.onProjectListChanged()
                }
            }
            SyncAction.PUSH -> {
                val text = st.read(projectFileName(name)) ?: return
                val at = localAt?.takeIf { it > 0 } ?: nowMillis()
                push(uid, name, text, at, localName = name)
            }
            SyncAction.NONE -> {
                // Same write on both sides; make sure a stale pendingPush flag does not linger.
                val meta = readMeta(name)
                if (meta != null && meta.pendingPush) writeMeta(name, meta.copy(pendingPush = false, remoteUpdatedAt = remoteAt))
            }
        }
    }

    // ------------------------------------------------------------------ sharing (owner side)

    /** Who the user's own project [name] is shared with (empty when nobody). */
    suspend fun sharingFor(name: String): ProjectSharing = withContext(ioDispatcher) {
        val user = cloud.user.value ?: throw CloudException("Sign in first.")
        cloud.store.readSharing(user.uid, name) ?: ProjectSharing(user.uid, user.email?.let(Sharing::normalize))
    }

    /**
     * Saves a new sharing list for the user's own project [name]. The project is uploaded first
     * so the people it is shared with find a complete copy.
     */
    suspend fun updateSharing(name: String, sharing: ProjectSharing) = withContext(ioDispatcher) {
        val user = cloud.user.value ?: throw CloudException("Sign in first.")
        if (sharedRef(name) != null) throw CloudException("Only the owner can change who this project is shared with.")
        val text = storage?.read(projectFileName(name)) ?: throw CloudException("Save the project before sharing it.")
        val at = readMeta(name)?.updatedAt?.takeIf { it > 0 } ?: nowMillis()
        lock.withLock {
            cloud.store.writeProject(user.uid, name, text, at, platform.name)
            writeMeta(name, SyncMeta(updatedAt = at, remoteUpdatedAt = at, pendingPush = false))
        }
        cloud.store.writeSharing(user.uid, name, sharing.copy(ownerUid = user.uid, ownerEmail = user.email?.let(Sharing::normalize)))
    }

    // ------------------------------------------------------------------ helpers

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

    fun dispose() {
        pullJob?.cancel()
    }
}

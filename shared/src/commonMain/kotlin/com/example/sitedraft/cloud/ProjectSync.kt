package com.example.sitedraft.cloud

import com.example.sitedraft.platform.Platform
import com.example.sitedraft.platform.ProjectStorage
import com.example.sitedraft.platform.ioDispatcher
import com.example.sitedraft.platform.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

/**
 * A project deleted on this device, kept in `deleted_projects.json`. The drawing file stays on disk (nothing is
 * erased on the device); the project just leaves the list and is never pushed again. [cloudMarked]: the cloud copy
 * carries the tombstone, so if it later shows none, it was restored on another device.
 */
@Serializable
data class DeletedLocal(val deletedAt: Long, val cloudMarked: Boolean = false)

/** One row of Recently deleted. */
data class DeletedProject(val name: String, val deletedAt: Long) {
    /** Whole days left before it is removed for good. */
    fun daysLeft(now: Long = nowMillis()): Int = ((deletedAt + ProjectSync.KEEP_DELETED_MS - now + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(0)
}

/** The listener the sync engine uses to tell the drawing side that a project changed on disk. */
interface ProjectSyncListener {
    /** The local file for [name] was replaced with the cloud copy. */
    fun onProjectPulled(name: String)
    /** The list of local projects changed (new ones arrived from the cloud). */
    fun onProjectListChanged()
    /** The project open on screen, watched for changes the Site Draft connector (Claude) makes. */
    fun openProjectName(): String? = null
    /** True while the open project has edits not yet written to disk (an autosave is pending). */
    fun hasUnsavedEdits(): Boolean = false
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
    private var watchJob: Job? = null

    private val storage: ProjectStorage? get() = platform.storage
    private val uid: String? get() = cloud.user.value?.uid

    companion object {
        const val PROJECT_LIST_FILE = "project_list.txt"
        const val SHARED_FILE = "shared_projects.json"
        const val DELETED_FILE = "deleted_projects.json"
        /** Deleted projects stay in Recently deleted this long, then the cloud copy is removed (Dan, Nov 27: 30 days). */
        const val KEEP_DELETED_MS = 30L * 86_400_000L
        /** How often the open project is checked for a change Claude made through the connector. */
        const val WATCH_INTERVAL_MS = 5_000L
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

    private val deletedSerializer = MapSerializer(String.serializer(), DeletedLocal.serializer())

    private fun readDeleted(): Map<String, DeletedLocal> =
        storage?.read(DELETED_FILE)?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(deletedSerializer, it) }.getOrNull() } ?: emptyMap()

    private fun writeDeleted(map: Map<String, DeletedLocal>) {
        storage?.write(DELETED_FILE, json.encodeToString(deletedSerializer, map))
    }

    // ------------------------------------------------------------------ delete and restore (Dan, Nov 27)

    /**
     * Deletes the user's own project [name]: it leaves the list here, the cloud copy is marked deleted so every other
     * device takes it out of its list and never pushes it back, and it waits 30 days in Recently deleted before the
     * cloud copy is removed. The drawing file on this device is kept. Returns why it can't, or null when done.
     */
    suspend fun deleteProject(name: String): String? = withContext(ioDispatcher) {
        if (name == listener?.openProjectName()) return@withContext "\"$name\" is open. Open another job first, then delete this one."
        if (sharedRef(name) != null) return@withContext "\"$name\" is shared with you; only its owner can delete it."
        val at = nowMillis()
        lock.withLock {
            writeDeleted(readDeleted() + (name to DeletedLocal(at)))
            writeList(readList() - name)
        }
        markInCloud(name, at)
        withContext(Dispatchers.Main) { listener?.onProjectListChanged() }
        null
    }

    /** Puts the tombstone on the cloud copy (when there is one) and notes that it's there. */
    private suspend fun markInCloud(name: String, at: Long) {
        val user = uid ?: return
        try {
            // Not in the cloud (never pushed): nothing to mark yet; if it turns up there later, the next sync marks it.
            if (cloud.store.projectUpdatedAt(user, name) == null) return
            cloud.store.setProjectDeleted(user, name, at)
            lock.withLock { readDeleted()[name]?.let { writeDeleted(readDeleted() + (name to it.copy(cloudMarked = true))) } }
        } catch (e: Exception) {
            platform.log("SiteDraftSync", "marking '$name' deleted failed, will retry: ${e.message}")
        }
    }

    /** Brings [name] back from Recently deleted, on this device and (through the cloud) the others. */
    suspend fun restoreProject(name: String): String? = withContext(ioDispatcher) {
        val user = uid
        if (user != null) {
            try {
                if (cloud.store.projectUpdatedAt(user, name) != null) cloud.store.setProjectDeleted(user, name, 0L)
            } catch (e: Exception) {
                return@withContext "Couldn't restore \"$name\": ${e.message}"
            }
        }
        lock.withLock {
            writeDeleted(readDeleted() - name)
            if (storage?.exists(projectFileName(name)) == true) writeList(readList() + name)
        }
        if (user != null) syncOne(user, name, remoteUpdatedAt = null, knownRemote = false)
        withContext(Dispatchers.Main) { listener?.onProjectListChanged() }
        if (storage?.exists(projectFileName(name)) == true) null else "Couldn't download \"$name\". Check your connection."
    }

    /** Recently deleted: this device's and the cloud's deleted projects from the last 30 days, newest first. */
    suspend fun recentlyDeleted(): List<DeletedProject> = withContext(ioDispatcher) {
        val now = nowMillis()
        val out = readDeleted().mapValues { it.value.deletedAt }.toMutableMap()
        uid?.let { user ->
            runCatching { cloud.store.listProjects(user) }.getOrNull()?.forEach { m ->
                if (m.deletedAt > 0L) out[m.name] = maxOf(out[m.name] ?: 0L, m.deletedAt)
                else if (readDeleted()[m.name]?.cloudMarked == true) out.remove(m.name) // restored elsewhere
            }
        }
        out.filter { now - it.value < KEEP_DELETED_MS }.map { DeletedProject(it.key, it.value) }.sortedByDescending { it.deletedAt }
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
        // Work saved under the name of a deleted project (a job renamed to it): that work is kept, so the name comes
        // back out of Recently deleted rather than the work never reaching the cloud.
        val revived = ref == null && name in readDeleted()
        if (revived) writeDeleted(readDeleted() - name)
        writeMeta(name, SyncMeta(updatedAt = savedAt, remoteUpdatedAt = previous?.remoteUpdatedAt, pendingPush = willPush))
        val user = uid ?: return
        if (!willPush) return // view-only: the change stays on this device
        scope.launch {
            if (revived) runCatching { if (cloud.store.projectUpdatedAt(user, name) != null) cloud.store.setProjectDeleted(user, name, 0L) }
            if (ref == null) {
                // Claude changed this project in the cloud since this device last saw it: these
                // edits are kept as a copy and Claude's version is taken, so neither is lost.
                // Another device saved it since this one last saw the cloud copy (Dan, Nov 27: the desktop opened its
                // older copy and wrote over the tablet's newer one): the same, these edits kept as a copy.
                val seen = previous?.remoteUpdatedAt
                val cloudAt = try { cloud.store.projectUpdatedAt(user, name) } catch (e: Exception) { null }
                if (connectorChangedSince(user, name, seen) || (seen != null && cloudAt != null && cloudAt > seen))
                    pullConnectorChange(user, name, keepLocal = true)
                else push(user, name, json, savedAt, localName = name)
            }
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

    // ------------------------------------------------------------------ changes made by Claude

    /**
     * Checks the open project every [WATCH_INTERVAL_MS] for a change the Site Draft connector
     * made in the cloud, and reloads it (see [checkOpenProject]). Safe to call again.
     */
    fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (isActive) {
                delay(WATCH_INTERVAL_MS)
                try {
                    checkOpenProject()
                } catch (e: Exception) {
                    platform.log("SiteDraftSync", "watch failed: ${e.message}")
                }
            }
        }
    }

    /**
     * One check of the open project: when the connector's stamp is newer than the cloud copy this
     * device last saw, the cloud copy is pulled and the drawing reloads. Local edits that were not
     * in the cloud yet are first kept as "<name> (<device> edits)". Waits while an autosave is
     * pending (that save notices the change itself, in [onLocalSaved]). True when it pulled.
     */
    suspend fun checkOpenProject(): Boolean {
        val user = uid ?: return false
        val name = listener?.openProjectName() ?: return false
        if (sharedRef(name) != null) return false
        if (listener?.hasUnsavedEdits() == true) return false
        val meta = readMeta(name)
        if (!connectorChangedSince(user, name, meta?.remoteUpdatedAt)) return false
        val dirty = meta == null || meta.pendingPush || meta.updatedAt > (meta.remoteUpdatedAt ?: 0L)
        pullConnectorChange(user, name, keepLocal = dirty)
        return true
    }

    private suspend fun connectorChangedSince(uid: String, name: String, seen: Long?): Boolean {
        val stamp = try {
            cloud.store.projectStamp(uid, name)
        } catch (e: Exception) {
            null
        } ?: return false
        return stamp > (seen ?: 0L)
    }

    private suspend fun pullConnectorChange(uid: String, name: String, keepLocal: Boolean) {
        val st = storage ?: return
        val doc = try {
            cloud.store.readProject(uid, name)
        } catch (e: Exception) {
            platform.log("SiteDraftSync", "read of '$name' failed: ${e.message}")
            null
        } ?: return
        var kept: Pair<String, String>? = null
        lock.withLock {
            if (keepLocal) kept = keepLocalCopy(name, doc.json, "${platform.name} edits")
            st.write(projectFileName(name), doc.json)
            writeMeta(name, SyncMeta(updatedAt = doc.updatedAt, remoteUpdatedAt = doc.updatedAt, pendingPush = false))
            val list = readList()
            val extra = listOfNotNull(name, kept?.first)
            if (!list.containsAll(extra)) writeList(list + extra)
        }
        kept?.let { (copyName, copyJson) -> push(uid, copyName, copyJson, readMeta(copyName)?.updatedAt ?: nowMillis(), localName = copyName) }
        platform.log("SiteDraftSync", "reloaded '$name' after a change by ${doc.device ?: "the connector"}")
        withContext(Dispatchers.Main) {
            listener?.onProjectPulled(name)
            if (kept != null) listener?.onProjectListChanged()
        }
    }

    /**
     * Waits up to [timeoutMs] until the local copy of [name] is in the cloud (pushed, nothing
     * pending), so Claude's next read sees what its commands did. True when it is.
     */
    suspend fun awaitPushed(name: String, timeoutMs: Long = 15_000L): Boolean {
        val end = nowMillis() + timeoutMs
        while (true) {
            val m = readMeta(name)
            if (m != null && !m.pendingPush && m.remoteUpdatedAt == m.updatedAt) return true
            if (nowMillis() > end) return false
            delay(250)
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
        val now = nowMillis()
        val open = listener?.openProjectName()
        var listChanged = false
        // Deleted projects (Nov 27): deleted on another device -> out of the list here too (unless it's open on
        // screen; it goes when it's closed); kept 30 days, then removed from the cloud; restored on another device ->
        // back in the list; deleted here while offline -> the tombstone goes up now.
        val tombstoned = mutableSetOf<String>()
        for (meta in remote) {
            val local = readDeleted()[meta.name]
            when {
                meta.deletedAt > 0L -> {
                    tombstoned += meta.name
                    if (now - meta.deletedAt >= KEEP_DELETED_MS) {
                        try { cloud.store.deleteProject(uid, meta.name) } catch (e: Exception) { platform.log("SiteDraftSync", "removing '${meta.name}' failed: ${e.message}") }
                        lock.withLock { writeDeleted(readDeleted() - meta.name) }
                    } else if (meta.name != open && meta.name !in sharedNames) lock.withLock {
                        writeDeleted(readDeleted() + (meta.name to DeletedLocal(meta.deletedAt, cloudMarked = true)))
                        if (meta.name in readList()) { writeList(readList() - meta.name); listChanged = true }
                    }
                }
                local != null && local.cloudMarked -> lock.withLock {
                    writeDeleted(readDeleted() - meta.name)
                    if (st.exists(projectFileName(meta.name))) { writeList(readList() + meta.name); listChanged = true }
                }
                local != null -> { tombstoned += meta.name; markInCloud(meta.name, local.deletedAt) }
            }
        }
        val deletedHere = readDeleted()
        // Local records older than 30 days (never in the cloud) drop off Recently deleted; the file stays on disk.
        val expired = deletedHere.filter { now - it.value.deletedAt >= KEEP_DELETED_MS }.keys
        if (expired.isNotEmpty()) lock.withLock { writeDeleted(readDeleted() - expired) }
        val localNames = readList() - sharedNames - deletedHere.keys
        val remoteByName = remote.filter { it.name !in tombstoned && it.name !in deletedHere }.associateBy { it.name }
        for (meta in remoteByName.values) {
            val existedLocally = st.exists(projectFileName(meta.name))
            if (meta.name in sharedNames) continue // an own project may not shadow a share's local name
            syncOne(uid, meta.name, meta.updatedAt, knownRemote = true)
            if (!existedLocally && st.exists(projectFileName(meta.name))) listChanged = true
        }
        for (name in localNames - remoteByName.keys - tombstoned) {
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
        if (readMeta(name) != null) return null
        return keepLocalCopy(name, incomingJson, platform.name)
    }

    /**
     * Saves the local [name] as a new project "<name> (<label>)" (renamed inside too) before it is
     * replaced by [incomingJson]; null when there is no local file or it is the same. Under [lock].
     */
    private fun keepLocalCopy(name: String, incomingJson: String, label: String): Pair<String, String>? {
        val st = storage ?: return null
        val localJson = st.read(projectFileName(name)) ?: return null
        if (localJson == incomingJson) return null
        val base = "$name ($label)"
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
        watchJob?.cancel()
    }
}

/**
 * Site Draft projects in Firestore, read and written exactly as the app does
 * (shared/.../cloud/GitLiveCloudService.kt, ProjectChunks, CloudDocs.encodeDocId):
 *
 *   users/{uid}/projects/{docId}            { name, updatedAt, device, chunkCount, json = chunk 0, ...sharing }
 *   users/{uid}/projects/{docId}/chunks/{i} { index: i, json }   for i >= 1, 300,000 characters each
 *
 * Added by the connector:
 *   users/{uid}/projectStamps/{docId}       { name, updatedAt, device: "Claude" }  - the open app polls this and reloads
 *   users/{uid}/mcpHistory/{autoId}         the drawing as it was before each change (chunked the same way), so
 *                                           undo_last_change can put it back; the newest 30 per project are kept
 *
 * Every change runs in one transaction: read the project, apply the tool, write the backup,
 * the project and the stamp together, so a save from the app in between is never lost silently.
 */
const M = require("./model");

const CHUNK_CHARS = 300000;
const DEVICE = "Claude";
const KEEP_HISTORY = 30;

function encodeDocId(name) {
  const cleaned = String(name).replace(/\//g, "∕");
  return (cleaned === "." || cleaned === ".." || !cleaned.trim()) ? `_${cleaned}` : cleaned;
}

function split(json) {
  if (!json.length) return [""];
  const out = [];
  for (let i = 0; i < json.length; i += CHUNK_CHARS) out.push(json.substring(i, i + CHUNK_CHARS));
  return out;
}

const projects = (db, uid) => db.collection("users").doc(uid).collection("projects");
const stamps = (db, uid) => db.collection("users").doc(uid).collection("projectStamps");
const history = (db, uid) => db.collection("users").doc(uid).collection("mcpHistory");

async function listProjects(db, uid) {
  const snap = await projects(db, uid).get();
  return snap.docs.map((d) => {
    const x = d.data();
    return { name: x.name || d.id, updatedAt: x.updatedAt || 0, device: x.device || null, sizeChars: null, chunkCount: x.chunkCount || 1 };
  }).sort((a, b) => b.updatedAt - a.updatedAt);
}

/** Reads the chunked text under [ref] (fields json + chunkCount, rest in ref/chunks). get = tx.get or plain get. */
async function readChunked(ref, get) {
  const snap = await get(ref);
  if (!snap.exists) return null;
  const d = snap.data();
  const count = Number(d.chunkCount || 1);
  const parts = [d.json || ""];
  if (count > 1) {
    const rest = await get(ref.collection("chunks"));
    const byIndex = rest.docs.map((c) => c.data()).filter((c) => c.index >= 1).sort((a, b) => a.index - b.index);
    for (const c of byIndex) parts.push(c.json || "");
    if (parts.length !== count) throw new Error(`The cloud copy is incomplete (${parts.length} of ${count} parts).`);
  }
  return { data: d, json: parts.join("") };
}

/** Queues the writes that store [json] chunked under [ref] (fields merged), removing parts past the new count. */
function writeChunked(w, ref, fields, json, oldCount = 1) {
  const chunks = split(json);
  w.set(ref, { ...fields, chunkCount: chunks.length, json: chunks[0] }, { merge: true });
  for (let i = 1; i < chunks.length; i++) w.set(ref.collection("chunks").doc(String(i)), { index: i, json: chunks[i] });
  for (let i = chunks.length; i < oldCount; i++) w.delete(ref.collection("chunks").doc(String(i)));
}

async function readProject(db, uid, name) {
  const got = await readChunked(projects(db, uid).doc(encodeDocId(name)), (r) => r.get());
  if (!got) return null;
  return { name: got.data.name || name, updatedAt: got.data.updatedAt || 0, device: got.data.device || null, state: M.parseProject(got.json) };
}

/**
 * Applies [change] (state -> { state, result }) to project [name] and saves it, with a backup.
 * [create]: the state to start from when the project does not exist (else it must exist).
 */
async function changeProject(db, uid, name, tool, change, { create = null, mustBeNew = false } = {}) {
  const ref = projects(db, uid).doc(encodeDocId(name));
  return db.runTransaction(async (tx) => {
    const got = await readChunked(ref, (r) => tx.get(r));
    if (got && mustBeNew) throw new M.UserError(`There is already a project named "${name}".`);
    if (!got && !create) throw new M.UserError(`No project named "${name}". Use list_projects to see the names.`);
    const before = got ? M.parseProject(got.json) : create;
    const { state, result } = await change(before);
    const json = M.stringifyProject(state);
    const now = Date.now();
    // Never write a time at or before the cloud copy's, or the app would think its own copy is newer.
    const updatedAt = Math.max(now, (got && got.data.updatedAt ? got.data.updatedAt : 0) + 1);
    if (got) {
      const h = history(db, uid).doc();
      // rev = this change's updatedAt: strictly increasing per project, so two changes in the same
      // millisecond still undo in the right order (at alone can tie).
      writeChunked(tx, h, { project: name, tool, at: now, rev: updatedAt, updatedAt: got.data.updatedAt || 0, device: got.data.device || null }, got.json);
    }
    writeChunked(tx, ref, { name, updatedAt, device: DEVICE }, json, got ? Number(got.data.chunkCount || 1) : 1);
    tx.set(stamps(db, uid).doc(encodeDocId(name)), { name, updatedAt, device: DEVICE });
    return { state, result, updatedAt };
  }).then(async (out) => { await pruneHistory(db, uid, name).catch(() => {}); return out; });
}

async function historyFor(db, uid, name) {
  const snap = await history(db, uid).where("project", "==", name).get();
  const order = (h) => h.rev ?? h.at;
  return snap.docs.map((d) => ({ ref: d.ref, id: d.id, ...d.data() })).sort((a, b) => order(b) - order(a));
}

async function pruneHistory(db, uid, name) {
  const all = await historyFor(db, uid, name);
  for (const h of all.slice(KEEP_HISTORY)) {
    const batch = db.batch();
    for (let i = 1; i < (h.chunkCount || 1); i++) batch.delete(h.ref.collection("chunks").doc(String(i)));
    batch.delete(h.ref);
    await batch.commit();
  }
}

/** Puts back the drawing as it was before the newest connector change to [name]. */
async function undoLast(db, uid, name) {
  const [last] = await historyFor(db, uid, name);
  if (!last) throw new M.UserError(`No connector change to "${name}" is left to undo.`);
  const got = await readChunked(last.ref, (r) => r.get());
  const restored = M.parseProject(got.json);
  const out = await changeProject(db, uid, name, "undo", () => ({ state: restored, result: { undid: last.tool, madeAt: new Date(last.at).toISOString() } }));
  // The undo itself made a backup; drop both it and the one just used, so a second undo goes further back.
  const after = await historyFor(db, uid, name);
  for (const h of [after.find((x) => x.tool === "undo"), after.find((x) => x.id === last.id)].filter(Boolean)) {
    const batch = db.batch();
    for (let i = 1; i < (h.chunkCount || 1); i++) batch.delete(h.ref.collection("chunks").doc(String(i)));
    batch.delete(h.ref);
    await batch.commit();
  }
  return out;
}

module.exports = { encodeDocId, split, listProjects, readProject, changeProject, undoLast, historyFor, CHUNK_CHARS, DEVICE };

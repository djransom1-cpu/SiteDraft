/**
 * The app runs Claude's commands (Dan, Nov 25 2026). Instead of a JavaScript copy of the drawing
 * code, Claude sends the app's own events (actions.json, generated from the app by
 * ConnectorExportTest) and the OPEN app runs them with its own code (shared/.../ui/cloud/ClaudeBridge.kt):
 *
 *  - users/{uid}/claudeDevices/{deviceId}: each open copy (desktop, Android, web) writes
 *    { device, project, lastActiveAt, seenAt } about once a minute; the connector sets
 *    pendingCommand on the copy it picks.
 *  - users/{uid}/claudeCommands/{id}: { project, target, status: pending|running|done|error,
 *    actions (JSON text), result (JSON text), createdAt, startedAt, finishedAt, runBy }.
 *
 * The copy picked is one with the project open, most recently used first; if no copy has it open,
 * the most recently used open copy (it opens the project first). Only that copy runs it.
 */
const M = require("./model");

let actionsFile = null;
try {
  actionsFile = require("./actions.json");
} catch (e) {
  actionsFile = null; // written by the app's tests (ConnectorExportTest); run them before deploying
}

/** A copy that hasn't written its presence for this long is taken as closed. */
const FRESH_MS = 150 * 1000;

const devices = (db, uid) => db.collection("users").doc(uid).collection("claudeDevices");
const commands = (db, uid) => db.collection("users").doc(uid).collection("claudeCommands");

const ago = (ms) => (ms < 90 * 1000 ? `${Math.round(ms / 1000)}s ago` : ms < 90 * 60 * 1000 ? `${Math.round(ms / 60000)} min ago` : `${Math.round(ms / 3600000)} h ago`);

/** Every copy of the app that has said it is open, newest use first. */
async function copies(db, uid, now = Date.now()) {
  const snap = await devices(db, uid).get();
  return snap.docs.map((d) => {
    const v = d.data() || {};
    return {
      id: d.id, device: v.device || "?", project: v.project || "",
      lastActiveAt: Number(v.lastActiveAt || 0), seenAt: Number(v.seenAt || 0),
      open: now - Number(v.seenAt || 0) < FRESH_MS,
    };
  }).sort((a, b) => b.lastActiveAt - a.lastActiveAt);
}

/** The copy that should run a command for [project], or null when none is open. */
function pick(list, project) {
  const open = list.filter((c) => c.open);
  return open.find((c) => c.project === project) || open[0] || null;
}

async function status(db, uid, now = Date.now()) {
  const list = await copies(db, uid, now);
  return {
    open: list.filter((c) => c.open).map((c) => ({ device: c.device, project: c.project, lastUsed: ago(now - c.lastActiveAt), seen: ago(now - c.seenAt) })),
    recentlyClosed: list.filter((c) => !c.open && now - c.seenAt < 24 * 3600 * 1000).map((c) => ({ device: c.device, project: c.project, lastSeen: ago(now - c.seenAt) })),
    note: list.some((c) => c.open)
      ? "run_actions goes to the copy with the project open that was used last (or, if none has it open, the last-used copy, which opens it)."
      : "No copy of Site Draft is open right now: run_actions needs the app open (desktop, tablet or web, signed in). The other tools work without it.",
  };
}

/** The actions list (from the app), optionally one action in full or a search. */
function listActions({ action, search } = {}) {
  if (!actionsFile) throw new M.UserError("actions.json is missing from the connector: run the app's tests (build-test-copy.bat) and deploy again.");
  const all = actionsFile.actions || [];
  if (action) {
    const a = all.find((x) => x.action.toLowerCase() === String(action).toLowerCase());
    if (!a) throw new M.UserError(`No action "${action}". Actions: ${all.map((x) => x.action).join(", ")}`);
    return { note: actionsFile.note, ...a };
  }
  const q = search ? String(search).toLowerCase() : null;
  const hits = all.filter((x) => !q || x.action.toLowerCase().includes(q) || (x.about || "").toLowerCase().includes(q));
  return {
    note: actionsFile.note + " Ask for one action by name to see its argument types in full.",
    actions: hits.map((x) => ({ action: x.action, args: x.args.map((g) => g.name + (g.optional ? "?" : "")), about: x.about })),
  };
}

function checkActions(actions) {
  if (!Array.isArray(actions) || !actions.length) throw new M.UserError("actions: a list like [{\"action\": \"AddWall\", \"args\": {\"lengthPx\": 600}}].");
  if (!actionsFile) return;
  const names = new Set((actionsFile.actions || []).map((x) => x.action));
  actions.forEach((a, i) => {
    const n = String(a.action || "").replace(/^com\.example\.sitedraft\.ui\.canvas\.DrawingEvent\./, "");
    if (!names.has(n)) throw new M.UserError(`Action ${i + 1}: no action "${a.action}" (list_actions has them all).`);
  });
}

const sleepMs = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Leaves a batch for the open app and waits (up to waitMs) for its answer.
 * opts.sleep / opts.now are for the tests.
 */
async function runActions(db, uid, { project, actions, waitMs = 25000 }, opts = {}) {
  const now = opts.now || (() => Date.now());
  const sleep = opts.sleep || sleepMs;
  checkActions(actions);
  const list = await copies(db, uid, now());
  const target = pick(list, project);
  if (!target) {
    const last = list[0];
    throw new M.UserError("Site Draft isn't open on any device right now" +
      (last ? ` (last seen: ${last.device}, ${ago(now() - last.seenAt)})` : "") +
      ". Ask Dan to open it (signed in), or use the regular tools, which work with the app closed.");
  }
  const ref = commands(db, uid).doc();
  await ref.set({
    project, target: target.id, status: "pending", actions: JSON.stringify(actions.map((a) => ({ action: a.action, args: a.args || {} }))),
    createdAt: now(), createdBy: "Claude",
  });
  await devices(db, uid).doc(target.id).set({ pendingCommand: ref.id }, { merge: true });
  const end = now() + Math.max(1000, waitMs);
  let doc = null;
  while (now() < end) {
    await sleep(500);
    const snap = await ref.get();
    doc = snap.exists ? snap.data() : null;
    if (doc && (doc.status === "done" || doc.status === "error")) break;
  }
  return answer(ref.id, target, doc);
}

function answer(id, target, doc) {
  const s = doc ? doc.status : "missing";
  if (s === "done" || s === "error") {
    let result = doc.result;
    try { result = JSON.parse(doc.result); } catch (e) { /* keep the text */ }
    return { command: id, status: s, ranOn: doc.runBy || target.device, result };
  }
  return {
    command: id, status: s, sentTo: `${target.device} (${target.project || "no project open"})`,
    note: s === "running" ? "Still running in the app; check with command_status." :
      "The app hasn't picked it up yet (it checks every few seconds while open). Check with command_status; if it never does, the app may have closed.",
  };
}

async function commandStatus(db, uid, id) {
  const snap = await commands(db, uid).doc(String(id)).get();
  if (!snap.exists) throw new M.UserError(`No command ${id}.`);
  const d = snap.data();
  return answer(snap.id, { device: d.runBy || "?", project: d.project }, d);
}

module.exports = { copies, pick, status, listActions, runActions, commandStatus, FRESH_MS, hasActions: () => !!actionsFile };

// Site Draft connector, end to end without the network: an in-memory Firestore fake, an
// in-memory MCP client against buildServer(), and the HTTP app (OAuth + upload page) on a local port.
// Writes test/out/connector-project.json for the Kotlin ConnectorCheckTest to open with the app's code.
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const { Client } = require("@modelcontextprotocol/sdk/client/index.js");
const { InMemoryTransport } = require("@modelcontextprotocol/sdk/inMemory.js");
const { buildServer, buildApp } = require("../sitedraft/mcp.js");
const S = require("../sitedraft/store.js");
const M = require("../sitedraft/model.js");
const T = require("../sitedraft/tools.js");

// ---- tiny Firestore fake (documents, subcollections, where ==, transactions, batches) ----
function makeDb() {
  const store = new Map();
  let n = 0;
  const clone = (d) => JSON.parse(JSON.stringify(d));
  const snapOf = (p) => { const d = store.get(p); return { id: p.split("/").pop(), ref: docRef(p), exists: !!d, data: () => (d ? clone(d) : undefined) }; };
  const docRef = (p) => ({
    id: p.split("/").pop(), path: p, _isDoc: true,
    async get() { return snapOf(p); },
    async set(data, opts) { const d = clone(data); store.set(p, opts?.merge ? { ...(store.get(p) || {}), ...d } : d); },
    async delete() { store.delete(p); },
    collection(name) { return colRef(p + "/" + name); },
  });
  const colRef = (p, filters = [], lim = Infinity) => ({
    path: p,
    doc(id) { return docRef(p + "/" + (id || "auto" + String(++n).padStart(6, "0"))); },
    where(f, op, v) { assert.strictEqual(op, "=="); return colRef(p, [...filters, [f, v]], lim); },
    limit(k) { return colRef(p, filters, k); },
    async get() {
      const docs = [];
      for (const [k, d] of store) {
        if (!k.startsWith(p + "/") || k.slice(p.length + 1).includes("/")) continue;
        if (filters.every(([f, v]) => d[f] === v)) docs.push(snapOf(k));
        if (docs.length >= lim) break;
      }
      return { docs, empty: !docs.length, size: docs.length };
    },
  });
  const writer = (ops) => ({
    set: (r, d, o) => { ops.push(() => r.set(d, o)); }, delete: (r) => { ops.push(() => r.delete()); },
  });
  return {
    collection: (name) => colRef(name),
    async runTransaction(fn) { const ops = []; const tx = { get: (r) => r.get(), ...writer(ops) }; const out = await fn(tx); for (const o of ops) await o(); return out; },
    batch() { const ops = []; return { ...writer(ops), async commit() { for (const o of ops) await o(); } }; },
    _store: store,
  };
}

const call = async (client, name, args) => {
  const r = await client.callTool({ name, arguments: args });
  const t = r.content[0].text;
  if (r.isError) return { error: t };
  return JSON.parse(t);
};

async function main() {
  const db = makeDb();
  const uid = "u1";
  const ctx = { db, uid, email: "owner@example.com", base: "https://example.test" };
  const server = buildServer(ctx);
  const [a, b] = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: "test", version: "1" });
  await Promise.all([server.connect(a), client.connect(b)]);

  const tools = (await client.listTools()).tools.map((t) => t.name);
  for (const t of ["whoami", "list_projects", "read_drawing", "create_project", "draw_walls", "place_opening", "place_cabinets", "import_pdf", "create_upload_link", "undo_last_change"])
    assert(tools.includes(t), `tool ${t}`);

  // A project the app saved (its own encoding, with a big packed colour) reads and round-trips losslessly.
  const appJson = fs.readFileSync(path.join(__dirname, "../sitedraft/fixtures/sample-project.json"), "utf8");
  await db.collection("users").doc(uid).collection("projects").doc("Sample").set({ name: "Sample", updatedAt: 1000, device: "Windows", chunkCount: 1, json: appJson });
  const sample = await call(client, "read_drawing", { project: "Sample" });
  assert.strictEqual(sample.walls.length, 1);
  assert.strictEqual(sample.cabinetsAndAppliances[0].code, "BD24");
  // Values survive exactly (packed colours beyond 2^53 included); only 48.0 may come back as 48, which Kotlin reads the same.
  const norm = (t) => t.replace(/(\d)\.0(?=[,\n\s}\]])/g, "$1");
  assert.strictEqual(norm(M.stringifyProject(M.parseProject(appJson))).trim(), norm(appJson).trim(), "app JSON round-trips");
  assert(M.stringifyProject(M.parseProject(appJson)).includes("-72057594037927936"), "Color.Black kept exactly");

  // ---- the walk: a 12' x 10' kitchen measured on the inside faces ----
  assert.deepStrictEqual(await call(client, "create_project", { name: "Ransom Kitchen" }), { created: "Ransom Kitchen", note: "Open it in Site Draft from the project list (refresh the list if it isn't there yet)." });
  assert((await call(client, "create_project", { name: "Ransom Kitchen" })).error.includes("already"));
  const missing = await call(client, "draw_walls", { project: "Ransom Kitchen", segments: [{ length: "12'" }] });
  assert(missing.error, "measured face is required");

  const walls = await call(client, "draw_walls", { project: "Ransom Kitchen", measured: "inside", close: true, room_name: "Kitchen",
    segments: [{ length: "12'" }, { length: "10'", turn: "right" }, { length: "12'", turn: "right" }, { length: "10'", turn: "right" }] });
  assert.strictEqual(walls.created.length, 4);
  assert.strictEqual(walls.insideSize, "12'-0\" x 10'-0\"");
  // The cloud copy is stamped for the app to reload, by "Claude", newer than before.
  const stamp = db._store.get(`users/${uid}/projectStamps/Ransom Kitchen`);
  assert.strictEqual(stamp.device, "Claude");
  const doc = db._store.get(`users/${uid}/projects/Ransom Kitchen`);
  assert.strictEqual(doc.updatedAt, stamp.updatedAt);
  assert.strictEqual(doc.device, "Claude");

  let d = await call(client, "read_drawing", { project: "Ransom Kitchen" });
  assert.strictEqual(d.roomLabels[0].insideWidth, "12'-0\"");
  const north = d.walls.find((w) => w.runs === "east-west" && w.roomSide === "south");
  const west = d.walls.find((w) => w.runs === "north-south" && w.roomSide === "east");
  const east = d.walls.find((w) => w.runs === "north-south" && w.roomSide === "west");

  // Window centred 6' from the left inside corner; door 4" off the bottom corner of the west wall.
  const win = await call(client, "place_opening", { project: "Ransom Kitchen", wall_id: north.id, kind: "window", width: "3'", from: "left", to: "center", distance: "6'" });
  assert.match(win.placed.measured, /^6'-0" from the left inside corner to the centre/);
  const doorMissingSwing = await call(client, "place_opening", { project: "Ransom Kitchen", wall_id: west.id, kind: "door", width: 36 });
  assert(doorMissingSwing.error.includes("swing"));
  const door = await call(client, "place_opening", { project: "Ransom Kitchen", wall_id: west.id, kind: "door", width: "2'8\"", from: "bottom", distance: '4"', swing_into: "east", hinge: "right" });
  assert.strictEqual(door.placed.type, "Door 2'-8\"");
  assert((await call(client, "place_opening", { project: "Ransom Kitchen", wall_id: north.id, kind: "window", width: "3'", from: "left", to: "center", distance: "7'" })).error.includes("overlaps"));
  assert((await call(client, "place_opening", { project: "Ransom Kitchen", wall_id: north.id, kind: "window", width: "3'", from: "right", distance: "11'" })).error.includes("past the end"));

  // Base run on the north wall from the left corner: the sink base under the window.
  const bases = await call(client, "place_cabinets", { project: "Ransom Kitchen", wall_id: north.id, items: ["B18", "B15", "SB36", "DW", "BD18", "B15"], style: "shaker" });
  assert.deepStrictEqual(bases.placed.map((p) => p.code || p.name), ["B18", "B15", "SB36", "Dishwasher", "BD18", "B15"]);
  assert.strictEqual(bases.runLength, "10'-6\""); // 18+15+36+24+18+15
  const uppers = await call(client, "place_cabinets", { project: "Ransom Kitchen", wall_id: north.id, items: ["W1830", "W1830", "W1830", "GAP 36", "W2430"] });
  assert.deepStrictEqual(uppers.placed.map((p) => p.code), ["W1830", "W1830", "W1830", "W2430"]);
  assert.strictEqual(uppers.warnings, undefined, "the gap leaves the window (54-90\") clear");
  const blocked = await call(client, "place_cabinets", { project: "Ransom Kitchen", wall_id: north.id, offset: "60\"", items: ["W1830"] });
  assert.match(blocked.warnings[0], /in front of the Window/);
  await call(client, "undo_last_change", { project: "Ransom Kitchen" });
  const tall = await call(client, "place_cabinets", { project: "Ransom Kitchen", wall_id: east.id, from: "top", items: ["Refrigerator 36\" French Door", "B24", { code: "W3018" }] });
  assert(tall.placed.length === 3);

  d = await call(client, "read_drawing", { project: "Ransom Kitchen" });
  const codes = d.cabinetsAndAppliances.map((c) => c.code || c.name);
  for (const c of ["B18", "SB36", "BD18", "W1830", "W2430", "W3018", "Dishwasher"]) assert(codes.includes(c), `has ${c}`);
  const sb = d.cabinetsAndAppliances.find((c) => c.code === "SB36");
  assert.strictEqual(sb.wallId, north.id);
  assert.strictEqual(sb.fromLeftOrTopInsideCornerIn, 33);
  assert(d.otherSymbols.length === 0 || d.otherSymbols.every((s) => s.name !== "Kitchen Sink"), "the sink is listed with the kitchen items");

  // Undo takes back exactly the last change, and a second undo the one before.
  const before = d.cabinetsAndAppliances.length;
  await call(client, "undo_last_change", { project: "Ransom Kitchen" });
  d = await call(client, "read_drawing", { project: "Ransom Kitchen" });
  assert.strictEqual(d.cabinetsAndAppliances.length, before - 3);
  await call(client, "undo_last_change", { project: "Ransom Kitchen" });
  d = await call(client, "read_drawing", { project: "Ransom Kitchen" });
  assert.strictEqual(d.cabinetsAndAppliances.filter((c) => (c.code || "").startsWith("W")).length, 0);
  // Put the uppers and the east wall back for the Kotlin check.
  await call(client, "place_cabinets", { project: "Ransom Kitchen", wall_id: north.id, items: ["W1830", "W1830", "W1830", "GAP 36", "W2430"] });
  await call(client, "place_cabinets", { project: "Ransom Kitchen", wall_id: east.id, from: "top", items: ["Refrigerator 36\" French Door", "B24", { code: "W3018" }] });

  // A second room sharing the east wall is not doubled: 8' x 10' measured inside, starting at the far side of the shared wall.
  const shared = await call(client, "draw_walls", { project: "Ransom Kitchen", measured: "inside", close: true, room_name: "Pantry",
    start: { x: "12'4 1/2\"", y: 0 }, segments: [{ length: "8'" }, { length: "10'", turn: "right" }, { length: "8'", turn: "right" }, { length: "10'", turn: "right" }] });
  assert.strictEqual(shared.created.length, 3);
  assert.strictEqual(shared.skippedSegments.length, 1);

  // Framing measurements: the finished room is 1" smaller each way.
  await call(client, "create_project", { name: "Framing" });
  const fr = await call(client, "draw_walls", { project: "Framing", measured: "framing", close: true, segments: [{ length: "10'" }, { length: "10'", turn: "right" }, { length: "10'", turn: "right" }, { length: "10'", turn: "right" }] });
  assert.strictEqual(fr.insideSize, "9'-11\" x 9'-11\"");
  // An L-shaped room, outside measurements, and a 45 degree wall warns but draws.
  await call(client, "create_project", { name: "L" });
  const l = await call(client, "draw_walls", { project: "L", measured: "outside", close: true,
    segments: [{ length: "20'" }, { length: "10'", turn: "right" }, { length: "8'", turn: "right" }, { length: "6'", turn: "left" }, { length: "12'", turn: "right" }, { length: "16'", turn: "right" }] });
  assert.strictEqual(l.created.length, 6);
  assert.strictEqual(l.closed, true);
  const angled = await call(client, "draw_walls", { project: "L", measured: "inside", start: { x: 0, y: "30'" }, room_side: "right", segments: [{ length: "8'" }, { length: "4'", turn: 45 }] });
  assert(angled.nonSquareWalls);

  // PDF import inline.
  const pdf = fs.readFileSync(path.join(__dirname, "plan.pdf"));
  const imp = await call(client, "import_pdf", { project: "Ransom Kitchen", pdf_base64: pdf.toString("base64"), pages: [1], plan_scale: "1/4\" = 1'", opacity: 0.5, locked: true });
  assert.strictEqual(imp.pages, 1);
  assert.strictEqual(imp.sizeOnDrawing, "68'-0\" x 44'-0\"");
  const st = (await S.readProject(db, uid, "Ransom Kitchen")).state;
  assert.strictEqual(M.kind(st.elements[0]), "Pdf", "plan first = under everything");
  assert.match(st.elements[0].pages[0], /^iVBORw0KGgo/);
  assert((await call(client, "import_pdf", { project: "Ransom Kitchen", pdf_base64: Buffer.from("hello").toString("base64") })).error.includes("isn't a PDF"));

  // Chunking: a project bigger than one 300,000-character part is split and read back whole.
  const big = await S.readProject(db, uid, "Ransom Kitchen");
  const bigJson = M.stringifyProject(big.state);
  const parts = db._store.get(`users/${uid}/projects/Ransom Kitchen`).chunkCount;
  assert.strictEqual(parts, Math.ceil(bigJson.length / 300000));
  assert(parts >= 1);
  // For the Kotlin ConnectorCheckTest: the drawing (without the plan image) and what the connector says about it.
  const fx = path.join(__dirname, "../sitedraft/fixtures");
  const kitchen = (await S.readProject(db, uid, "Ransom Kitchen")).state;
  const noPdf = { ...kitchen, elements: kitchen.elements.filter((e) => M.kind(e) !== "Pdf") };
  fs.writeFileSync(path.join(fx, "connector-project.json"), M.stringifyProject(noPdf) + "\n");
  fs.writeFileSync(path.join(fx, "connector-L.json"), M.stringifyProject((await S.readProject(db, uid, "L")).state) + "\n");
  const seen = T.readDrawing(noPdf);
  fs.writeFileSync(path.join(fx, "connector-expect.json"), JSON.stringify({
    note: "Written by functions-mcp/test/sitedraft-mcp.test.js; ConnectorCheckTest checks it with the app's code.",
    codes: Object.fromEntries(seen.cabinetsAndAppliances.filter((c) => c.code).map((c) => [c.id, c.code])),
    window: { id: win.placed.id, fromLeftInsideCornerToCenterIn: 72 },
    door: { id: door.placed.id, fromBottomInsideCornerToEdgeIn: 4, hingeRight: true },
    kitchenInsideIn: [144, 120],
    walls: seen.walls.length,
  }, null, 2) + "\n");
  const forceChunks = { ...big.state, projectName: "Big", elements: [...big.state.elements, M.makeText({ text: "x".repeat(700000), topLeft: { x: 0, y: 0 } })] };
  await S.changeProject(db, uid, "Big", "test", () => ({ state: forceChunks, result: {} }), { create: { projectName: "Big" } });
  assert.strictEqual(db._store.get(`users/${uid}/projects/Big`).chunkCount, Math.ceil(M.stringifyProject(forceChunks).length / 300000));
  assert.strictEqual((await S.readProject(db, uid, "Big")).state.elements.length, forceChunks.elements.length);
  await S.changeProject(db, uid, "Big", "test", (s) => ({ state: { ...s, elements: s.elements.slice(0, -1) }, result: {} }));
  assert.strictEqual(db._store.get(`users/${uid}/projects/Big`).chunkCount, 1);
  assert(!db._store.has(`users/${uid}/projects/Big/chunks/1`), "old parts removed");

  // ---- the HTTP side: consent page, upload link ----
  const app = buildApp(db, { legacySecret: "test-key", base: "http://127.0.0.1", auth: { getUserByEmail: async () => ({ uid }) } });
  const srv = app.listen(0);
  const port = srv.address().port;
  const url = (p) => `http://127.0.0.1:${port}${p}`;
  try {
    const link = await call(client, "create_upload_link", { project: "Upload Test", create_if_missing: true, plan_scale: "1/4\" = 1'" });
    const token = link.link.split("/upload/")[1];
    const page = await fetch(url(`/upload/${token}`));
    assert.strictEqual(page.status, 200);
    assert((await page.text()).includes("Upload Test"));
    const bad = await fetch(url(`/upload/${token}`), { method: "POST", headers: { "Content-Type": "application/pdf" }, body: Buffer.from("nope") });
    assert.strictEqual(bad.status, 400);
    const good = await fetch(url(`/upload/${token}`), { method: "POST", headers: { "Content-Type": "application/pdf" }, body: pdf });
    assert.strictEqual(good.status, 200, await good.clone().text());
    assert.strictEqual((await good.json()).pages, 2);
    assert.strictEqual((await call(client, "upload_status", { upload_id: token })).status, "imported");
    assert.strictEqual((await fetch(url(`/upload/${token}`))).status, 410, "one use only");
    assert.strictEqual((await call(client, "list_projects", {})).some((p) => p.name === "Upload Test"), true);

    // OAuth: register, authorize, a wrong key is refused, the right key issues a token that works.
    const reg = await (await fetch(url("/register"), { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ redirect_uris: ["https://claude.ai/cb"], client_name: "t", token_endpoint_auth_method: "none" }) })).json();
    const verifier = "v".repeat(50);
    const challenge = require("crypto").createHash("sha256").update(verifier).digest("base64url");
    const auth = await fetch(url(`/authorize?response_type=code&client_id=${reg.client_id}&redirect_uri=${encodeURIComponent("https://claude.ai/cb")}&code_challenge=${challenge}&code_challenge_method=S256&state=s1`));
    const html = await auth.text();
    assert(html.includes("Site Draft access key"), html.slice(0, 300));
    const authId = html.match(/name="authId" value="([^"]+)"/)[1];
    assert.strictEqual((await fetch(url(`/authorize/confirm?authId=${authId}&secret=wrong`), { redirect: "manual" })).status, 400);
    const ok = await fetch(url(`/authorize/confirm?authId=${authId}&secret=test-key`), { redirect: "manual" });
    assert.strictEqual(ok.status, 302);
    const code = new URL(ok.headers.get("location")).searchParams.get("code");
    const tok = await (await fetch(url("/token"), { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "authorization_code", code, code_verifier: verifier, client_id: reg.client_id, redirect_uri: "https://claude.ai/cb" }) })).json();
    assert(tok.access_token, JSON.stringify(tok));
    const rpc = await fetch(url("/"), { method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream", Authorization: `Bearer ${tok.access_token}` },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "list_projects", arguments: {} } }) });
    const body = await rpc.text();
    assert.strictEqual(rpc.status, 200, body);
    assert(body.includes("Ransom Kitchen"), body.slice(0, 400));
    assert.strictEqual((await fetch(url("/"), { method: "POST", headers: { "Content-Type": "application/json", Authorization: "Bearer nope" }, body: "{}" })).status, 401);
  } finally {
    srv.close();
  }
  console.log("sitedraft-mcp tests passed");
}

main().catch((e) => { console.error(e); process.exit(1); });

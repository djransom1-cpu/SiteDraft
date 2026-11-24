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
  // A new window's head lines up with the door head: 32" sill + 48" = 80".
  {
    const st = (await S.readProject(db, uid, "Ransom Kitchen")).state;
    const w = st.elements.find((e) => e.id === win.placed.id);
    assert.strictEqual(w.properties[M.props.sillIn] + w.properties[M.props.heightIn], 80);
  }
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
  // Remodel: a 20' x 12' room measured as existing, 6' of the north wall coming out 4' from its
  // west end, the east wall coming out whole, then a new wall; the proposed plan shown.
  {
  await call(client, "create_project", { name: "Remodel" });
  const ex = await call(client, "draw_walls", { project: "Remodel", measured: "inside", close: true, status: "existing",
    segments: [{ length: "20'" }, { length: "12'", turn: "right" }, { length: "20'", turn: "right" }, { length: "12'", turn: "right" }] });
  assert(ex.created.every((w) => w.status === "existing"), "drawn as existing");
  const north = ex.created.find((w) => w.runs === "east-west" && w.from.yIn < 0);
  const northLen = north.lengthIn;
  const split = await call(client, "demo_wall_part", { project: "Remodel", wall_id: north.id, from: "4'", length: "6'", measured_from: "left" });
  assert.strictEqual(split.walls.length, 3, JSON.stringify(split));
  assert.deepStrictEqual(split.walls.map((w) => w.status), ["existing", "demo", "existing"]);
  assert.strictEqual(split.walls[0].id, north.id, "first piece keeps the id");
  assert.strictEqual(split.walls[1].lengthIn, 72);
  assert.strictEqual(split.walls[1].from.xIn, north.from.xIn + 48);
  assert(Math.abs(split.walls.reduce((t, w) => t + w.lengthIn, 0) - northLen) < 0.01, "pieces add up to the wall");
  // From the right end, and past the end refused; a north-south wall measured from the left refused.
  const east = ex.created.find((w) => w.runs === "north-south" && w.from.xIn > 100);
  assert((await call(client, "demo_wall_part", { project: "Remodel", wall_id: east.id, from: 0, length: "40'" })).error.includes("past the end"));
  assert((await call(client, "demo_wall_part", { project: "Remodel", wall_id: east.id, from: 0, length: "2'", measured_from: "left" })).error.includes("north-south"));
  const endPiece = await call(client, "demo_wall_part", { project: "Remodel", wall_id: split.walls[2].id, from: 0, length: "1'", measured_from: "right" });
  assert.deepStrictEqual(endPiece.walls.map((w) => w.status), ["existing", "demo"], "demo at the end makes two pieces");
  await call(client, "edit_element", { project: "Remodel", id: east.id, status: "demo" });
  await call(client, "draw_walls", { project: "Remodel", measured: "center", start: { x: "10'", y: 0 }, room_side: "right", status: "new", segments: [{ length: "12'", direction: "south" }] });
  assert((await call(client, "edit_element", { project: "Remodel", id: east.id, status: "gone" })).error);
  const view = await call(client, "set_remodel_view", { project: "Remodel", show: "proposed", draw_new_walls_as: "new" });
  assert.deepStrictEqual(view.changed, ["showing the proposed plan", "new walls drawn as new"]);
  const rd = await call(client, "read_drawing", { project: "Remodel" });
  assert.strictEqual(rd.remodel.showing, "proposed");
  // North: existing, demo, existing, demo (two splits); south and west existing; east demo whole.
  assert.deepStrictEqual(rd.remodel.walls, { existing: 4, demo: 3, new: 1 });
  // The app's JSON names: the phase enums and the wall property.
  const rdoc = M.parseProject(db._store.get(`users/${uid}/projects/Remodel`).json);
  assert.strictEqual(rdoc.remodelPhase, "PROPOSED");
  assert.strictEqual(rdoc.wallDrawStatus, "NEW");
  assert(rdoc.elements.filter((e) => e.category === "Walls").every((e) => [0, 1, 2].includes(e.properties.remodelStatus)));
  }

  // Stairs: the app's own worked samples (catalog.stairs, from Stairs.kt) through stairs.js.
  {
  const ST = require("../sitedraft/stairs.js");
  const cs = M.catalog.stairs;
  assert(cs && cs.samples.length > 20, "the app exported stair samples");
  for (const smp of cs.samples) {
    const project = { building: smp.settings, elements: [] };
    const props = { ...cs.defaults[smp.type], [cs.keys.goes]: smp.goes };
    if (smp.typedRise > 0) { props[cs.keys.rise] = smp.typedRise; props[cs.keys.riseSet] = 1; }
    const st = { type: "x", id: "st", symbolName: smp.type, category: "Stairs", properties: props, layerId: `floor${smp.floor}-plan`, rotation: smp.rotation };
    ST.syncOne(st, project);
    const what = `${smp.building} ${smp.type} goes ${smp.goes} floor ${smp.floor}`;
    assert.strictEqual(st.properties[cs.keys.dir] === 2, smp.down, what + ": up or down");
    assert(Math.abs(st.properties[cs.keys.rise] - smp.riseIn) < 0.01, `${what}: rise ${st.properties[cs.keys.rise]} vs ${smp.riseIn}`);
    assert.strictEqual(ST.layout(st).risers, smp.risers, what + ": risers");
    const fp = ST.footprint(st);
    assert(Math.abs(fp[0] - smp.footprintWIn) < 0.01 && Math.abs(fp[1] - smp.footprintDIn) < 0.01, `${what}: footprint ${fp} vs ${smp.footprintWIn},${smp.footprintDIn}`);
    const op = ST.opening(st, project);
    if (smp.openingLayer == null) assert.strictEqual(op, null, what + ": no opening");
    else {
      assert.strictEqual(op.layerId, smp.openingLayer, what + ": opening layer");
      assert(Math.abs(op.lengthIn - smp.openingLIn) < 0.01, `${what}: opening ${op.lengthIn} vs ${smp.openingLIn}`);
    }
  }
  // Placed by voice: a basement stair, top at the hall, climbing north; then typed and back to the building.
  await call(client, "create_project", { name: "Stairs" });
  await call(client, "set_building_inputs", { project: "Stairs", build_from_floor_plan: true });
  const ps = await call(client, "place_stairs", { project: "Stairs", top: { x: "10'", y: "4'" }, climbs: "north" });
  assert.strictEqual(ps.placed.goes, "down", JSON.stringify(ps));
  assert.strictEqual(ps.placed.risers, 13);
  assert.strictEqual(ps.placed.planLabel, "DN 13R");
  assert.strictEqual(ps.footprintIn.depth, 120);
  assert.deepStrictEqual(ps.at, { centerXIn: 120, centerYIn: 108 }, "centre half the run back from the top edge");
  assert.strictEqual(ps.placed.floorOpening.layer, "floor1-structural");
  const east = await call(client, "place_stairs", { project: "Stairs", top: { x: 0, y: 0 }, climbs: "east", goes: "up", width: "42\"" });
  assert.strictEqual(east.placed.goes, "up");
  assert.deepStrictEqual(east.at, { centerXIn: -east.footprintIn.depth / 2, centerYIn: 0 }, "climbing east: the stair runs west of its top");
  const typed = await call(client, "edit_stairs", { project: "Stairs", id: ps.placed.id, rise: "105\"" });
  assert.strictEqual(typed.edited.rise, "typed by hand");
  assert.strictEqual(typed.edited.risers, 14);
  const back = await call(client, "edit_stairs", { project: "Stairs", id: ps.placed.id, rise: "building" });
  assert.strictEqual(back.edited.rise, "follows the building");
  assert.strictEqual(back.edited.risers, 13);
  const rs = await call(client, "read_drawing", { project: "Stairs" });
  assert.strictEqual(rs.stairs.length, 2);
  assert((await call(client, "place_symbol", { project: "Stairs", name: "Straight Stair", x: 0, y: 0 })).error.includes("place_stairs"));
  }

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
  // ---- levels and layers (phase 1 of foundation to roof) ----
  const L = require("../sitedraft/layers.js");
  // layers.js works out the same levels and layers as the app (samples the app exported into catalog.json).
  const r4 = (v) => (v == null ? null : Math.round(v * 10000) / 10000);
  for (const [name, sample] of Object.entries(M.catalog.building.samples)) {
    const s = { ...M.catalog.building.defaults, ...sample.settings };
    assert.deepStrictEqual(L.levels(s).map((l) => ({ ...l, elevationIn: r4(l.elevationIn) })), sample.levels.map((l) => ({ ...l, elevationIn: r4(l.elevationIn) })), `levels ${name}`);
    assert.deepStrictEqual(L.defaultLayers(s), sample.layers, `layers ${name}`);
  }
  for (const t of ["list_levels_and_layers", "set_active_layer", "show_hide_layers", "add_layer", "set_building_inputs", "move_to_layer"]) assert(tools.includes(t), `tool ${t}`);
  await call(client, "create_project", { name: "House" });
  let ll = await call(client, "list_levels_and_layers", { project: "House" });
  const lev = Object.fromEntries(ll.levels.map((x) => [x.id, x.elevation]));
  assert.strictEqual(lev.floor1, "100'-0\"");
  assert.strictEqual(lev.foundation, "99'-0 1/2\"");
  assert.strictEqual(lev.footing, "91'-8 1/2\"");
  assert(ll.levels.find((x) => x.id === "footing").note.includes("91'-0 1/2\""));
  assert.strictEqual(ll.activeLayer, "floor1-plan");
  // Drawn on the foundation layer once it is active.
  assert.strictEqual((await call(client, "set_active_layer", { project: "House", layer: "Foundation and foundation walls" })).activeLayer, "foundation");
  await call(client, "draw_walls", { project: "House", measured: "outside", close: true, wall_type: "8\" Block Wall",
    segments: [{ length: "30'" }, { length: "24'", turn: "right" }, { length: "30'", turn: "right" }, { length: "24'", turn: "right" }] });
  let house = (await S.readProject(db, uid, "House")).state;
  assert(house.elements.length >= 4 && house.elements.every((e) => e.layerId === "foundation"), "foundation walls tagged");
  // A second floor, then a cabinet drawn on its plan lands on its cabinets layer.
  const two = await call(client, "set_building_inputs", { project: "House", upper_floors: [{ name: "Second floor", top_of_subfloor: "109'-1 1/8\"" }] });
  assert(two.layers.some((x) => x.id === "floor2-plan"));
  assert((await call(client, "set_building_inputs", { project: "House", upper_floors: [{ name: "Low", top_of_subfloor: "90'" }] })).error.includes("higher"));
  // The layer builder switch: saved on the building, the app does the building.
  await call(client, "set_building_inputs", { project: "House", build_from_floor_plan: true });
  assert.strictEqual((await S.readProject(db, uid, "House")).state.building.autoLayers, true);
  await call(client, "set_building_inputs", { project: "House", build_from_floor_plan: false });
  assert.notStrictEqual((await S.readProject(db, uid, "House")).state.building.autoLayers, true);
  await call(client, "set_active_layer", { project: "House", layer: "floor2-plan" });
  const cab = await call(client, "place_symbol", { project: "House", name: "Base 24\"", x: 100, y: 100 });
  house = (await S.readProject(db, uid, "House")).state;
  const cabEl = house.elements[house.elements.length - 1];
  assert.strictEqual(cabEl.layerId, "floor2-cabinets", JSON.stringify(cab));
  // The active layer can't be hidden; others can.
  assert((await call(client, "show_hide_layers", { project: "House", layers: ["floor2-plan"], visible: false })).error.includes("active"));
  assert.deepStrictEqual((await call(client, "show_hide_layers", { project: "House", layers: ["Site plan"], visible: false })).hidden, ["Site plan"]);
  const elec = await call(client, "add_layer", { project: "House", name: "Electrical", level: "First floor" });
  assert.strictEqual(elec.active, true);
  assert((await call(client, "add_layer", { project: "House", name: "electrical" })).error.includes("already"));
  // Back on the first-floor plan, a wall stays untagged (its default), as the app writes it.
  await call(client, "set_active_layer", { project: "House", layer: "First floor plan" });
  await call(client, "place_symbol", { project: "House", name: "2x4 Wall", x: 500, y: 500 });
  house = (await S.readProject(db, uid, "House")).state;
  const plainWall = house.elements[house.elements.length - 1];
  assert.strictEqual(plainWall.layerId, undefined);
  const mv = await call(client, "move_to_layer", { project: "House", ids: [plainWall.id], layer: "Electrical" });
  assert.strictEqual(mv.moved, 1);
  ll = await call(client, "list_levels_and_layers", { project: "House" });
  assert.strictEqual(ll.layers.find((x) => x.name === "Electrical").elements, 1);
  assert.strictEqual(ll.layers.find((x) => x.id === "site").visible, false);
  house = (await S.readProject(db, uid, "House")).state;
  assert.strictEqual(house.building.upperFloors[0].elevationIn, 1309.125);
  assert.strictEqual(house.building.datumIn, undefined, "defaults left out, as the app writes them");
  fs.writeFileSync(path.join(fx, "connector-layers.json"), M.stringifyProject(house) + "\n");
  fs.writeFileSync(path.join(fx, "connector-layers-expect.json"), JSON.stringify({
    note: "Written by functions-mcp/test/sitedraft-mcp.test.js; ConnectorCheckTest checks it with the app's code.",
    layerOf: Object.fromEntries(house.elements.map((e) => [e.id, L.layerOf(e, house).id])),
    activeLayer: L.activeLayer(house).id,
    hidden: L.effectiveLayers(house).filter((x) => !x.visible).map((x) => x.id),
    levels: Object.fromEntries(L.levels(L.settingsOf(house)).map((x) => [x.id, x.elevationIn])),
  }, null, 2) + "\n");

  // ---- voice drawing (Nov 23): room-picked cabinet runs, fronts, facing, the rod, hatch, elevations, roof ----
  await call(client, "create_project", { name: "Voice" });
  const shell = await call(client, "draw_walls", { project: "Voice", measured: "outside", wall_type: "2x6 Wall", close: true, start: { x: 0, y: 0 },
    segments: [{ length: "50'" }, { length: "30'", turn: "right" }, { length: "50'", turn: "right" }, { length: "30'", turn: "right" }] });
  const northWall = shell.created.find((w) => w.runs === "east-west" && w.from.yIn === 0).id;
  await call(client, "draw_walls", { project: "Voice", measured: "inside", start: { x: 6.5, y: 186.5 }, room_side: "left", segments: [{ length: "15'" }, { length: "15'", turn: "left" }] });
  await call(client, "add_label", { project: "Voice", text: "KITCHEN", x: 60, y: 90 });
  await call(client, "add_label", { project: "Voice", text: "BEDROOM", x: 300, y: 90 });
  // The north wall runs the whole house: without a room it warns; with one it starts at that room's corner.
  const noRoom = await call(client, "place_cabinets", { project: "Voice", wall_id: northWall, items: ["B12"] });
  assert(noRoom.warnings && noRoom.warnings[0].includes("more than one room"), JSON.stringify(noRoom));
  await call(client, "delete_elements", { project: "Voice", ids: [noRoom.placed[0].id] });
  const kRun = await call(client, "place_cabinets", { project: "Voice", wall_id: northWall, room: "Kitchen", items: ["LS36", { code: "BD", width: 29 }] });
  assert.strictEqual(kRun.warnings, undefined);
  assert.strictEqual(kRun.placed[1].widthIn, 29);
  assert.strictEqual(kRun.facing, "south");
  const bRun = await call(client, "place_cabinets", { project: "Voice", wall_id: northWall, room: "bedroom", from: "right", items: ["B24"] });
  let vd = await call(client, "read_drawing", { project: "Voice" });
  const atX = (id) => vd.cabinetsAndAppliances.find((c) => c.id === id).at.xIn;
  assert.strictEqual(atX(kRun.placed[0].id), 6.5, "kitchen run starts at the kitchen's inside corner");
  assert.strictEqual(atX(bRun.placed[0].id), 593.5 - 24, "bedroom run ends at the east inside corner");
  assert.strictEqual(vd.cabinetsAndAppliances.find((c) => c.id === kRun.placed[1].id).front, "3 drawers");
  // Fronts on a single cabinet, then changed; facing and the wall it's against come back.
  const base = await call(client, "place_symbol", { project: "Voice", name: "Base 36\"", x: 150, y: 18.5, front: "drawer + doors", style: "shaker" });
  assert.strictEqual(base.placed.facing, "south");
  assert(base.placed.against.includes(northWall));
  const ed = await call(client, "edit_element", { project: "Voice", id: base.placed.id, front: "4 drawers", hardware: "bar pulls" });
  assert.deepStrictEqual(ed.changed, ["front/style/hardware"]);
  assert((await call(client, "place_symbol", { project: "Voice", name: "Washer", x: 50, y: 150, front: "doors" })).error.includes("for cabinets"));
  const washer = await call(client, "place_symbol", { project: "Voice", name: "Washer", x: 20, y: 300, rotation: 270 });
  assert.strictEqual(washer.placed.facing, "east");
  const turned = await call(client, "edit_element", { project: "Voice", id: washer.placed.id, rotation: 90 });
  assert.strictEqual(turned.facing, "west");
  // The plain rod, stretched: its Properties carry the real size.
  const rod = await call(client, "place_symbol", { project: "Voice", name: "Rod 48\"", x: 300, y: 250, width: "5'10 1/2\"" });
  let vs = (await S.readProject(db, uid, "Voice")).state;
  assert.strictEqual(vs.elements.find((e) => e.id === rod.placed.id).properties.widthInches, 70.5);
  const bs = vs.elements.find((e) => e.id === base.placed.id).properties;
  assert.deepStrictEqual([bs.front, bs.doorStyle, bs.hardware], [3, 1, 1]);
  // Hardwood in the kitchen, then again (replaced, not stacked); the bedroom by a point.
  const h1 = await call(client, "hatch_area", { project: "Voice", room: "kitchen", pattern: "hardwood", size: 5, length: 48 });
  assert.strictEqual(h1.hatched.pattern, "PLANKS");
  assert.strictEqual(h1.hatched.area, "15'-0\" x 15'-0\"");
  const h2 = await call(client, "hatch_area", { project: "Voice", room: "Kitchen", pattern: "tile", size: 12 });
  assert.deepStrictEqual(h2.replaced, [h1.hatched.id]);
  await call(client, "hatch_area", { project: "Voice", near: { x: 300, y: 100 }, pattern: "carpet", size: 2 });
  assert((await call(client, "hatch_area", { project: "Voice", room: "Garage", pattern: "tile", size: 12 })).error.includes("No room label"));
  vs = (await S.readProject(db, uid, "Voice")).state;
  const hs = vs.elements.filter((e) => M.kind(e) === "Rectangle");
  assert.strictEqual(hs.length, 2);
  assert.deepStrictEqual(hs.map((h) => h.fill.pattern).sort(), ["DOTS", "TILE"]);
  assert(hs.every((h) => h.strokeWidth === 0));
  // Elevations: the kitchen's north wall, looking north from the room; the island's front.
  const el = await call(client, "add_elevation", { project: "Voice", wall_id: northWall, crown: 3, backsplash: { pattern: "subway", size: 3, length: 6 } });
  assert.strictEqual(el.elevation.looking, "north");
  assert((await call(client, "add_elevation", { project: "Voice", wall_id: northWall, looking: "east" })).error.includes("north or south"));
  const isl = await call(client, "place_symbol", { project: "Voice", name: "Island 3'x6'", x: 100, y: 110 });
  const ie = await call(client, "add_elevation", { project: "Voice", wall_id: isl.placed.id, island_face: "back" });
  assert(ie.elevation.of.includes("back"));
  // Roof and exterior elevations: set on the building; the app builds them.
  const roof = await call(client, "set_roof", { project: "Voice", pitch: "8/12", ends: "hip", overhang: "16\"" });
  assert.strictEqual(roof.roof.pitch, "8/12");
  const ext = await call(client, "set_exterior_elevations", { project: "Voice", grade_exposure: "8\"" });
  assert.strictEqual(ext.gradeExposure, "8\"");
  vd = await call(client, "read_drawing", { project: "Voice" });
  assert.strictEqual(vd.hatches.length, 2);
  assert.strictEqual(vd.hatches.find((h) => h.pattern === "TILE").room, "KITCHEN");
  assert.strictEqual(vd.elevations.length, 2);
  assert.strictEqual(vd.roof.built, false);
  assert.strictEqual(vd.roof.pitch, 8);
  vs = (await S.readProject(db, uid, "Voice")).state;
  assert.deepStrictEqual(vs.building.roof, { pitch: 8, overhangIn: 16, endA: "HIP", endB: "HIP" });
  assert.strictEqual(vs.building.gradeExposureIn, 8);
  fs.writeFileSync(path.join(fx, "connector-voice.json"), M.stringifyProject(vs) + "\n");
  fs.writeFileSync(path.join(fx, "connector-voice-expect.json"), JSON.stringify({
    note: "Written by functions-mcp/test/sitedraft-mcp.test.js; ConnectorCheckTest checks it with the app's code.",
    kitchenRun: kRun.placed.map((p) => p.id), drawerBase: kRun.placed[1].id, base: base.placed.id, rod: rod.placed.id,
    wallElevation: el.elevation.id, islandElevation: ie.elevation.id, northWall,
  }, null, 2) + "\n");
  const rmRoof = await call(client, "set_roof", { project: "Voice", remove: true });
  assert.strictEqual(rmRoof.roof, "removed");
  assert.strictEqual((await S.readProject(db, uid, "Voice")).state.building.roof, undefined);

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

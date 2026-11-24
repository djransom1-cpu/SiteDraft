/**
 * More connector tools (Dan, Nov 23 2026): the beam and posts, the exterior finish (stone
 * wainscot), porches and garages against the house, and the sheet set. Pure: state in,
 * { state, result } out, like tools.js and layers.js.
 */
const M = require("./model");
const G = require("./geometry");
const L = require("./layers");

const fail = (msg) => { throw new M.UserError(msg); };
const len = (v, what) => { const x = M.parseLength(v); if (x == null || isNaN(x)) fail(`${what}: "${v}" isn't a length.`); return x; };
const r2 = (v) => Math.round(v * 100) / 100;
const ink = (state) => (state.currentColor !== undefined ? state.currentColor : M.WHITE);

// ---------------------------------------------------------------- beam and posts (Building.kt StructureSpecs)

const POSTS = { lally: "LALLY", pipe: "LALLY", "steel lally": "LALLY", hss: "HSS", "steel tube": "HSS", tube: "HSS" };

function normShape(s) {
  const m = String(s).trim().toUpperCase().replace(/\s+/g, "").replace(/BY/, "X").match(/^W?(\d+)[X*](\d+(\.\d+)?)$/);
  if (!m) fail(`beam: a steel W shape like W10x15 or W8x18 ("${s}").`);
  return `W${m[1]}x${m[2]}`;
}

function setStructure(project, a) {
  const s = { ...L.settingsOf(project) };
  const st = { ...(s.structure || {}) };
  if (a.beam !== undefined) st.beamShape = a.beam === null || a.beam === "" || String(a.beam).toLowerCase() === "auto" ? null : normShape(a.beam);
  if (a.post_spacing != null) { const ft = len(a.post_spacing, "post_spacing") / 12; if (!(ft >= 4 && ft <= 20)) fail("post_spacing: between 4' and 20'."); st.postSpacingFt = r2(ft); }
  if (a.post_type) { const p = POSTS[String(a.post_type).toLowerCase()]; if (!p) fail("post_type: lally (pipe) or hss (steel tube)."); st.post = p; }
  if (a.max_joist_span != null) st.maxJoistSpanFt = r2(len(a.max_joist_span, "max_joist_span") / 12);
  if (a.live_load_psf != null) st.liveLoadPsf = Number(a.live_load_psf);
  if (a.dead_load_psf != null) st.deadLoadPsf = Number(a.dead_load_psf);
  if (a.soil_bearing_psf != null) st.soilBearingPsf = Number(a.soil_bearing_psf);
  if (a.roof_snow_psf != null) st.roofSnowPsf = Number(a.roof_snow_psf);
  L.materialize(project);
  project.building = { ...s, structure: st, ...(a.build_from_floor_plan === false ? {} : { autoLayers: true }) };
  L.reconcile(project);
  L.compact(project);
  return { state: project, result: { structure: {
    beam: st.beamShape || "sized by Site Draft", postSpacing: st.postSpacingFt != null ? `${st.postSpacingFt}' max` : "12' max (default)",
    post: (st.post || "LALLY") === "LALLY" ? "steel lally column" : "steel tube (HSS)" },
    note: "Site Draft rebuilds the beam, posts and pad footings from the floor plan when it reloads the project. Post spacing is the most allowed; the app spaces them evenly." } };
}

// ---------------------------------------------------------------- exterior finish (Building.kt ExteriorFinish)

const FINISHES = { lap: "LAP", "lap siding": "LAP", vinyl: "LAP", siding: "LAP", "fiber cement": "LAP", "board and batten": "BOARD_BATTEN", "board & batten": "BOARD_BATTEN", batten: "BOARD_BATTEN",
  shake: "SHAKE", shakes: "SHAKE", shingle: "SHAKE", brick: "BRICK", "brick veneer": "BRICK", stone: "STONE", "stone veneer": "STONE" };
const finish = (v, what) => { const f = FINISHES[String(v).toLowerCase()]; if (!f) fail(`${what}: lap siding, board and batten, shake, brick or stone.`); return f; };

function setExteriorFinish(project, a) {
  const s = { ...L.settingsOf(project) };
  const x = { ...(s.exterior || {}) };
  if (a.main) x.main = finish(a.main, "main");
  if (a.wainscot !== undefined) {
    if (a.wainscot === null || String(a.wainscot).toLowerCase() === "none") { x.wainscot = null; }
    else x.wainscot = finish(a.wainscot, "wainscot");
  }
  if (a.wainscot_height != null) x.wainscotHeightIn = len(a.wainscot_height, "wainscot_height");
  if (a.gable !== undefined) x.gable = a.gable === null || String(a.gable).toLowerCase() === "none" ? null : finish(a.gable, "gable");
  if (a.corner_trim != null) x.cornerTrimIn = len(a.corner_trim, "corner_trim");
  L.materialize(project);
  project.building = { ...s, exterior: x };
  L.reconcile(project);
  L.compact(project);
  const lbl = (f) => (f ? f.toLowerCase().replace(/_/g, " ") : "none");
  return { state: project, result: { exterior: { main: lbl(x.main || "LAP"), wainscot: lbl(x.wainscot),
    wainscotHeight: x.wainscot ? `${L.inchText(x.wainscotHeightIn ?? 36)} above the top of the foundation` : undefined, gable: x.gable ? lbl(x.gable) : "same as main" },
    note: "The exterior elevations show it when Site Draft reloads the project." } };
}

// ---------------------------------------------------------------- porches and garages against the house

/** The house: the bounds of the first-floor 2x6 exterior walls, px. */
function houseBounds(state) {
  const ext = G.walls(state).filter((w) => /2x6/i.test(w.symbolName || ""));
  if (!ext.length) fail("There are no 2x6 exterior walls to put it against. Draw the house first.");
  const bs = ext.map(G.bounds);
  return G.rect(Math.min(...bs.map((b) => b.left)), Math.min(...bs.map((b) => b.top)), Math.max(...bs.map((b) => b.right)), Math.max(...bs.map((b) => b.bottom)));
}

/**
 * A rectangle against a side of the house, px: [w] along the wall, [d] out from it. Along the
 * wall it is centred on a door, on a point, or set from the left/top outside corner.
 */
function againstHouse(state, a, w, d, inch) {
  const fp = houseBounds(state);
  const side = String(a.side || "").toLowerCase();
  if (!["north", "south", "east", "west"].includes(side)) fail("side: north, south, east or west (the side of the house it's on).");
  const along = side === "north" || side === "south";
  const lo = along ? fp.left : fp.top, hi = along ? fp.right : fp.bottom;
  let mid;
  if (a.centered_on) {
    const el = (state.elements || []).find((e) => e.id === a.centered_on);
    if (!el) fail(`No element with id "${a.centered_on}" to centre on.`);
    const b = G.bounds(el);
    mid = along ? b.cx : b.cy;
  } else if (a.from_corner != null) {
    mid = lo + len(a.from_corner, "from_corner") * inch + w / 2;
  } else mid = (lo + hi) / 2;
  const s0 = mid - w / 2, s1 = mid + w / 2;
  let r;
  if (side === "north") r = G.rect(s0, fp.top - d, s1, fp.top);
  else if (side === "south") r = G.rect(s0, fp.bottom, s1, fp.bottom + d);
  else if (side === "west") r = G.rect(fp.left - d, s0, fp.left, s1);
  else r = G.rect(fp.right, s0, fp.right + d, s1);
  return { r, side, fromCornerIn: r2((s0 - lo) / inch) };
}

const PORCH_TYPES = { framed: 0, skirt: 0, masonry: 1, block: 1, wood: 2, "wood deck": 2, deck: 2 };
const PORCH_ROOFS = { shed: 0, gable: 1, "gable facing out": 1, "return gable": 1, hip: 2 };
const COLUMNS = { wood: 0, masonry: 1, mix: 2, "masonry corners": 2 };

function placePorch(state, a) {
  const inch = M.pxPerInch(state);
  const wIn = a.width != null ? len(a.width, "width") : 96, dIn = a.depth != null ? len(a.depth, "depth") : 60;
  const { r, side, fromCornerIn } = againstHouse(state, a, wIn * inch, dIn * inch, inch);
  const props = { pcType: 0, pcRoof: 1, pcCols: 0, pcPier: 0 };
  if (a.type) { const t = PORCH_TYPES[String(a.type).toLowerCase()]; if (t == null) fail("type: framed, masonry or wood deck."); props.pcType = t; }
  if (a.roof === "none" || a.roof === false) props.pcRoof = 0;
  else if (a.roof) { const t = PORCH_ROOFS[String(a.roof).toLowerCase()]; if (t == null) fail("roof: shed, gable (facing out), hip or none."); props.pcRoof = 1; props.pcRoofStyle = t; }
  if (a.columns) { const t = COLUMNS[String(a.columns).toLowerCase()]; if (t == null) fail("columns: wood, masonry or mix."); props.pcCols = t; }
  if (a.pier) props.pcPier = /cookie/i.test(a.pier) ? 1 : 0;
  if (a.pitch != null) { const p = Number(String(a.pitch).split("/")[0]); if (!(p > 0 && p <= 24)) fail("pitch: rise per 12."); props.pcPitch = p; }
  const horiz = side === "north" || side === "south";
  const sym = M.makeSymbol({ name: "Porch", category: "Porch", center: { x: r.cx, y: r.cy }, widthPx: r.width, depthPx: r.height, color: ink(state),
    properties: { ...props, widthInches: r2((horiz ? r.width : r.height) / inch), depthInches: r2((horiz ? r.height : r.width) / inch) } });
  const out = { ...state, elements: [...(state.elements || []), sym] };
  const t = ["framed (skirt)", "masonry (cap, veneer)", "wood deck on posts"][props.pcType];
  return { state: out, result: { placed: { id: sym.id, porch: `${L.inchText(wIn).replace(/"$/, "")}" wide x ${L.inchText(dIn).replace(/"$/, "")}" out`, side, fromOutsideCornerIn: fromCornerIn, type: t,
    roof: props.pcRoof ? ["shed", "gable facing out", "hip"][props.pcRoofStyle ?? 0] : "none", columns: ["wood", "masonry", "masonry corners, wood between"][props.pcCols] },
    note: "Site Draft builds the porch's footings or piers on the foundation layer, its roof and its posts when it reloads the project." } };
}

const DOOR_SIDES = { north: 0, south: 1, west: 2, east: 3 };
const GARAGE_ROOFS = { continue: 0, "continue the house roof": 0, gable: 1, "gable turned": 1, hip: 2 };

function placeGarage(state, a) {
  const inch = M.pxPerInch(state);
  const wIn = a.width != null ? len(a.width, "width") : 288, dIn = a.depth != null ? len(a.depth, "depth") : 288;
  const { r, side, fromCornerIn } = againstHouse(state, a, wIn * inch, dIn * inch, inch);
  if (a.detached_gap != null) {
    const g = len(a.detached_gap, "detached_gap") * inch;
    const dx = side === "east" ? g : side === "west" ? -g : 0, dy = side === "south" ? g : side === "north" ? -g : 0;
    Object.assign(r, G.rect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy));
  }
  const props = { gaDoorSide: DOOR_SIDES[side], gaDoor: 0 };
  if (a.door_side) { const t = DOOR_SIDES[String(a.door_side).toLowerCase()]; if (t == null) fail("door_side: north, south, east or west."); props.gaDoorSide = t; }
  if (a.doors) props.gaDoor = /two|2|9/.test(String(a.doors)) ? 1 : 0;
  if (a.roof) { const t = GARAGE_ROOFS[String(a.roof).toLowerCase()]; if (t == null) fail("roof: continue (the house roof), gable (turned 90) or hip."); props.gaRoof = t; }
  const horiz = side === "north" || side === "south";
  const sym = M.makeSymbol({ name: "Garage", category: "Garage", center: { x: r.cx, y: r.cy }, widthPx: r.width, depthPx: r.height, color: ink(state),
    properties: { ...props, widthInches: r2((horiz ? r.width : r.height) / inch), depthInches: r2((horiz ? r.height : r.width) / inch) } });
  return { state: { ...state, elements: [...(state.elements || []), sym] }, result: { placed: { id: sym.id, side, fromOutsideCornerIn: fromCornerIn,
    size: `${r2(wIn / 12)}' x ${r2(dIn / 12)}'`, attached: a.detached_gap == null, doors: props.gaDoor ? "two 9' doors" : "one 16' door",
    doorSide: Object.keys(DOOR_SIDES)[props.gaDoorSide] }, note: "Site Draft builds the garage's walls, slab, footings and roof when it reloads the project." } };
}

// ---------------------------------------------------------------- the sheet set (Sheets.kt)

const PAPER = { "arch d": ["ARCH_D", 36, 24], "24x36": ["ARCH_D", 36, 24], "arch c": ["ARCH_C", 24, 18], "18x24": ["ARCH_C", 24, 18], "arch e1": ["ARCH_E1", 42, 30], "30x42": ["ARCH_E1", 42, 30], tabloid: ["TABLOID", 17, 11], "11x17": ["TABLOID", 17, 11] };
const SCALES = { "1/8": "EIGHTH", "1/4": "QUARTER", "3/8": "THREE_EIGHTHS", "1/2": "HALF", "1/16": "SIXTEENTH" };
const id = () => M.newId();

/**
 * The whole set in Dan's order, one sheet per subject: T-1 cover (legend, symbols), T-2 notes,
 * C-1 site plan (when there is one), S-1 foundation plan, S framing plan per floor, S roof framing,
 * S wall section and structural calcs, S details, A floor plan per floor, A roof plan,
 * A exterior elevations, A details, A door and window schedules. Views sit in the middle of the
 * drawing area; drag them where you like in the app. The app numbers the set as it does any set.
 */
function buildSheets(state, a) {
  const [paper, pw, ph] = PAPER[String(a.paper || "arch d").toLowerCase()] || fail("paper: Arch D (24x36), Arch C (18x24), Arch E1 (30x42) or 11x17.");
  const scale = a.scale ? (SCALES[String(a.scale).replace(/"|\s|=.*$/g, "")] || fail("scale: 1/8, 1/4, 3/8 or 1/2.")) : "QUARTER";
  const strip = pw < 20 ? 1.75 : 2.5;
  const cx = (pw - strip) / 2, cy = ph / 2;
  const layers = L.effectiveLayers(state);
  const lv = L.levels(L.settingsOf(state));
  const els = state.elements || [];
  const count = new Map();
  for (const e of els) { const lid = L.layerOf(e, state).id; count.set(lid, (count.get(lid) || 0) + 1); }
  const has = (ls) => ls.some((l) => count.get(l.id));
  const sheets = [];
  const sheet = (title, views) => sheets.push({ id: id(), number: "", title, paper, orientation: "LANDSCAPE", views });
  const view = (kind, title, extra = {}) => ({ id: id(), kind, title, scale, xIn: cx, yIn: cy, ...extra });
  const skipped = [];

  sheet("Cover Sheet, Legend and Symbols", []);
  sheet("General Notes", []);

  const site = layers.filter((l) => l.trade === "SITE_PLAN");
  if (has(site) || L.settingsOf(state).site) sheet("Site Plan", [view("PLAN", "Site Plan", { layerIds: site.map((l) => l.id), scale: "ENG_20" })]);
  else skipped.push("site plan (nothing on the Site plan layer yet)");

  const onLevel = (levelId, pred = () => true) => layers.filter((l) => l.levelId === levelId && pred(l));
  const found = lv.find((l) => l.kind === "FOUNDATION");
  if (found) {
    const ls = onLevel(found.id);
    if (has(ls)) sheet("Foundation Plan", [view("PLAN", "Foundation Plan", { layerIds: ls.map((l) => l.id) })]); else skipped.push("foundation plan (not built yet)");
  }
  for (const level of lv.filter((l) => l.kind === "FLOOR_FRAMING")) {
    const ls = onLevel(level.id);
    const t = `${level.name.replace(/ framing$/i, "")} Framing Plan`;
    if (has(ls)) sheet(t, [view("PLAN", t, { layerIds: ls.map((l) => l.id) })]); else skipped.push(`${t.toLowerCase()} (not built yet)`);
  }
  const roof = lv.find((l) => l.kind === "ROOF");
  const roofFraming = roof ? onLevel(roof.id, (l) => l.trade !== "ROOF_PLAN") : [];
  const roofPlan = roof ? onLevel(roof.id, (l) => l.trade === "ROOF_PLAN") : [];
  if (has(roofFraming)) sheet("Roof Framing Plan", [view("PLAN", "Roof Framing Plan", { layerIds: roofFraming.map((l) => l.id) })]);
  else if (roof) skipped.push("roof framing plan (no roof yet: set_roof)");

  const symOf = (pred) => els.filter((e) => M.kind(e) === "Symbol" && pred(e));
  const section = symOf((e) => e.category === "Details" && /section/i.test(e.symbolName || ""));
  const secViews = [view("SECTION", "Typical Wall Section", section[0] ? { elementId: section[0].id, scale: "HALF", xIn: cx * 0.55 } : { scale: "HALF", xIn: cx * 0.55 }),
    view("STRUCTURAL_CALCS", "Structural Calculations", { xIn: cx * 1.45 })];
  sheet("Wall Section and Structural Calculations", secViews);

  const details = symOf((e) => e.category === "Details" && !/section/i.test(e.symbolName || ""));
  if (details.length) {
    const cols = Math.min(3, details.length), rows = Math.ceil(details.length / cols);
    sheet("Structural Details", details.map((d, i) => view("ELEVATION", d.properties && d.properties.title ? d.properties.title : `Detail ${i + 1}`,
      { elementId: d.id, scale: "HALF", xIn: ((i % cols) + 0.5) * (pw - strip) / cols, yIn: (Math.floor(i / cols) + 0.5) * ph / rows })));
  }

  for (const level of lv.filter((l) => l.kind === "FLOOR")) {
    const ls = onLevel(level.id);
    const t = `${level.name} Plan`;
    if (has(ls)) sheet(t, [view("PLAN", t, { layerIds: ls.map((l) => l.id) })]);
  }
  if (has(roofPlan)) sheet("Roof Plan", [view("PLAN", "Roof Plan", { layerIds: roofPlan.map((l) => l.id) })]);

  const FACES = ["South Elevation", "North Elevation", "East Elevation", "West Elevation"];
  const ext = symOf((e) => e.category === "Exterior" && e.symbolName === "Exterior Elevation")
    .sort((p, q) => ((p.properties || {}).face || 0) - ((q.properties || {}).face || 0));
  if (ext.length) {
    sheet("Exterior Elevations", ext.map((e, i) => view("ELEVATION", FACES[(e.properties || {}).face || 0] || `Elevation ${i + 1}`,
      { elementId: e.id, xIn: ((i % 2) + 0.5) * (pw - strip) / 2, yIn: (Math.floor(i / 2) + 0.5) * ph / 2 })));
  } else skipped.push("exterior elevations (none yet: set_exterior_elevations)");

  const interior = els.filter((e) => M.kind(e) === "ElevationView");
  if (interior.length) {
    const cols = Math.min(3, interior.length), rows = Math.ceil(interior.length / cols);
    sheet("Interior Elevations", interior.map((e, i) => view("ELEVATION", e.title || e.name || `Elevation ${i + 1}`,
      { elementId: e.id, scale: "HALF", xIn: ((i % cols) + 0.5) * (pw - strip) / cols, yIn: (Math.floor(i / cols) + 0.5) * ph / rows })));
  }

  sheet("Door and Window Schedules", [view("DOOR_SCHEDULE", "Door Schedule", { yIn: ph * 0.3 }), view("WINDOW_SCHEDULE", "Window Schedule", { yIn: ph * 0.7 })]);

  // Numbered in the set's order; the app renumbers the same way when it opens them.
  const counters = {};
  const series = (t) => (/cover|notes/i.test(t) ? "T" : /site/i.test(t) ? "C" : /foundation|framing|section|structural/i.test(t) ? "S" : "A");
  for (const s of sheets) { const k = series(s.title); counters[k] = (counters[k] || 0) + 1; s.number = `${k}-${counters[k]}`; }

  const existing = state.sheets || [];
  let kept = existing;
  if (existing.length && !a.replace) {
    const titles = new Set(existing.map((s) => String(s.title).toLowerCase()));
    const add = sheets.filter((s) => !titles.has(s.title.toLowerCase()));
    kept = [...existing, ...add];
    return { state: { ...state, sheets: kept }, result: { added: add.map((s) => `${s.number} ${s.title}`), alreadyThere: existing.map((s) => `${s.number} ${s.title}`), notMade: skipped,
      note: "Kept the sheets already there and added the rest; the app renumbers the set. replace: true starts the set over." } };
  }
  return { state: { ...state, sheets }, result: { sheets: sheets.map((s) => `${s.number} ${s.title}`), notMade: skipped, replaced: existing.length || undefined,
    note: "Open Sheets in Site Draft; the views sit in the middle of each sheet, drag them where you want them. The set numbers itself in Dan's order." } };
}

function listSheets(state) {
  return { sheets: (state.sheets || []).map((s) => ({ id: s.id, number: s.number, title: s.title, paper: s.paper || "ARCH_D", views: (s.views || []).map((v) => `${v.title} (${(v.kind || "").toLowerCase()}, ${v.scale || "QUARTER"})`) })) };
}

module.exports = { setStructure, setExteriorFinish, placePorch, placeGarage, buildSheets, listSheets, houseBounds };

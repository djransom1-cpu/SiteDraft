/**
 * Stairs, the way the app works them out (shared/.../ui/canvas/Stairs.kt, Nov 24):
 *  - up or down from the floor they are drawn on (auto: up when there is a floor above, otherwise
 *    down to the floor below or the basement slab);
 *  - the rise from the building unless typed, risers as close to 7 1/2" as it allows (6 1/4"..8 1/4");
 *  - the plan footprint from the risers and treads.
 * The app opens the floor framing for each stair itself when it next loads the project.
 * The test checks these against samples the app exported into catalog.json (catalog.stairs).
 */
const M = require("./model.js");
const L = require("./layers.js");

const S = M.catalog.stairs || { defaults: {}, keys: {}, headroomIn: 80, samples: [] };
const K = S.keys;
const TYPES = { straight: "Straight Stair", l: "L Stair (landing)", u: "U Stair (landing)", deck: "Deck Stair (open)", spiral: "Spiral Stair", curved: "Curved Stair" };
const typeOf = (name) => ({ "L Stair (landing)": "L", "U Stair (landing)": "U", "Deck Stair (open)": "DECK", "Spiral Stair": "SPIRAL", "Curved Stair": "CURVED" })[name] || "STRAIGHT";

const prop = (s, key) => { const p = s.properties || {}; return p[key] !== undefined ? Number(p[key]) : Number((S.defaults[s.symbolName] || {})[key] || 0); };

/** Riser count for a rise: nearest to 7 1/2" each, kept within 6 1/4".. 8 1/4" (Stairs.riserCount). */
function riserCount(rise) {
  if (rise <= 8.25) return 1;
  let n = Math.max(1, Math.round(rise / 7.5));
  while (rise / n > 8.25) n++;
  while (n > 1 && rise / n < 6.25) n--;
  return n;
}

function layout(s) {
  const rise = Math.max(1, prop(s, K.rise));
  const n = riserCount(rise);
  const t = typeOf(s.symbolName);
  let f1 = n;
  if (t === "L" || t === "U") {
    const chosen = Math.trunc(prop(s, K.flight1));
    f1 = (chosen >= 1 && chosen < n) ? chosen : Math.trunc((n + 1) / 2);
    f1 = Math.min(Math.max(f1, 1), Math.max(n - 1, 1));
  }
  const width = Math.max(12, prop(s, K.width));
  const tread = Math.max(4, prop(s, K.tread));
  return { risers: n, riser: rise / n, rise, tread, width, treads: n - 1, treads1: Math.max(f1 - 1, 0), treads2: Math.max(n - f1 - 1, 0) };
}

/** Plan footprint [width, depth] in inches (Stairs.footprintIn); curved stairs are the app's to size. */
function footprint(s) {
  const l = layout(s), w = l.width;
  switch (typeOf(s.symbolName)) {
    case "L": return [w + l.treads2 * l.tread, l.treads1 * l.tread + w];
    case "U": return [2 * w, Math.max(l.treads1, l.treads2) * l.tread + w];
    case "SPIRAL": return [w, w];
    case "CURVED": return null;
    default: return [w, Math.max(l.treads * l.tread, l.tread)];
  }
}

function floorOf(s, project) {
  const layer = L.layerOf(s, project);
  if (!layer || layer.trade !== "FLOOR_PLAN" || !layer.levelId) return null;
  const n = parseInt(String(layer.levelId).replace(/^floor/, ""), 10);
  return isNaN(n) ? null : n;
}

const floorElevation = (b, n) => (n === 1 ? b.datumIn : n >= 2 ? ((b.upperFloors || [])[n - 2] || {}).elevationIn ?? null : null);
const basementFloor = (b) => b.datumIn - b.subfloorIn - b.joistDepthIn - b.sillIn - b.blockCourses * b.courseIn + (b.basementSlabIn ?? 4);

function goesDown(s, project) {
  const g = Math.trunc(prop(s, K.goes));
  if (g === 1) return false;
  if (g === 2) return true;
  if (typeOf(s.symbolName) === "DECK") return false;
  const n = floorOf(s, project);
  if (n == null) return false;
  const b = L.settingsOf(project);
  if (floorElevation(b, n + 1) != null) return false;
  return n > 1 || b.foundation === "BASEMENT";
}

/** The rise from the building (Stairs.autoRiseIn), or null when typed, a deck stair, or not on a floor plan. */
function autoRise(s, project) {
  if (typeOf(s.symbolName) === "DECK" || Math.trunc(prop(s, K.riseSet)) === 1) return null;
  const n = floorOf(s, project);
  if (n == null) return null;
  const b = L.settingsOf(project);
  const here = floorElevation(b, n);
  if (here == null) return null;
  if (goesDown(s, project)) {
    if (n === 1) return b.foundation === "BASEMENT" ? here - basementFloor(b) : null;
    const below = floorElevation(b, n - 1);
    return below == null ? null : here - below;
  }
  const above = floorElevation(b, n + 1) ?? (here + b.plateHeightIn + b.joistDepthIn + b.subfloorIn);
  return above - here;
}

/** The floor opening's length and layer (Stairs.floorOpening), for describing; the app draws it. */
function opening(s, project) {
  const t = typeOf(s.symbolName);
  if (t === "DECK") return null;
  const n = floorOf(s, project);
  if (n == null) return null;
  const layerId = `floor${goesDown(s, project) ? n : n + 1}-structural`;
  if (!L.effectiveLayers(project).some((l) => l.id === layerId)) return null;
  const b = L.settingsOf(project), l = layout(s);
  const fp = footprint(s);
  const depth = fp ? fp[1] : null;
  let length = depth;
  if (t === "STRAIGHT") length = Math.min(Math.ceil((S.headroomIn + b.subfloorIn + b.joistDepthIn) * l.tread / l.riser - 1e-6), depth);
  return { layerId, widthIn: fp ? fp[0] : null, lengthIn: length };
}

/** Brings one stair in step (Stairs.sync): DIR, and the rise unless typed. Mutates and returns it. */
function syncOne(s, project) {
  s.properties = { ...(s.properties || {}) };
  s.properties[K.dir] = goesDown(s, project) ? 2 : 1;
  const r = autoRise(s, project);
  if (r != null && r > 0) s.properties[K.rise] = Math.round(r * 1e4) / 1e4;
  return s;
}

const r2 = (v) => Math.round(v * 100) / 100;

function describe(s, project) {
  const l = layout(s), down = prop(s, K.dir) === 2;
  const op = opening(s, project);
  return {
    id: s.id, type: s.symbolName, goes: down ? "down" : "up",
    risers: l.risers, riserIn: r2(l.riser), treads: l.treads, treadIn: r2(l.tread), totalRiseIn: r2(l.rise), widthIn: r2(l.width),
    rise: Math.trunc(prop(s, K.riseSet)) === 1 ? "typed by hand" : "follows the building",
    planLabel: (down ? "DN" : "UP") + (Math.trunc(prop(s, K.showR)) === 1 ? ` ${l.risers}R` : ""),
    floorOpening: op ? { layer: op.layerId, widthIn: r2(op.widthIn), lengthIn: r2(op.lengthIn) } : "none (a deck stair, or no floor framing layer there)",
  };
}

const COMPASS = { north: 0, east: 90, south: 180, west: 270 };

/**
 * Places a stair. climbs = the way you walk going UP it (north = up the page). top = the middle of
 * its top edge, where it meets the upper floor (straight and deck stairs); or center = the middle
 * of its footprint (any stair).
 */
function placeStairs(project, a, len, ink) {
  const name = TYPES[String(a.type || "straight").toLowerCase()];
  if (!name) throw new M.UserError("type must be straight, l, u, spiral or deck.");
  if (name === TYPES.curved) throw new M.UserError("Curved stairs need their radii set in the app for now.");
  const inch = M.pxPerInch(project);
  const props = { ...(S.defaults[name] || {}) };
  if (a.goes !== undefined) props[K.goes] = { auto: 0, up: 1, down: 2 }[String(a.goes).toLowerCase()] ?? (() => { throw new M.UserError("goes must be auto, up or down."); })();
  if (a.width !== undefined) props[K.width] = len(a.width, "width");
  if (a.tread !== undefined) props[K.tread] = len(a.tread, "tread");
  if (a.rise !== undefined) { props[K.rise] = len(a.rise, "rise"); props[K.riseSet] = 1; }
  if (a.turn !== undefined) props[K.turn] = String(a.turn).toLowerCase() === "left" ? 1 : 0;
  if (a.show_risers !== undefined) props[K.showR] = a.show_risers ? 1 : 0;
  const rotation = COMPASS[String(a.climbs || "north").toLowerCase()];
  if (rotation === undefined) throw new M.UserError("climbs must be north, south, east or west (the way you walk going up).");
  // Footprint first at the origin, on the layer it will go on, so the rise comes from the right floor.
  const sym = M.makeSymbol({ name, category: "Stairs", center: { x: 0, y: 0 }, widthPx: 36 * inch, depthPx: 120 * inch, rotation, color: ink, properties: props });
  sym.layerId = L.layerForNew(sym, project);
  syncOne(sym, project);
  const [wIn, dIn] = footprint(sym);
  sym.size = M.sizeText(wIn * inch, dIn * inch);
  sym.properties.widthInches = r2(wIn); sym.properties.depthInches = r2(dIn);
  let c;
  if (a.center) c = { x: len(a.center.x, "center.x") * inch, y: len(a.center.y, "center.y") * inch };
  else if (a.top) {
    if (!["STRAIGHT", "DECK"].includes(typeOf(name))) throw new M.UserError("Give center for an L, U or spiral stair (top is for straight and deck stairs).");
    const t = { x: len(a.top.x, "top.x") * inch, y: len(a.top.y, "top.y") * inch };
    const h = dIn * inch / 2, r = rotation * Math.PI / 180;
    // The top edge is the top of the unrotated symbol: the centre is half the depth back from it.
    c = { x: t.x - h * Math.sin(r), y: t.y + h * Math.cos(r) };
  } else throw new M.UserError("Say where: top (the middle of the top edge) or center.");
  sym.center = M.ptText(c);
  const out = { ...project, elements: [...(project.elements || []), sym] };
  return { state: out, result: { placed: describe(sym, out), at: { centerXIn: r2(c.x / inch), centerYIn: r2(c.y / inch) }, footprintIn: { width: r2(wIn), depth: r2(dIn) },
    note: "Site Draft opens the floor framing for it when Build from the floor plan is on." } };
}

/** Changes a stair: goes, rise (typed, or "building" to follow it again), width, tread, show_risers. */
function editStairs(project, a, len) {
  const s = (project.elements || []).find((e) => e.id === a.id);
  if (!s || M.kind(s) !== "Symbol" || s.category !== "Stairs") throw new M.UserError(`No stair with id "${a.id}".`);
  const e = JSON.parse(JSON.stringify(s, (k, v) => (typeof v === "bigint" ? { __big: v.toString() } : v)), (k, v) => (v && v.__big ? BigInt(v.__big) : v));
  e.properties = { ...(e.properties || {}) };
  if (a.goes !== undefined) e.properties[K.goes] = { auto: 0, up: 1, down: 2 }[String(a.goes).toLowerCase()] ?? (() => { throw new M.UserError("goes must be auto, up or down."); })();
  if (a.rise !== undefined) {
    if (String(a.rise).toLowerCase() === "building") e.properties[K.riseSet] = 0;
    else { e.properties[K.rise] = len(a.rise, "rise"); e.properties[K.riseSet] = 1; }
  }
  if (a.width !== undefined) e.properties[K.width] = len(a.width, "width");
  if (a.tread !== undefined) e.properties[K.tread] = len(a.tread, "tread");
  if (a.show_risers !== undefined) e.properties[K.showR] = a.show_risers ? 1 : 0;
  syncOne(e, project);
  const fp = footprint(e);
  if (fp) { const inch = M.pxPerInch(project); e.size = M.sizeText(fp[0] * inch, fp[1] * inch); e.properties.widthInches = r2(fp[0]); e.properties.depthInches = r2(fp[1]); }
  const out = { ...project, elements: project.elements.map((x) => (x.id === e.id ? e : x)) };
  return { state: out, result: { edited: describe(e, out) } };
}

module.exports = { TYPES, riserCount, layout, footprint, goesDown, autoRise, opening, syncOne, describe, placeStairs, editStairs, typeOf };

/**
 * Levels and layers, the way the app works them out (shared/.../ui/canvas/Building.kt).
 *
 *  - project.building: the inputs (datum = top of first-floor subfloor, subfloor, joist, sill,
 *    block courses, footing...). Missing fields take the app's defaults (catalog.building.defaults).
 *  - project.layers: the layer list; empty or missing = the defaults for the building.
 *  - project.activeLayerId: the layer new drawing goes on; missing = the first-floor plan.
 *  - element.layerId: the layer an element is on; missing = its default (cabinets on the
 *    first-floor cabinets layer, furniture on furniture, everything else on the first-floor plan).
 *
 * The test (test/sitedraft-mcp.test.js) checks these against samples the app exported into
 * catalog.json, so the two never disagree.
 */
const M = require("./model.js");

const B = M.catalog.building;
const FLOOR1_PLAN = B.floor1Plan;
const FLOOR1_CABINETS = B.floor1Cabinets;
const FLOOR1_FURNITURE = B.floor1Furniture;

const LABEL = {
  FOUNDATION: "Foundation and foundation walls", FLOOR_STRUCTURAL: "Floor structural", FLOOR_PLAN: "Floor plan",
  CABINETS: "Cabinets", FURNITURE: "Furniture", ROOF_STRUCTURAL: "Roof structural", ROOF_PLAN: "Roof plan",
  EXTERIOR_ELEVATION: "Exterior elevation", SITE_PLAN: "Site plan", CUSTOM: "Custom",
};
const ORDINAL = ["First", "Second", "Third", "Fourth", "Fifth"];
const ordinal = (n) => ORDINAL[n - 1] || `Floor ${n}`;
const floorId = (n) => `floor${n}`;
const framingId = (n) => `floor${n}-framing`;

function settingsOf(project) {
  return { ...B.defaults, ...(project.building || {}) };
}

const fmt = (inches) => M.formatLength(inches);
function inchText(v) {
  // As the app's formatInches: whole and fraction, no feet.
  let s = Math.round(v * 16);
  const whole = Math.floor(s / 16);
  let rest = s - whole * 16, frac = "";
  if (rest) { let den = 16; while (rest % 2 === 0) { rest /= 2; den /= 2; } frac = `${whole ? " " : ""}${rest}/${den}`; }
  return `${whole || !frac ? whole : ""}${frac}"`;
}

const foundationTop = (s) => s.datumIn - s.subfloorIn - s.joistDepthIn - s.sillIn;
const sillTop = (s) => s.datumIn - s.subfloorIn - s.joistDepthIn;
const footingTop = (s) => foundationTop(s) - s.blockCourses * s.courseIn;
const footingBottom = (s) => footingTop(s) - s.footingThicknessIn;

/** The levels, lowest first, as Building.levels. */
function levels(s) {
  const out = [];
  if (s.foundation !== "SLAB") {
    out.push({ id: "footing", name: "Footing", kind: "FOOTING", elevationIn: footingTop(s), note: `Top of footing; bottom at ${fmt(footingBottom(s))}` });
    if (s.foundation === "CRAWL") {
      out.push({ id: "crawl-floor", name: "Crawl space floor", kind: "CRAWL_FLOOR", elevationIn: sillTop(s) - s.crawlClearanceIn, note: `${inchText(s.crawlClearanceIn)} below the bottom of the joists` });
    }
    out.push({ id: "foundation", name: s.foundation === "BASEMENT" ? "Foundation (basement)" : "Foundation (crawl space)", kind: "FOUNDATION", elevationIn: foundationTop(s), note: "Top of foundation wall" });
    out.push({ id: framingId(1), name: "First floor framing", kind: "FLOOR_FRAMING", elevationIn: sillTop(s), note: "Top of sill, under the joists" });
  }
  out.push({ id: "floor1", name: "First floor", kind: "FLOOR", elevationIn: s.datumIn, note: s.foundation === "SLAB" ? "Top of slab" : "Top of subfloor" });
  (s.upperFloors || []).forEach((f, i) => {
    const n = i + 2;
    out.push({ id: framingId(n), name: `${f.name} framing`, kind: "FLOOR_FRAMING", elevationIn: f.elevationIn - s.subfloorIn - s.joistDepthIn, note: "Top of plate, under the joists" });
    out.push({ id: floorId(n), name: f.name, kind: "FLOOR", elevationIn: f.elevationIn, note: "Top of subfloor" });
  });
  out.push({ id: "roof", name: "Roof", kind: "ROOF", elevationIn: s.roofPlateIn ?? null, note: s.roofPlateIn == null ? "Set when the roof is set up" : "Top of plate" });
  return out;
}

const floorName = (s, n) => (n === 1 ? "First floor" : (s.upperFloors || [])[n - 2]?.name || `${ordinal(n)} floor`);

function floorLayers(n, name, s) {
  const f = floorId(n);
  const structural = n === 1 && s.foundation === "SLAB" ? [] : [{ id: `${f}-structural`, name: `${name} structural`, trade: "FLOOR_STRUCTURAL", levelId: framingId(n), visible: true }];
  return [...structural,
    { id: `${f}-plan`, name: `${name} plan`, trade: "FLOOR_PLAN", levelId: f, visible: true },
    { id: `${f}-cabinets`, name: `${name} cabinets`, trade: "CABINETS", levelId: f, visible: true },
    { id: `${f}-furniture`, name: `${name} furniture`, trade: "FURNITURE", levelId: f, visible: true }];
}

/** The default layers, as Building.defaultLayers. */
function defaultLayers(s) {
  const out = [];
  if (s.foundation !== "SLAB") out.push({ id: "foundation", name: LABEL.FOUNDATION, trade: "FOUNDATION", levelId: "foundation", visible: true });
  const floors = 1 + (s.upperFloors || []).length;
  for (let n = 1; n <= floors; n++) out.push(...floorLayers(n, floorName(s, n), s));
  out.push({ id: "roof-structural", name: LABEL.ROOF_STRUCTURAL, trade: "ROOF_STRUCTURAL", levelId: "roof", visible: true });
  out.push({ id: "roof-plan", name: LABEL.ROOF_PLAN, trade: "ROOF_PLAN", levelId: "roof", visible: true });
  out.push({ id: "exterior", name: LABEL.EXTERIOR_ELEVATION, trade: "EXTERIOR_ELEVATION", levelId: null, visible: true });
  out.push({ id: "site", name: LABEL.SITE_PLAN, trade: "SITE_PLAN", levelId: null, visible: true });
  return out;
}

/** A saved layer with the app's defaults filled in (fields at their default are left out of the file). */
const full = (l) => ({ levelId: null, visible: true, ...l });

function effectiveLayers(project) {
  return (project.layers && project.layers.length) ? project.layers.map(full) : defaultLayers(settingsOf(project));
}

function activeLayer(project) {
  const all = effectiveLayers(project);
  return all.find((l) => l.id === project.activeLayerId) || all.find((l) => l.id === FLOOR1_PLAN) || all[0];
}

function defaultLayerId(e) {
  if (M.kind(e) === "Symbol" && (e.category === "Cabinets" || e.category === "Closet")) return FLOOR1_CABINETS;
  if (M.kind(e) === "Symbol" && e.category === "Furniture") return FLOOR1_FURNITURE;
  return FLOOR1_PLAN;
}

function layerOf(e, project) {
  const all = effectiveLayers(project);
  const id = e.layerId || defaultLayerId(e);
  return all.find((l) => l.id === id) || all.find((l) => l.id === defaultLayerId(e)) || all.find((l) => l.id === FLOOR1_PLAN) || all[0];
}

/** Where a new element goes: the active layer, a cabinet or furniture on a floor plan to that floor's own layer. */
function layerForNew(e, project) {
  const active = activeLayer(project);
  if (active.trade === "FLOOR_PLAN" && M.kind(e) === "Symbol") {
    const trade = (e.category === "Cabinets" || e.category === "Closet") ? "CABINETS" : e.category === "Furniture" ? "FURNITURE" : null;
    if (trade) { const l = effectiveLayers(project).find((x) => x.trade === trade && x.levelId === active.levelId); if (l) return l.id; }
  }
  return active.id;
}

/** Tags the elements not in [beforeIds] that have no layer, as the app does when anything is drawn. */
function tagNew(beforeIds, project) {
  for (const e of project.elements || []) {
    if (e.layerId || beforeIds.has(e.id)) continue;
    const id = layerForNew(e, project);
    if (id !== defaultLayerId(e)) e.layerId = id;
  }
  return project;
}

/** Writes the layer list out so it can be edited (it was the defaults). */
function materialize(project) {
  if (!project.layers || !project.layers.length) project.layers = defaultLayers(settingsOf(project));
  else project.layers = project.layers.map(full);
  return project;
}

/** After new inputs: floors' layers added or kept, custom layers, names and visibility kept (Layers.reconcile). */
function reconcile(project) {
  const wanted = defaultLayers(settingsOf(project));
  const old = new Map(project.layers.map((l) => [l.id, l]));
  const builtIn = wanted.map((w) => (old.has(w.id) ? { ...w, visible: old.get(w.id).visible, name: old.get(w.id).name } : w));
  const keep = project.layers.filter((l) => l.trade !== "CUSTOM" && !wanted.some((w) => w.id === l.id) && (project.elements || []).some((e) => e.layerId === l.id));
  project.layers = [...builtIn, ...keep, ...project.layers.filter((l) => l.trade === "CUSTOM")];
  return project;
}

function validate(s) {
  if (s.subfloorIn < 0 || s.joistDepthIn < 0 || s.sillIn < 0) return "Sizes can't be negative";
  if (s.blockCourses < 0 || !Number.isInteger(s.blockCourses)) return "Block courses must be a whole number";
  if (!(s.courseIn > 0)) return "A block course needs a height";
  if (s.footingThicknessIn < 0 || s.footingWidthIn < 0) return "Footing sizes can't be negative";
  if (s.crawlClearanceIn < 0) return "Crawl clearance can't be negative";
  const ups = s.upperFloors || [];
  if (ups.some((f) => !String(f.name || "").trim())) return "Every floor needs a name";
  let below = s.datumIn;
  for (const f of ups) { if (!(f.elevationIn > below)) return "Each floor has to sit higher than the one below it"; below = f.elevationIn; }
  return null;
}

/** Finds a layer by id or name (any case). */
function findLayer(project, idOrName) {
  const k = String(idOrName || "").trim().toLowerCase();
  const all = effectiveLayers(project);
  const hit = all.find((l) => l.id.toLowerCase() === k) || all.find((l) => l.name.toLowerCase() === k);
  if (!hit) throw new M.UserError(`No layer "${idOrName}". Layers: ${all.map((l) => `${l.name} (${l.id})`).join(", ")}`);
  return hit;
}

/** Only the fields that differ from the app's defaults, the way the app writes the file. */
function compactSettings(s) {
  const out = {};
  for (const [k, v] of Object.entries(s)) {
    const d = B.defaults[k];
    if (JSON.stringify(v) !== JSON.stringify(d)) out[k] = v;
  }
  return out;
}
function compactLayer(l) {
  const o = { id: l.id, name: l.name, trade: l.trade };
  if (l.levelId != null) o.levelId = l.levelId;
  if (l.visible === false) o.visible = false;
  return o;
}
/** The project as the app would save it: defaults left out. */
function compact(project) {
  if (project.building) {
    const b = compactSettings(settingsOf(project));
    if (Object.keys(b).length) project.building = b; else delete project.building;
  }
  if (project.layers) project.layers = project.layers.map(compactLayer);
  return project;
}

// ---------------------------------------------------------------- tools (pure: state in, { state, result } out)

/** Levels with their elevations, and layers with what is on them. */
function describe(project) {
  const s = settingsOf(project);
  const active = activeLayer(project);
  const counts = new Map();
  for (const e of project.elements || []) { const id = layerOf(e, project).id; counts.set(id, (counts.get(id) || 0) + 1); }
  return {
    building: {
      foundation: s.foundation, firstFloorTopOfSubfloor: fmt(s.datumIn), subfloor: inchText(s.subfloorIn), joistDepth: inchText(s.joistDepthIn),
      sill: inchText(s.sillIn), blockCourses: s.blockCourses, courseHeight: inchText(s.courseIn), crawlClearance: inchText(s.crawlClearanceIn),
      footing: `${inchText(s.footingThicknessIn)} thick x ${inchText(s.footingWidthIn)} wide`,
      upperFloors: (s.upperFloors || []).map((f) => ({ name: f.name, topOfSubfloor: fmt(f.elevationIn) })),
      roof: s.roof ? s.roof : "not set up",
      exteriorElevationsGradeExposure: s.gradeExposureIn == null ? "not set up" : inchText(s.gradeExposureIn),
      buildFromFloorPlan: !!s.autoLayers,
    },
    levels: levels(s).slice().reverse().map((l) => ({ id: l.id, name: l.name, elevation: l.elevationIn == null ? "not set" : fmt(l.elevationIn), note: l.note })),
    layers: effectiveLayers(project).map((l) => ({ id: l.id, name: l.name, trade: l.trade, level: l.levelId, visible: l.visible, active: l.id === active.id, elements: counts.get(l.id) || 0 })),
    activeLayer: active.id,
    note: "New drawing goes on the active layer; a cabinet or furniture drawn on a floor plan goes on that floor's cabinets or furniture layer. Layers on lower levels show faint in the app.",
  };
}

function setActiveLayer(project, a) {
  const layer = findLayer(project, a.layer);
  if (!layer.visible) { materialize(project); project.layers.find((l) => l.id === layer.id).visible = true; }
  project.activeLayerId = layer.id;
  compact(project);
  return { state: project, result: { activeLayer: layer.id, name: layer.name } };
}

function showHideLayers(project, a) {
  const targets = (a.layers || []).map((x) => findLayer(project, x));
  const active = activeLayer(project);
  if (!a.visible && targets.some((l) => l.id === active.id)) throw new M.UserError(`${active.name} is the active layer and can't be hidden. Set another layer active first.`);
  materialize(project);
  for (const t of targets) project.layers.find((l) => l.id === t.id).visible = !!a.visible;
  compact(project);
  return { state: project, result: { [a.visible ? "shown" : "hidden"]: targets.map((t) => t.name) } };
}

function addLayer(project, a) {
  const name = String(a.name || "").trim();
  if (!name) throw new M.UserError("Give the layer a name.");
  const all = effectiveLayers(project);
  if (all.some((l) => l.name.toLowerCase() === name.toLowerCase())) throw new M.UserError(`There is already a layer called ${name}.`);
  const lv = levels(settingsOf(project));
  const levelId = a.level ? (lv.find((l) => l.id === a.level || l.name.toLowerCase() === String(a.level).toLowerCase()) || {}).id : null;
  if (a.level && !levelId) throw new M.UserError(`No level "${a.level}". Levels: ${lv.map((l) => l.name).join(", ")}`);
  materialize(project);
  const id = "custom-" + M.newId().slice(0, 8);
  project.layers.push({ id, name, trade: "CUSTOM", levelId, visible: true });
  const makeActive = a.make_active !== false;
  if (makeActive) project.activeLayerId = id;
  compact(project);
  return { state: project, result: { added: id, name, level: levelId, active: makeActive } };
}

/** New building inputs; lengths as people say them (100'-0", 3/4", 9 1/4"). Only what is given changes. */
function setBuildingInputs(project, a) {
  const s = { ...settingsOf(project) };
  const len = (v, what) => { const x = M.parseLength(v); if (x == null || isNaN(x)) throw new M.UserError(`${what}: "${v}" isn't a length.`); return x; };
  if (a.foundation) s.foundation = { basement: "BASEMENT", crawl: "CRAWL", slab: "SLAB" }[a.foundation];
  if (a.first_floor_top_of_subfloor != null) s.datumIn = len(a.first_floor_top_of_subfloor, "First floor");
  if (a.subfloor != null) s.subfloorIn = len(a.subfloor, "Subfloor");
  if (a.joist_depth != null) s.joistDepthIn = len(a.joist_depth, "Joist depth");
  if (a.sill != null) s.sillIn = len(a.sill, "Sill");
  if (a.block_courses != null) s.blockCourses = a.block_courses;
  if (a.course_height != null) s.courseIn = len(a.course_height, "Course height");
  if (a.crawl_clearance != null) s.crawlClearanceIn = len(a.crawl_clearance, "Crawl clearance");
  if (a.footing_thickness != null) s.footingThicknessIn = len(a.footing_thickness, "Footing thickness");
  if (a.footing_width != null) s.footingWidthIn = len(a.footing_width, "Footing width");
  if (a.build_from_floor_plan != null) s.autoLayers = !!a.build_from_floor_plan;
  if (a.show_existing_framing != null) s.showExistingFraming = !!a.show_existing_framing;
  if (a.upper_floors) s.upperFloors = a.upper_floors.map((f) => ({ name: String(f.name || "").trim(), elevationIn: len(f.top_of_subfloor, f.name || "Floor") }));
  const bad = validate(s);
  if (bad) throw new M.UserError(bad);
  materialize(project);
  project.building = s;
  reconcile(project);
  compact(project);
  return { state: project, result: describe(project) };
}

const COVERINGS = { shingles: "ARCH_SHINGLES", "architectural shingles": "ARCH_SHINGLES", "standing seam": "STANDING_SEAM", "standing seam metal": "STANDING_SEAM",
  metal: "EXPOSED_FASTENER", "exposed fastener": "EXPOSED_FASTENER", "exposed-fastener metal": "EXPOSED_FASTENER", "cedar shakes": "CEDAR_SHAKES", shakes: "CEDAR_SHAKES",
  "spanish tile": "SPANISH_TILE", tile: "SPANISH_TILE", slate: "SLATE" };

/**
 * The roof (Building.kt RoofSettings; RoofPlan draws it over the first-floor 2x6 exterior walls):
 * trusses or rafters, pitch (rise per 12), eave overhang, rake at a gable end, heel, spacing,
 * gable or hip at each end. The app builds the roof plan and framing from this when it next
 * opens or reloads the project, and the exterior elevations show it. remove: true takes it away.
 */
function setRoof(project, a) {
  const s = { ...settingsOf(project) };
  const len = (v, what) => { const x = M.parseLength(v); if (x == null || isNaN(x)) throw new M.UserError(`${what}: "${v}" isn't a length.`); return x; };
  if (a.remove) {
    materialize(project); project.building = { ...s, roof: null }; reconcile(project); compact(project);
    return { state: project, result: { roof: "removed" } };
  }
  const r = { ...(s.roof || {}) };
  if (a.framing) {
    const f = { trusses: "TRUSSES", rafters: "RAFTERS" }[String(a.framing).toLowerCase()];
    // Switching changes the spacing to that framing's usual (Dan: trusses 24", rafters 16") unless one is given.
    if (a.spacing == null && f !== (r.framing || "TRUSSES")) r.ocIn = f === "TRUSSES" ? 24 : 16;
    r.framing = f;
  }
  if (a.pitch != null) { const p = Number(String(a.pitch).split("/")[0]); if (!(p > 0 && p <= 24)) throw new M.UserError("pitch: rise per 12, e.g. 6 for a 6/12."); r.pitch = p; }
  if (a.overhang != null) r.overhangIn = len(a.overhang, "overhang");
  if (a.rake != null) r.rakeIn = len(a.rake, "rake");
  if (a.heel != null) r.heelIn = len(a.heel, "heel");
  if (a.spacing != null) r.ocIn = len(a.spacing, "spacing");
  if (a.rafter_size) r.rafterSize = String(a.rafter_size);
  if (a.girder_height != null) { const g = len(a.girder_height, "girder_height"); if (!(g > 0 && g <= 240)) throw new M.UserError("girder_height: how tall the truss is at the hip girder, e.g. 5'."); r.girderHeightIn = g; }
  if (a.addon_spacing != null) r.addOnOcIn = len(a.addon_spacing, "addon_spacing");
  const end = (v, what) => { const x = { gable: "GABLE", hip: "HIP" }[String(v).toLowerCase()]; if (!x) throw new M.UserError(`${what}: gable or hip.`); return x; };
  if (a.ends) { r.endA = end(a.ends, "ends"); r.endB = r.endA; }
  if (a.end_a) r.endA = end(a.end_a, "end_a");
  if (a.end_b) r.endB = end(a.end_b, "end_b");
  if (a.turn_ridge != null) r.turnRidge = !!a.turn_ridge;
  if (a.covering) {
    const c = COVERINGS[String(a.covering).toLowerCase()];
    if (!c) throw new M.UserError(`covering: ${Object.keys(COVERINGS).join(", ")}.`);
    r.covering = c;
  }
  materialize(project);
  project.building = { ...s, roof: r };
  reconcile(project);
  compact(project);
  const shown = { framing: (r.framing || "TRUSSES").toLowerCase(), pitch: `${r.pitch ?? 6}/12`, overhang: inchText(r.overhangIn ?? 12), rake: inchText(r.rakeIn ?? 12),
    heel: inchText(r.heelIn ?? 4), spacing: `${inchText(r.ocIn ?? 24)} o.c.`, garage_and_wing_trusses: `${inchText(r.addOnOcIn ?? 24)} o.c.`,
    hip_girder_at_truss_height: (r.framing || "TRUSSES") === "TRUSSES" ? inchText(r.girderHeightIn ?? 60) : undefined, ends: `${(r.endA || "GABLE").toLowerCase()} / ${(r.endB || "GABLE").toLowerCase()}`,
    ridge: r.turnRidge ? "turned (runs the short way)" : "runs the long way", covering: (r.covering || "ARCH_SHINGLES").toLowerCase().replace(/_/g, " ") };
  return { state: project, result: { roof: shown, note: "Site Draft builds the roof plan and framing over the first-floor 2x6 exterior walls when it reloads the project (within a few seconds if it's open)." } };
}

/**
 * The four exterior elevations (ExteriorElevation): made by the app once the grade exposure (how
 * much foundation shows above grade) is set, live from the plan, the openings and the roof.
 */
function setExteriorElevations(project, a) {
  const s = { ...settingsOf(project) };
  let g = null;
  if (!a.remove) {
    g = M.parseLength(a.grade_exposure);
    if (g == null || isNaN(g) || g < 0) throw new M.UserError("grade_exposure: how much foundation shows above grade, e.g. 8\".");
  }
  materialize(project);
  project.building = { ...s, gradeExposureIn: g };
  reconcile(project);
  compact(project);
  return { state: project, result: a.remove ? { exteriorElevations: "removed" } : { exteriorElevations: "south, north, east and west", gradeExposure: inchText(g),
    note: "Site Draft makes the four exterior elevations under the drawing when it reloads the project, on the Exterior elevation layer. They show the roof once set_roof is done." } };
}

/** Puts elements on a layer. Nothing moves. */
function moveToLayer(project, a) {
  const layer = findLayer(project, a.layer);
  const ids = new Set(a.ids || []);
  const missing = [...ids].filter((id) => !(project.elements || []).some((e) => e.id === id));
  if (missing.length) throw new M.UserError(`No element with id ${missing.join(", ")}.`);
  for (const e of project.elements || []) if (ids.has(e.id)) e.layerId = layer.id;
  return { state: project, result: { moved: ids.size, layer: layer.id } };
}

module.exports = {
  FLOOR1_PLAN, settingsOf, levels, defaultLayers, effectiveLayers, activeLayer, defaultLayerId, layerOf, layerForNew,
  tagNew, materialize, reconcile, validate, findLayer, compact, compactSettings, inchText, fmt,
  describe, setActiveLayer, showHideLayers, addLayer, setBuildingInputs, moveToLayer, setRoof, setExteriorElevations,
};

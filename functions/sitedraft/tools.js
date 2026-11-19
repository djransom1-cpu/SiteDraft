/**
 * The drawing tools as pure functions: (project state, arguments) -> { state, result }. No
 * Firestore here, so they are tested directly (test/sitedraft.test.js) and the Kotlin
 * ConnectorCheckTest opens their output with the app's own code.
 *
 * Coordinates for people and Claude: inches from the drawing origin, x to the right (east),
 * y DOWN (south), as on the screen. Lengths may be typed the way builders say them: 12'6",
 * 12' 6 1/2", 150", 3.2m.
 */
const M = require("./model");
const G = require("./geometry");

const ToolError = M.UserError;
const fail = (msg) => { throw new ToolError(msg); };

function len(v, what) {
  const x = M.parseLength(v);
  if (x === null || !isFinite(x)) fail(`${what}: "${v}" is not a length (try 12'6", 150" or 3.2m).`);
  return x;
}

const ink = (state) => (state.currentColor !== undefined ? state.currentColor : M.WHITE);
const fmt = (inches, state) => M.formatLength(inches, M.isMetric(state));
const r2 = (v) => Math.round(v * 100) / 100;
const findById = (state, id) => (state.elements || []).find((e) => e.id === id) || null;

function wallOrFail(state, id) {
  const w = findById(state, id);
  if (!w || M.kind(w) !== "Symbol" || w.category !== G.WALLS) fail(`No wall with id "${id}". Use read_drawing to see the walls and their ids.`);
  return w;
}

// =============================================================================== create

function newProject(name, { metric = false, pxPerFoot } = {}) {
  if (!name || /[\/]/.test(name)) fail("A project name is needed, without slashes.");
  const s = { projectName: name };
  if (metric) s.unitSystem = "METRIC";
  if (pxPerFoot && pxPerFoot !== M.catalog.defaults.pxPerFoot) s.scale = { ratio: pxPerFoot, label: `1' = ${pxPerFoot}px` };
  return s;
}

// =============================================================================== read

function describeWall(w, state, inch) {
  const b = G.bounds(w), h = G.isHorizontal(w);
  const length = (h ? b.width : b.height) / inch;
  const thick = (h ? b.height : b.width) / inch;
  return {
    id: w.id, type: w.symbolName, runs: h ? "east-west" : "north-south",
    length: fmt(length, state), lengthIn: r2(length), thicknessIn: r2(thick),
    from: { xIn: r2(b.left / inch), yIn: r2(b.top / inch) }, to: { xIn: r2(b.right / inch), yIn: r2(b.bottom / inch) },
    roomSide: ({ BELOW: "south", ABOVE: "north", RIGHT: "east", LEFT: "west" })[G.interiorSide(w, state)] || null,
  };
}

/** Which wall face a symbol stands against (its back within 6" of the face), and where along it. */
function wallOf(s, state, inch) {
  const b = G.bounds(s);
  let best = null;
  for (const w of G.walls(state)) {
    const wb = G.bounds(w), h = G.isHorizontal(w);
    const overlap = h ? Math.min(b.right, wb.right) - Math.max(b.left, wb.left) : Math.min(b.bottom, wb.bottom) - Math.max(b.top, wb.top);
    if (overlap < Math.min(h ? b.width : b.height, 2 * inch) * 0.5) continue;
    const gaps = h ? { south: b.top - wb.bottom, north: wb.top - b.bottom } : { east: b.left - wb.right, west: wb.left - b.right };
    for (const [face, gap] of Object.entries(gaps)) {
      if (gap >= -3 * inch && gap <= 6 * inch && (!best || Math.abs(gap) < Math.abs(best.gap))) best = { wall: w, face, gap };
    }
  }
  if (!best) return null;
  const ax = G.axis(G.isHorizontal(best.wall));
  const [start] = G.wallEnds(best.wall, state, false, ax.a({ x: b.cx, y: b.cy }), inch);
  return { wallId: best.wall.id, face: best.face, fromStartIn: r2((ax.a1(b) - start) / inch) };
}

function readDrawing(state) {
  const inch = M.pxPerInch(state);
  const syms = G.symbols(state);
  const walls = G.walls(state).map((w) => describeWall(w, state, inch));
  const openings = [], cabinets = [], others = [];
  for (const s of syms) {
    if (s.category === G.WALLS) continue;
    const p = s.properties || {};
    const b = G.bounds(s);
    if (s.category === G.DOORS_WINDOWS) {
      const host = G.hostWall(s, state, inch);
      const o = { id: s.id, type: s.symbolName, widthIn: r2((G.isDoor(s.symbolName) ? G.size(s).w : Math.max(b.width, b.height)) / inch), wallId: host ? host.id : null };
      if (host) {
        const d = G.openingDistance(s, state, { fromEnd: false, toCenter: false, outside: false }, inch);
        o.fromLeftOrTopInsideCornerToEdge = d === null ? null : fmt(d / inch, state);
      }
      if (G.isDoor(s.symbolName)) {
        o.hinge = p[M.props.hingeRight] >= 0.5 ? "right" : "left";
        const rot = (((Number(s.rotation || 0)) % 360) + 360) % 360;
        o.swingsInto = ({ 0: "north", 90: "east", 180: "south", 270: "west" })[Math.round(rot / 90) * 90 % 360] || `${rot} deg`;
        o.heightIn = p[M.props.heightIn] ?? M.catalog.defaults.doorHeightIn;
      } else if (G.isWindow(s.symbolName)) {
        o.sillIn = p[M.props.sillIn] ?? M.catalog.defaults.windowSillIn;
        o.heightIn = p[M.props.heightIn] ?? M.catalog.defaults.windowHeightIn;
      }
      openings.push(o);
      continue;
    }
    const item = { id: s.id, name: s.symbolName, category: s.category, widthIn: r2(G.size(s).w / inch), depthIn: r2(G.size(s).h / inch),
      at: { xIn: r2(b.left / inch), yIn: r2(b.top / inch) }, rotation: Number(s.rotation || 0) };
    const code = G.cabinetLabel(s, state, inch);
    if (code) item.code = code;
    const on = wallOf(s, state, inch);
    if (on) Object.assign(item, { wallId: on.wallId, wallFace: on.face, fromLeftOrTopInsideCornerIn: on.fromStartIn });
    const kitchen = [G.CABINETS, "Appliances", "Hoods"].includes(s.category) || (s.category === "Fixtures" && on);
    (kitchen ? cabinets : others).push(item);
  }
  const rooms = [];
  for (const t of (state.elements || []).filter((e) => M.kind(e) === "Text")) {
    const tl = M.pt(t.topLeft);
    const area = G.areaAt({ x: tl.x + 1, y: tl.y + 1 }, state) || G.areaAt({ x: tl.x + 20, y: tl.y + 20 }, state);
    rooms.push({ label: t.text, textId: t.id, insideWidth: area ? fmt(area.width / inch, state) : null, insideDepth: area ? fmt(area.height / inch, state) : null,
      insideFrom: area ? { xIn: r2(area.left / inch), yIn: r2(area.top / inch) } : null });
  }
  const count = (k) => (state.elements || []).filter((e) => M.kind(e) === k).length;
  return {
    project: state.projectName, units: M.isMetric(state) ? "metric" : "imperial", pxPerFoot: M.pxPerUnit(state),
    coordinates: "inches from the drawing origin; x to the right (east), y down (south), as on screen",
    walls, openings, cabinetsAndAppliances: cabinets, otherSymbols: others, roomLabels: rooms,
    counts: { elevations: count("ElevationView"), dimensions: count("Dimension"), texts: count("Text"), pdfPlans: count("Pdf"), lines: count("Line"), sheets: (state.sheets || []).length },
  };
}

// =============================================================================== walls

const DIRS = { east: [1, 0], south: [0, 1], west: [-1, 0], north: [0, -1] };
/** Drywall each face, inches: the app's wall thickness includes it (a 2x4 Wall is 3 1/2" + 2 x 1/2"). */
const DRYWALL_IN = 0.5;

/**
 * Walk-the-room walls. [segments]: [{ length, turn?: "left"|"right"|degrees, direction?: compass }].
 * The first segment goes [start_direction] (default east); each later one turns from the last.
 * [measured]: which face the lengths are of: "inside" (finished room face), "framing" (stud face),
 * "outside" (outer finished face) or "center". The walls are built on the far side of that face from
 * the room. Square corners join the way the app's rooms do (the east-west wall runs through the corner).
 */
function drawWalls(state, a) {
  const inch = M.pxPerInch(state);
  const wallType = a.wall_type || "2x4 Wall";
  if (!M.categories[G.WALLS].includes(wallType)) fail(`wall_type must be one of: ${M.categories[G.WALLS].join(", ")}`);
  const t = M.footprints[wallType][1];
  const measured = String(a.measured || "").toLowerCase();
  if (!["inside", "framing", "outside", "center"].includes(measured)) fail('measured is required: "inside" (finished room face), "framing", "outside" or "center". Ask which face the lengths were taken on.');
  const segs = a.segments || [];
  if (!segs.length) fail("segments: give at least one wall length.");

  // The face path, in inches.
  let dir = DIRS[String(a.start_direction || "east").toLowerCase()];
  if (!dir) fail("start_direction must be north, south, east or west.");
  let angle = Math.atan2(dir[1], dir[0]) * 180 / Math.PI;
  const start = a.start ? { x: len(a.start.x ?? a.start.xIn ?? 0, "start.x"), y: len(a.start.y ?? a.start.yIn ?? 0, "start.y") } : defaultStart(state, inch);
  const pts = [start];
  segs.forEach((sg, i) => {
    if (i > 0 || sg.direction) {
      if (sg.direction) {
        const d = DIRS[String(sg.direction).toLowerCase()];
        if (!d) fail(`segment ${i + 1}: direction must be north, south, east or west.`);
        angle = Math.atan2(d[1], d[0]) * 180 / Math.PI;
      } else if (i > 0) {
        const turn = sg.turn;
        if (turn === undefined || turn === null) fail(`segment ${i + 1}: say turn "left" or "right" (or degrees, + right), or a direction.`);
        // Screen y is down, so turning right (clockwise as seen on the plan) adds to the angle.
        const deg = turn === "right" ? 90 : turn === "left" ? -90 : Number(turn);
        if (!isFinite(deg)) fail(`segment ${i + 1}: turn "${turn}" is not left, right or a number of degrees.`);
        angle += deg;
      }
    }
    const L = len(sg.length, `segment ${i + 1} length`);
    if (L <= 0) fail(`segment ${i + 1}: the length must be more than zero.`);
    const p = pts[pts.length - 1], rad = angle * Math.PI / 180;
    pts.push({ x: p.x + Math.cos(rad) * L, y: p.y + Math.sin(rad) * L });
  });
  const snap = (v) => Math.round(v * 64) / 64;
  for (const p of pts) { p.x = snap(p.x); p.y = snap(p.y); }

  let closed = false;
  const first = pts[0], last = pts[pts.length - 1];
  if (a.close) {
    if (Math.hypot(first.x - last.x, first.y - last.y) < 0.5) { pts[pts.length - 1] = { ...first }; closed = true; }
    else if (Math.abs(first.x - last.x) < 0.5 || Math.abs(first.y - last.y) < 0.5) { pts.push({ ...first }); closed = true; }
    else fail(`close: the walls end ${fmt(Math.abs(last.x - first.x), state)} across and ${fmt(Math.abs(last.y - first.y), state)} up/down from the start, so one straight wall can't close them. Check the lengths, or add the missing walls.`);
  } else if (Math.hypot(first.x - last.x, first.y - last.y) < 0.5) {
    pts[pts.length - 1] = { ...first }; closed = true;
  }

  // Which side is the room: a closed loop's inside; else as given, or the side the turns go.
  let roomRight;
  if (closed) {
    let area2 = 0;
    for (let i = 0; i < pts.length - 1; i++) area2 += pts[i].x * pts[i + 1].y - pts[i + 1].x * pts[i].y;
    roomRight = area2 > 0; // y down: positive shoelace = clockwise on screen = room on the right
  } else if (a.room_side) {
    roomRight = String(a.room_side).toLowerCase() === "right";
  } else {
    const rights = segs.filter((s) => s.turn === "right" || Number(s.turn) > 0).length;
    const lefts = segs.filter((s) => s.turn === "left" || Number(s.turn) < 0).length;
    roomRight = rights >= lefts;
  }

  // Offsets from the measured face toward the OUTSIDE of the room (inches): the wall band.
  const off0 = { inside: 0, framing: -DRYWALL_IN, outside: -t, center: -t / 2 }[measured];
  const off1 = off0 + t;
  const n = pts.length - 1;
  const segDir = (i) => { const p = pts[i], q = pts[i + 1], L = Math.hypot(q.x - p.x, q.y - p.y); return { x: (q.x - p.x) / L, y: (q.y - p.y) / L }; };
  // Outward normal: the room is on the right of the walking direction -> outside is on the left.
  const outN = (d) => roomRight ? { x: d.y, y: -d.x } : { x: -d.y, y: d.x };
  const orth = (d) => Math.abs(d.x) < 1e-6 || Math.abs(d.y) < 1e-6;

  // Miter point of the offset line at vertex i (between segment i-1 and i) for offset o.
  function miter(i, o) {
    const hasPrev = i > 0 || closed, hasNext = i < n || closed;
    const di = hasNext ? segDir(i % n) : segDir(i - 1);
    const dp = hasPrev ? segDir((i - 1 + n) % n) : di;
    const n1 = outN(dp), n2 = outN(di);
    const p = pts[i % n === 0 && closed ? 0 : i];
    if (!hasPrev || !hasNext) { const nn = hasNext ? n2 : n1; return { x: p.x + nn.x * o, y: p.y + nn.y * o }; }
    const cross = dp.x * di.y - dp.y * di.x;
    if (Math.abs(cross) < 1e-6) return { x: p.x + n2.x * o, y: p.y + n2.y * o };
    // Intersection of the two offset lines.
    const a1 = { x: p.x + n1.x * o, y: p.y + n1.y * o }, a2 = { x: p.x + n2.x * o, y: p.y + n2.y * o };
    const tt = ((a2.x - a1.x) * di.y - (a2.y - a1.y) * di.x) / cross;
    return { x: a1.x + dp.x * tt, y: a1.y + dp.y * tt };
  }

  const rects = []; // { kind: "rect", l,t,r,b, horizontal } or { kind: "rot", cx, cy, length, angle }
  for (let i = 0; i < n; i++) {
    const d = segDir(i);
    const A = miter(i, off0), B = miter(i, off1), C = miter(i + 1, off0), D = miter(i + 1, off1);
    if (orth(d)) {
      const xs = [A.x, B.x, C.x, D.x], ys = [A.y, B.y, C.y, D.y];
      rects.push({ kind: "rect", l: Math.min(...xs), t: Math.min(...ys), r: Math.max(...xs), b: Math.max(...ys), horizontal: Math.abs(d.x) > 0.5, seg: i });
    } else {
      const mid = { x: (A.x + B.x + C.x + D.x) / 4, y: (A.y + B.y + C.y + D.y) / 4 };
      const length = Math.hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y);
      rects.push({ kind: "rot", cx: mid.x, cy: mid.y, length, angle: Math.atan2(d.y, d.x) * 180 / Math.PI, seg: i });
    }
  }
  // Square corners: the east-west wall keeps the corner; the north-south wall stops at it.
  for (const v of rects.filter((r) => r.kind === "rect" && !r.horizontal)) {
    for (const h of rects.filter((r) => r.kind === "rect" && r.horizontal)) {
      const xOverlap = Math.min(v.r, h.r) - Math.max(v.l, h.l);
      if (xOverlap <= 0.01) continue;
      if (h.b > v.t && h.t < v.t + 0.01 + (h.b - h.t) && h.t <= v.t + 0.01) v.t = Math.max(v.t, h.b);
      if (h.t < v.b && h.b > v.b - 0.01 - (h.b - h.t) && h.b >= v.b - 0.01) v.b = Math.min(v.b, h.t);
    }
  }

  // Walls already there (another room sharing this side) are not doubled.
  const existing = G.walls(state).map((w) => G.bounds(w));
  const covered = (r) => {
    const horizontal = r.horizontal, L = horizontal ? r.r - r.l : r.b - r.t;
    return existing.some((e) => {
      const eh = e.width >= e.height;
      if (eh !== horizontal) return false;
      const gap = horizontal ? Math.abs(e.cy - (r.t + r.b) / 2 * inch) : Math.abs(e.cx - (r.l + r.r) / 2 * inch);
      const touching = gap <= (t * inch + (horizontal ? e.height : e.width)) / 2 + inch;
      const ov = horizontal ? Math.min(e.right, r.r * inch) - Math.max(e.left, r.l * inch) : Math.min(e.bottom, r.b * inch) - Math.max(e.top, r.t * inch);
      return touching && ov >= 0.5 * L * inch;
    });
  };

  const color = ink(state);
  const made = [], skipped = [];
  for (const r of rects) {
    if (r.kind === "rect") {
      if (r.r - r.l <= 0.01 || r.b - r.t <= 0.01) continue;
      if (a.skip_existing !== false && covered(r)) { skipped.push(r.seg + 1); continue; }
      made.push(M.makeSymbol({ name: wallType, category: G.WALLS, center: { x: (r.l + r.r) / 2 * inch, y: (r.t + r.b) / 2 * inch },
        widthPx: (r.r - r.l) * inch, depthPx: (r.b - r.t) * inch, color, properties: { widthInches: M.catalog.defaults.defaultWallLengthIn, depthInches: t } }));
    } else {
      made.push(M.makeSymbol({ name: wallType, category: G.WALLS, center: { x: r.cx * inch, y: r.cy * inch }, widthPx: r.length * inch, depthPx: t * inch,
        rotation: r.angle, color, properties: { widthInches: M.catalog.defaults.defaultWallLengthIn, depthInches: t } }));
    }
  }
  let elements = [...(state.elements || []), ...made];
  const out = { ...state, elements };

  // A room label for a closed rectangle.
  let inside = null;
  if (closed && pts.every((p, i) => i === 0 || orth(segDir(i - 1)))) {
    const insideOff = { inside: 0, framing: DRYWALL_IN, outside: t, center: t / 2 }[measured];
    const xs = pts.map((p) => p.x), ys = pts.map((p) => p.y);
    // The finished inside face is insideOff in from the measured line, all round.
    inside = { l: Math.min(...xs) + insideOff, t: Math.min(...ys) + insideOff, r: Math.max(...xs) - insideOff, b: Math.max(...ys) - insideOff };
    if (a.room_name !== undefined && pts.length === 5) out.elements = [...out.elements, roomLabel(out, String(a.room_name), inside, inch)];
  }
  return {
    state: out,
    result: {
      created: made.map((w) => describeWall(w, out, inch)),
      skippedSegments: skipped.length ? skipped.map((s) => `segment ${s}: a wall is already there`) : undefined,
      closed, roomOn: roomRight ? "right of the walking direction" : "left of the walking direction",
      insideSize: inside ? `${fmt(inside.r - inside.l, state)} x ${fmt(inside.b - inside.t, state)}` : undefined,
      nonSquareWalls: rects.some((r) => r.kind === "rot") ? "Walls at angles other than 90 degrees are drawn turned; elevations and auto-dimensions only use square walls." : undefined,
    },
  };
}

function defaultStart(state, inch) {
  const ws = G.walls(state);
  if (!ws.length) return { x: 0, y: 0 };
  const right = Math.max(...ws.map((w) => G.bounds(w).right)) / inch;
  const top = Math.min(...ws.map((w) => G.bounds(w).top)) / inch;
  return { x: Math.ceil(right + 60), y: top };
}

/** FloorPlan.roomLabel: "Name\n12' x 10'" centred in the inside of the room. */
function roomLabel(state, name, insideIn, inch) {
  const w = (insideIn.r - insideIn.l) / 12, d = (insideIn.b - insideIn.t) / 12;
  const n = (v) => Number.isInteger(v) ? String(v) : v.toFixed(2).replace(/0+$/, "").replace(/\.$/, "");
  const dims = M.isMetric(state) ? `${n(w * 0.3048)} x ${n(d * 0.3048)} m` : `${n(w)}' x ${n(d)}'`;
  const text = name.trim() ? `${name.trim()}\n${dims}` : dims;
  const fontSize = Math.min(36, Math.max(10, 6 * inch));
  const lines = text.split("\n");
  const longest = Math.max(...lines.map((l) => l.length));
  const cx = (insideIn.l + insideIn.r) / 2 * inch, cy = (insideIn.t + insideIn.b) / 2 * inch;
  return M.makeText({ text, topLeft: { x: cx - longest * fontSize * 0.55 / 2, y: cy - fontSize * 0.6 * lines.length }, fontSize, color: ink(state) });
}

// =============================================================================== doors and windows

function openingName(a) {
  if (a.type && M.categories[G.DOORS_WINDOWS].includes(a.type)) return a.type;
  const kindWord = String(a.kind || a.type || "").toLowerCase();
  const list = M.categories[G.DOORS_WINDOWS];
  const pool = kindWord.includes("slid") ? list.filter((n) => n.startsWith("Sliding"))
    : kindWord.includes("open") ? list.filter((n) => n.startsWith("Opening"))
    : kindWord.includes("window") ? list.filter((n) => n.startsWith("Window"))
    : kindWord.includes("door") ? list.filter((n) => n.startsWith("Door"))
    : null;
  if (!pool) fail(`type: give one of ${list.join(", ")}, or kind door / window / opening / sliding door with a width.`);
  const w = a.width ? len(a.width, "width") : M.footprints[pool[0]][0];
  return pool.reduce((best, nm) => Math.abs(M.footprints[nm][0] - w) < Math.abs(M.footprints[best][0] - w) ? nm : best, pool[0]);
}

const END_WORDS = { left: false, top: false, right: true, bottom: true };

/**
 * A door, window or opening in [wall_id], positioned as the app's "Position on the wall" does:
 * [from] left/right (top/bottom on a north-south wall), [to] "edge" (the near edge) or "center",
 * [corner] "inside" (the room's wall face) or "outside" (the building's outside corner), [distance].
 */
function placeOpening(state, a) {
  const inch = M.pxPerInch(state);
  const wall = wallOrFail(state, a.wall_id);
  const wb = G.bounds(wall), horizontal = G.isHorizontal(wall);
  const name = openingName(a);
  const widthIn = a.width ? len(a.width, "width") : M.footprints[name][0];
  const w = widthIn * inch;
  const door = G.isDoor(name);
  const props = {};
  const from = String(a.from || (horizontal ? "left" : "top")).toLowerCase();
  if (!(from in END_WORDS)) fail('from must be "left" or "right" (or "top"/"bottom" on a north-south wall).');
  if (horizontal && (from === "top" || from === "bottom")) fail(`This wall runs east-west: measure from "left" or "right".`);
  if (!horizontal && (from === "left" || from === "right")) fail(`This wall runs north-south: measure from "top" or "bottom".`);
  const ref = { fromEnd: END_WORDS[from], toCenter: String(a.to || "edge").toLowerCase().startsWith("c"), outside: String(a.corner || "inside").toLowerCase() === "outside" };
  props[M.props.posFromEnd] = ref.fromEnd ? 1 : 0;
  props[M.props.posToCenter] = ref.toCenter ? 1 : 0;
  props[M.props.posOutside] = ref.outside ? 1 : 0;

  let sym;
  const midX = wb.cx, midY = wb.cy;
  if (door) {
    const swing = String(a.swing_into || "").toLowerCase();
    const ok = horizontal ? ["north", "south"] : ["east", "west"];
    if (!ok.includes(swing)) fail(`swing_into: this wall runs ${horizontal ? "east-west" : "north-south"}, so the door swings ${ok.join(" or ")}.`);
    props[M.props.hingeRight] = String(a.hinge || "left").toLowerCase() === "right" ? 1 : 0;
    props[M.props.heightIn] = a.height ? len(a.height, "height") : M.catalog.defaults.doorHeightIn;
    let c, rot;
    if (horizontal) { c = { x: midX, y: midY - w / 2 }; rot = 0; if (swing === "south") { c = { x: midX, y: midY + w / 2 }; rot = 180; } }
    else { c = { x: midX + w / 2, y: midY }; rot = 90; if (swing === "west") { c = { x: midX - w / 2, y: midY }; rot = 270; } }
    sym = M.makeSymbol({ name, category: G.DOORS_WINDOWS, center: c, widthPx: w, depthPx: w, rotation: rot, color: ink(state), properties: { widthInches: widthIn, depthInches: widthIn, ...props } });
  } else {
    const t = horizontal ? wb.height : wb.width;
    if (G.isWindow(name)) {
      props[M.props.sillIn] = a.sill ? len(a.sill, "sill") : M.catalog.defaults.windowSillIn;
      props[M.props.heightIn] = a.height ? len(a.height, "height") : M.catalog.defaults.windowHeightIn;
    } else if (a.height) props[M.props.heightIn] = len(a.height, "height");
    sym = M.makeSymbol({ name, category: G.DOORS_WINDOWS, center: { x: midX, y: midY }, widthPx: horizontal ? w : t, depthPx: horizontal ? t : w,
      color: ink(state), properties: { widthInches: widthIn, depthInches: M.footprints[name][1], ...props } });
  }
  const withIt = { ...state, elements: [...(state.elements || []), sym] };
  const distance = len(a.distance ?? 0, "distance");
  const shift = G.openingShift(sym, withIt, distance * inch, ref, inch);
  if (!shift) fail("Could not seat the opening in that wall.");
  const c0 = M.pt(sym.center);
  sym.center = M.ptText({ x: c0.x + shift.x, y: c0.y + shift.y });

  // It has to fit in the wall and not run into another opening.
  const ax = G.axis(horizontal);
  const at = ax.a(G.openingAnchor(sym));
  const lo = at - w / 2, hi = at + w / 2;
  if (lo < ax.a1(wb) - 0.5 || hi > ax.a2(wb) + 0.5) fail(`That puts the ${name} past the end of the wall (the wall is ${fmt((ax.a2(wb) - ax.a1(wb)) / inch, state)} long).`);
  for (const o of G.symbols(state).filter((s) => s.category === G.DOORS_WINDOWS)) {
    const host = G.hostWall(o, state, inch);
    if (!host || host.id !== wall.id) continue;
    const oa = ax.a(G.openingAnchor(o));
    const ow = (G.isDoor(o.symbolName) ? G.size(o).w : (horizontal ? G.bounds(o).width : G.bounds(o).height)) / 2;
    if (lo < oa + ow - 0.5 && hi > oa - ow + 0.5) fail(`That overlaps the ${o.symbolName} (${o.id}) already in this wall.`);
  }
  const check = G.openingDistance(sym, withIt, ref, inch);
  return { state: withIt, result: { placed: { id: sym.id, type: name, wallId: wall.id, widthIn: r2(widthIn), measured: `${fmt(check / inch, state)} from the ${from} ${ref.outside ? "outside" : "inside"} corner to the ${ref.toCenter ? "centre" : "near edge"}` } } };
}

// =============================================================================== cabinets

const UPPER_CODES = new Set(["W", "WDC", "WF"]);
const STYLE = { slab: 0, shaker: 1, "raised panel": 2, raised: 2, glass: 3 };
const HARDWARE = { knobs: 2, "bar pulls": 1, pulls: 1, "knobs + drawer pulls": 0, "knobs and pulls": 0 };
const FRONT = { doors: 0, "drawer + doors": 1, "drawer and doors": 1, "3 drawers": 2, "4 drawers": 3 };

function nearestName(prefix, w, exclude = () => false) {
  const names = M.categories[G.CABINETS].filter((n) => n.startsWith(prefix) && !exclude(n));
  if (!names.length) return null;
  const exact = names.find((n) => M.footprints[n][0] === w);
  return exact || names.reduce((b, n) => Math.abs(M.footprints[n][0] - w) < Math.abs(M.footprints[b][0] - w) ? n : b, names[0]);
}

/** One cabinet-run item -> { name, widthIn, depthIn, props, upper, gap, sink }. */
function resolveItem(item, opts) {
  const o = typeof item === "string" ? { code: item } : { ...item };
  const raw = String(o.code || o.name || "").trim();
  const props = {};
  if (opts.style !== undefined) props[M.props.style] = opts.style;
  if (opts.hardware !== undefined) props[M.props.hardware] = opts.hardware;
  let m;
  if ((m = raw.match(/^(GAP|SPACE)\s+(.+)$/i))) return { gap: len(m[2], `"${raw}"`) };
  const cat = M.categoryOf(raw);
  if (cat) {
    const fp = M.footprints[raw] || [24, 24];
    const upper = raw.startsWith("Upper") || cat === "Hoods" || raw === "Over-Range Microwave 30\"";
    return { name: raw, widthIn: o.width ? len(o.width, "width") : fp[0], depthIn: o.depth ? len(o.depth, "depth") : fp[1], props: cat === G.CABINETS ? props : {}, upper, cat };
  }
  const up = raw.toUpperCase().replace(/\s+/g, "");
  if (up === "DW" || up === "DW24") return { name: "Dishwasher", widthIn: 24, depthIn: 24, props: {}, cat: "Fixtures" };
  if (up === "DW18") return { name: "Dishwasher 18\"", widthIn: 18, depthIn: M.footprints["Dishwasher 18\""][1], props: {}, cat: "Appliances" };
  if (!(m = up.match(/^(WDC|BD|SB|LS|BF|WF|VD|LT|ISL|W|B|P|V)(\d+(?:\.\d+)?)$/))) fail(`"${raw}": not a cabinet code (B24, BD18, SB36, LS36, W3030, WDC2430, P2484, V30, VD18, LT18, BF3, WF3), a catalog name, or GAP 30".`);
  const code = m[1], digits = m[2];
  // Wall cabinets carry width then height: W3030 = 30" wide, 30" high; W15 alone = 15" x 30".
  let width = Number(digits), height = null;
  if ((code === "W" || code === "WDC" || code === "P") && /^\d{4}$/.test(digits)) { width = Number(digits.slice(0, 2)); height = Number(digits.slice(2)); }
  if (o.width) width = len(o.width, "width");
  const upper = UPPER_CODES.has(code);
  let name;
  switch (code) {
    case "B": case "BD": case "SB": name = nearestName("Base ", width); break;
    case "LS": name = "Corner Base 36\""; break;
    case "W": name = nearestName("Upper ", width, (n) => n.includes("Corner") || n.includes("Filler")); break;
    case "WDC": name = "Upper Corner 24\""; break;
    case "P": name = "Pantry 24\""; break;
    case "V": name = nearestName("Vanity ", width, (n) => n.includes("Drawers")); break;
    case "VD": name = nearestName("Vanity Drawers", width); break;
    case "LT": name = "Linen Tower 18\""; break;
    case "BF": name = nearestName("Filler", width, (n) => n.startsWith("Upper")); break;
    case "WF": name = nearestName("Upper Filler", width); break;
    case "ISL": fail("An island stands free: use place_symbol with \"Island 3'x6'\" and a position."); break;
  }
  if (code === "BD") props[M.props.front] = 2;
  if (code === "B" && opts.front !== undefined) props[M.props.front] = opts.front;
  if (upper && height && height !== M.catalog.defaults.upperHeightIn) props[M.props.upperHeightIn] = height;
  if (o.front !== undefined) props[M.props.front] = typeof o.front === "number" ? o.front : FRONT[String(o.front).toLowerCase()];
  const fp = M.footprints[name];
  return { name, widthIn: width, depthIn: o.depth ? len(o.depth, "depth") : fp[1], props, upper, cat: G.CABINETS, sink: code === "SB" && o.sink !== false };
}

/**
 * A run of cabinets and appliances along one face of a wall, back to the wall, in order from
 * the chosen end. Items are shop codes (B24, SB36, W3018...), catalog names, "GAP 30" to skip
 * space, or objects { code|name, width?, front? }. A sink base also gets a sink in its top.
 */
function placeCabinets(state, a) {
  const inch = M.pxPerInch(state);
  const wall = wallOrFail(state, a.wall_id);
  const wb = G.bounds(wall), horizontal = G.isHorizontal(wall);
  const ax = G.axis(horizontal);
  const roomSide = ({ BELOW: "south", ABOVE: "north", RIGHT: "east", LEFT: "west" })[G.interiorSide(wall, state)];
  const face = String(a.face || roomSide || (horizontal ? "south" : "east")).toLowerCase();
  if (horizontal && !["north", "south"].includes(face)) fail("This wall runs east-west: face must be north or south.");
  if (!horizontal && !["east", "west"].includes(face)) fail("This wall runs north-south: face must be east or west.");
  const from = String(a.from || (horizontal ? "left" : "top")).toLowerCase();
  if (!(from in END_WORDS)) fail('from must be "left"/"right" (east-west wall) or "top"/"bottom" (north-south wall).');
  const fromEnd = END_WORDS[from];
  const opts = {
    style: a.style !== undefined ? (typeof a.style === "number" ? a.style : STYLE[String(a.style).toLowerCase()]) : undefined,
    hardware: a.hardware !== undefined ? (typeof a.hardware === "number" ? a.hardware : HARDWARE[String(a.hardware).toLowerCase()]) : undefined,
    front: a.front !== undefined ? (typeof a.front === "number" ? a.front : FRONT[String(a.front).toLowerCase()]) : undefined,
  };
  const items = (a.items || []).map((it) => resolveItem(it, opts));
  if (!items.length) fail("items: list the cabinets in order, e.g. [\"B15\", \"SB36\", \"DW\", \"BD18\"].");

  // Start: the inside corner (or outside) at that end, plus an offset.
  const outside = String(a.corner || "inside").toLowerCase() === "outside";
  const [s, e] = G.wallEnds(wall, state, outside, (ax.a1(wb) + ax.a2(wb)) / 2, inch);
  const offset = len(a.offset ?? 0, "offset") * inch;
  const faceCoord = { south: wb.bottom, north: wb.top, east: wb.right, west: wb.left }[face];
  const outward = { south: 1, north: -1, east: 1, west: -1 }[face];
  // The symbol's back is its top edge: turn it so the front faces the room (DrawingState.rotateAbout, y down).
  const rotation = { south: 0, north: 180, west: 90, east: 270 }[face];

  let cursor = fromEnd ? e - offset : s + offset;
  const color = ink(state);
  const made = [], placed = [], warnings = [];
  for (const it of items) {
    if (it.gap !== undefined) { cursor += (fromEnd ? -1 : 1) * it.gap * inch; continue; }
    const w = it.widthIn * inch, d = it.depthIn * inch;
    const a1 = fromEnd ? cursor - w : cursor;
    const a2 = a1 + w;
    cursor = fromEnd ? a1 : a2;
    const along = (a1 + a2) / 2;
    const across = faceCoord + outward * d / 2;
    const c = horizontal ? { x: along, y: across } : { x: across, y: along };
    const sym = M.makeSymbol({ name: it.name, category: it.cat, center: c, widthPx: w, depthPx: d, rotation, color,
      properties: { widthInches: it.widthIn, depthInches: it.depthIn, ...it.props } });
    made.push(sym);
    if (it.sink) {
      const sw = Math.min(33, it.widthIn - 3) * inch, sd = 22 * inch;
      const sc = horizontal ? { x: along, y: faceCoord + outward * (1.5 * inch + sd / 2) } : { x: faceCoord + outward * (1.5 * inch + sd / 2), y: along };
      made.push(M.makeSymbol({ name: "Kitchen Sink", category: "Fixtures", center: sc, widthPx: sw, depthPx: sd, rotation, color, properties: { widthInches: sw / inch, depthInches: 22 } }));
    }
    placed.push({ sym, it, a1, a2 });
    if (a1 < s - 0.5 || a2 > e + 0.5) warnings.push(`${it.name} runs past the ${outside ? "outside" : "inside"} corner at the ${a1 < s ? (horizontal ? "left" : "top") : (horizontal ? "right" : "bottom")} end.`);
  }
  // Doors and windows in this wall that the run would cover.
  for (const o of G.symbols(state).filter((x) => x.category === G.DOORS_WINDOWS)) {
    const host = G.hostWall(o, state, inch);
    if (!host || host.id !== wall.id) continue;
    const oa = ax.a(G.openingAnchor(o));
    const half = (G.isDoor(o.symbolName) ? G.size(o).w : (horizontal ? G.bounds(o).width : G.bounds(o).height)) / 2;
    for (const p of placed) {
      if (p.a1 < oa + half - 0.5 && p.a2 > oa - half + 0.5) {
        const sill = (o.properties || {})[M.props.sillIn] ?? M.catalog.defaults.windowSillIn;
        if (G.isWindow(o.symbolName) && !p.it.upper && sill >= 36) continue; // a base under a window is normal
        warnings.push(`${p.it.name} is in front of the ${o.symbolName} (${o.id}).`);
      }
    }
  }
  const out = { ...state, elements: [...(state.elements || []), ...made] };
  return {
    state: out,
    result: {
      placed: placed.map((p) => ({ id: p.sym.id, name: p.it.name, code: G.cabinetLabel(p.sym, out, inch) || undefined,
        widthIn: r2(p.it.widthIn), fromInsideCornerIn: r2(((fromEnd ? e - p.a2 : p.a1 - s)) / inch) })),
      runLength: fmt(Math.abs((fromEnd ? e - offset : s + offset) - cursor) / inch, state),
      face, from, warnings: warnings.length ? warnings : undefined,
    },
  };
}

// =============================================================================== generic

function placeSymbol(state, a) {
  const inch = M.pxPerInch(state);
  const name = a.name;
  const cat = M.categoryOf(name);
  if (!cat) fail(`"${name}" is not in the catalog. Use list_catalog.`);
  if (cat === "Showers" || cat === "Stairs") fail("Showers and stairs need their own settings; place them in the app for now.");
  const fp = M.footprints[name] || [24, 24];
  const w = (a.width ? len(a.width, "width") : fp[0]) * inch, d = (a.depth ? len(a.depth, "depth") : fp[1]) * inch;
  const c = { x: len(a.x, "x") * inch, y: len(a.y, "y") * inch };
  const sym = M.makeSymbol({ name, category: cat, center: c, widthPx: w, depthPx: d, rotation: Number(a.rotation || 0), color: ink(state) });
  return { state: { ...state, elements: [...(state.elements || []), sym] }, result: { placed: { id: sym.id, name, category: cat } } };
}

function addLabel(state, a) {
  const inch = M.pxPerInch(state);
  const t = M.makeText({ text: String(a.text || ""), topLeft: { x: len(a.x, "x") * inch, y: len(a.y, "y") * inch }, fontSize: a.size ? len(a.size, "size") * inch : Math.min(36, Math.max(10, 6 * inch)), color: ink(state) });
  return { state: { ...state, elements: [...(state.elements || []), t] }, result: { added: t.id } };
}

function editElement(state, a) {
  const inch = M.pxPerInch(state);
  const el = findById(state, a.id);
  if (!el) fail(`No element with id "${a.id}".`);
  const e = JSON.parse(JSON.stringify(el, (k, v) => (typeof v === "bigint" ? { __big: v.toString() } : v)), (k, v) => (v && v.__big ? BigInt(v.__big) : v));
  const changed = [];
  if (a.move_by) {
    const dx = len(a.move_by.x ?? 0, "move_by.x") * inch, dy = len(a.move_by.y ?? 0, "move_by.y") * inch;
    for (const key of ["center", "topLeft", "start", "end", "origin"]) if (typeof e[key] === "string") { const p = M.pt(e[key]); e[key] = M.ptText({ x: p.x + dx, y: p.y + dy }); }
    changed.push("moved");
  }
  if (a.rotation !== undefined) { e.rotation = Number(a.rotation); changed.push("rotation"); }
  if (a.width !== undefined || a.depth !== undefined) {
    if (typeof e.size !== "string") fail("That element has no size to change.");
    const sz = M.pt(e.size);
    e.size = M.sizeText(a.width !== undefined ? len(a.width, "width") * inch : sz.x, a.depth !== undefined ? len(a.depth, "depth") * inch : sz.y);
    changed.push("size");
  }
  if (a.text !== undefined) { if (M.kind(e) !== "Text") fail("Only text has text."); e.text = String(a.text); changed.push("text"); }
  if (a.properties) { e.properties = { ...(e.properties || {}), ...a.properties }; changed.push("properties"); }
  return { state: { ...state, elements: state.elements.map((x) => (x.id === a.id ? e : x)) }, result: { edited: a.id, changed } };
}

function deleteElements(state, a) {
  const ids = new Set(a.ids || []);
  const gone = (state.elements || []).filter((e) => ids.has(e.id)).map((e) => e.id);
  if (!gone.length) fail("None of those ids are in the drawing.");
  // An elevation's dimensions go with it, as in the app.
  const elements = state.elements.filter((e) => !ids.has(e.id) && !(M.kind(e) === "Dimension" && e.ownerId && ids.has(e.ownerId)));
  return { state: { ...state, elements }, result: { deleted: gone } };
}

module.exports = {
  ToolError, newProject, readDrawing, drawWalls, placeOpening, placeCabinets, placeSymbol, addLabel, editElement, deleteElements,
  resolveItem, roomLabel,
};

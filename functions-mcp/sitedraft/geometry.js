/**
 * The app's plan geometry, ported line for line from the Kotlin (names kept so they can be
 * compared): FloorPlan.bounds / areaAt, Elevation.interiorSide / openingAnchor,
 * OpeningPosition, PlanSymbols.cabinetLabel. Screen coordinates: x right, y DOWN; rotation in
 * degrees clockwise on screen. A ConnectorCheckTest on the Kotlin side opens drawings made here
 * and measures them with the app's own code.
 */
const M = require("./model");

const WALLS = "Walls";
const DOORS_WINDOWS = "Doors & Windows";
const CABINETS = "Cabinets";

const symbols = (state) => (state.elements || []).filter((e) => M.kind(e) === "Symbol");
const walls = (state) => symbols(state).filter((s) => s.category === WALLS);
/** Which floor an element is drawn on, from its layer ("floor2-plan" = 2); no layer = the first floor (Nov 27: the second
 * floor's walls are carried up from the first, so a first-floor window must not find its wall upstairs). */
const floorNo = (e) => { const m = /^floor(\d+)/.exec(String((e && e.layerId) || "")); return m ? Number(m[1]) : 1; };
const wallsOn = (state, e) => walls(state).filter((w) => floorNo(w) === floorNo(e));
const center = (s) => M.pt(s.center);
const size = (s) => { const p = M.pt(s.size); return { w: p.x, h: p.y }; };

/** DrawingState.rotateAbout. */
function rotateAbout(p, pivot, deg) {
  if (!deg) return { x: p.x, y: p.y };
  const r = deg * Math.PI / 180, c = Math.cos(r), s = Math.sin(r);
  const dx = p.x - pivot.x, dy = p.y - pivot.y;
  return { x: pivot.x + dx * c - dy * s, y: pivot.y + dx * s + dy * c };
}

const rect = (l, t, r, b) => ({ left: l, top: t, right: r, bottom: b, get width() { return this.right - this.left; }, get height() { return this.bottom - this.top; }, get cx() { return (this.left + this.right) / 2; }, get cy() { return (this.top + this.bottom) / 2; } });
const inflate = (r, d) => rect(r.left - d, r.top - d, r.right + d, r.bottom + d);
const contains = (r, p) => p.x >= r.left && p.x < r.right && p.y >= r.top && p.y < r.bottom; // Compose Rect.contains: right/bottom exclusive

/** FloorPlan.bounds: the axis-aligned footprint as drawn (a quarter turn swaps width and depth). */
function bounds(s) {
  const c = center(s), { w, h } = size(s), rot = Number(s.rotation || 0);
  if (!rot) return rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2);
  const q = Math.round(rot / 90);
  if (Math.abs(rot - q * 90) < 0.01) {
    const [W, H] = q % 2 !== 0 ? [h, w] : [w, h];
    return rect(c.x - W / 2, c.y - H / 2, c.x + W / 2, c.y + H / 2);
  }
  const pts = [[-w / 2, -h / 2], [w / 2, -h / 2], [w / 2, h / 2], [-w / 2, h / 2]].map(([x, y]) => rotateAbout({ x: c.x + x, y: c.y + y }, c, rot));
  return rect(Math.min(...pts.map((p) => p.x)), Math.min(...pts.map((p) => p.y)), Math.max(...pts.map((p) => p.x)), Math.max(...pts.map((p) => p.y)));
}

const isHorizontal = (wall) => { const { w, h } = size(wall); return w >= h; };
const isDoor = (name) => name.startsWith("Door ");
const isWindow = (name) => name.startsWith("Window ");

/** Elevation.interiorSide: the side of [wall] facing the middle of the other walls ("BELOW"/"ABOVE"/"RIGHT"/"LEFT"), or null. */
function interiorSide(wall, state) {
  const others = wallsOn(state, wall).filter((w) => w.id !== wall.id).map((w) => bounds(w));
  if (!others.length) return null;
  const mx = others.reduce((a, r) => a + r.cx, 0) / others.length;
  const my = others.reduce((a, r) => a + r.cy, 0) / others.length;
  const wb = bounds(wall);
  return isHorizontal(wall) ? (my >= wb.cy ? "BELOW" : "ABOVE") : (mx >= wb.cx ? "RIGHT" : "LEFT");
}

/** Elevation.openingAnchor: the point of a door or window that lies in its wall (a hinged door's threshold). */
function openingAnchor(s) {
  if (!isDoor(s.symbolName)) return center(s);
  const c = center(s), { h } = size(s);
  return rotateAbout({ x: c.x, y: c.y + h / 2 }, c, Number(s.rotation || 0));
}

// ---------------------------------------------------------------- OpeningPosition

function hostWall(opening, state, inch) {
  const a = openingAnchor(opening);
  const candidates = wallsOn(state, opening).filter((w) => contains(inflate(bounds(w), 2 * inch), a));
  candidates.sort((p, q) => dist2(bounds(p), a) - dist2(bounds(q), a));
  return candidates[0] || null;
}
const dist2 = (r, a) => (r.cx - a.x) ** 2 + (r.cy - a.y) ** 2;

function axis(horizontal) {
  return horizontal
    ? { a: (p) => p.x, a1: (r) => r.left, a2: (r) => r.right, n1: (r) => r.top, n2: (r) => r.bottom, along: (d) => ({ x: d, y: 0 }) }
    : { a: (p) => p.y, a1: (r) => r.top, a2: (r) => r.bottom, n1: (r) => r.left, n2: (r) => r.right, along: (d) => ({ x: 0, y: d }) };
}

/** OpeningPosition.ends: the reference points along the wall around [at] (inside corners of the room, or the building's outside corners). */
function wallEnds(wall, state, outside, at, inch) {
  const ax = axis(isHorizontal(wall));
  const wb = bounds(wall);
  const side = interiorSide(wall, state);
  const insidePlus = side === null || side === "BELOW" || side === "RIGHT";
  const probe = (outside === insidePlus) ? ax.n1(wb) - 0.5 * inch : ax.n2(wb) + 0.5 * inch;
  const crossing = wallsOn(state, wall).filter((w) => w.id !== wall.id).map((w) => bounds(w)).filter((r) => ax.n1(r) < probe && ax.n2(r) > probe);
  if (!outside) {
    const start = Math.max(ax.a1(wb), ...crossing.filter((r) => ax.a2(r) <= at).map((r) => ax.a2(r)));
    const end = Math.min(ax.a2(wb), ...crossing.filter((r) => ax.a1(r) >= at).map((r) => ax.a1(r)));
    return [start, end];
  }
  const start = Math.min(ax.a1(wb), ...crossing.filter((r) => ax.a2(r) >= ax.a1(wb) - inch && ax.a1(r) <= ax.a1(wb) + inch).map((r) => ax.a1(r)));
  const end = Math.max(ax.a2(wb), ...crossing.filter((r) => ax.a1(r) <= ax.a2(wb) + inch && ax.a2(r) >= ax.a2(wb) - inch).map((r) => ax.a2(r)));
  return [start, end];
}

function widthAlong(opening, ax) {
  if (isDoor(opening.symbolName)) return size(opening).w;
  const b = bounds(opening);
  return ax.a2(b) - ax.a1(b);
}

/** OpeningPosition.distance (px); null when the opening is not in a wall. */
function openingDistance(opening, state, ref, inch) {
  const wall = hostWall(opening, state, inch);
  if (!wall) return null;
  const ax = axis(isHorizontal(wall));
  const at = ax.a(openingAnchor(opening));
  const half = widthAlong(opening, ax) / 2;
  const [s, e] = wallEnds(wall, state, ref.outside, at, inch);
  const point = ref.toCenter ? at : (ref.fromEnd ? at + half : at - half);
  return ref.fromEnd ? e - point : point - s;
}

/** OpeningPosition.placed: the move (px) that puts the opening [distancePx] from the chosen end. */
function openingShift(opening, state, distancePx, ref, inch) {
  const wall = hostWall(opening, state, inch);
  if (!wall) return null;
  const ax = axis(isHorizontal(wall));
  const at = ax.a(openingAnchor(opening));
  const half = widthAlong(opening, ax) / 2;
  const [s, e] = wallEnds(wall, state, ref.outside, at, inch);
  const point = ref.fromEnd ? e - distancePx : s + distancePx;
  const newAt = ref.toCenter ? point : (ref.fromEnd ? point - half : point + half);
  return ax.along(newAt - at);
}

// ---------------------------------------------------------------- rooms

/** FloorPlan.areaAt: the rectangle around [p] bounded by the nearest wall (or straight line) on each side, or null. */
function areaAt(p, state) {
  const boxes = [];
  for (const e of state.elements || []) {
    const k = M.kind(e);
    if (k === "Symbol" && e.category === WALLS) boxes.push(bounds(e));
    else if (k === "Line" && !Number(e.rotation || 0)) {
      const a = M.pt(e.start), b = M.pt(e.end);
      if (a.x === b.x || a.y === b.y) boxes.push(rect(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.max(a.x, b.x), Math.max(a.y, b.y)));
    }
  }
  const pick = (f, sel) => { const v = boxes.filter(f).map(sel); return v.length ? v : null; };
  const L = pick((r) => r.right <= p.x && p.y >= r.top && p.y <= r.bottom, (r) => r.right);
  const R = pick((r) => r.left >= p.x && p.y >= r.top && p.y <= r.bottom, (r) => r.left);
  const T = pick((r) => r.bottom <= p.y && p.x >= r.left && p.x <= r.right, (r) => r.bottom);
  const B = pick((r) => r.top >= p.y && p.x >= r.left && p.x <= r.right, (r) => r.top);
  if (!L || !R || !T || !B) return null;
  const out = rect(Math.max(...L), Math.max(...T), Math.min(...R), Math.min(...B));
  return out.width > 0 && out.height > 0 ? out : null;
}

// ---------------------------------------------------------------- cabinet codes

function codeInches(v) {
  const eighths = Math.round(v * 8);
  const whole = Math.trunc(eighths / 8);
  let rest = eighths % 8, den = 8;
  if (!rest) return String(whole);
  while (rest % 2 === 0) { rest /= 2; den /= 2; }
  return whole === 0 ? `${rest}/${den}` : `${whole}-${rest}/${den}`;
}

/** PlanSymbols.cabinetLabel: B24, BD18, SB36, LS36, W2430, WDC2430, P2484, V30, VD18, LT18, BF3, WF3, ISL72. */
function cabinetLabel(s, state, inch) {
  if (s.category !== CABINETS) return null;
  const name = s.symbolName, p = s.properties || {};
  const w = codeInches(size(s).w / inch);
  const upperH = codeInches(p[M.props.upperHeightIn] ?? M.catalog.defaults.upperHeightIn);
  const front = p[M.props.front] ?? (name.startsWith("Vanity Drawers") ? 2 : (name.startsWith("Base") || name.startsWith("Vanity") ? 1 : 0));
  if (name.includes("Filler")) return (name.startsWith("Upper") ? "WF" : "BF") + w;
  if (name === "Upper Corner 24\"") return `WDC${w}${upperH}`;
  if (name.startsWith("Upper")) return `W${w}${upperH}`;
  if (name.startsWith("Corner Base")) return `LS${w}`;
  if (name.startsWith("Base")) {
    const b = bounds(s);
    const sink = symbols(state).some((o) => o.category === "Fixtures" && M.catalog.counterSinks[o.symbolName] && contains(b, center(o)));
    return (sink ? "SB" : (front === 2 || front === 3 ? "BD" : "B")) + w;
  }
  if (name.startsWith("Pantry")) return `P${w}${codeInches(M.catalog.defaults.pantryHeightIn)}`;
  if (name === "Linen Tower 18\"") return `LT${w}`;
  if (name.startsWith("Vanity Drawers")) return `VD${w}`;
  if (name.startsWith("Vanity")) return `V${w}`;
  if (name.startsWith("Island")) return `ISL${w}`;
  return null;
}

module.exports = {
  WALLS, DOORS_WINDOWS, CABINETS, symbols, walls, wallsOn, floorNo, center, size, rotateAbout, rect, inflate, contains, bounds,
  isHorizontal, isDoor, isWindow, interiorSide, openingAnchor, hostWall, axis, wallEnds, openingDistance, openingShift,
  areaAt, codeInches, cabinetLabel,
};

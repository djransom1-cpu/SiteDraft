/**
 * Site Draft's project file, read and written the way the Kotlin app does it
 * (kotlinx.serialization of CanvasState, see shared/.../ui/canvas/DrawingState.kt):
 *
 *  - each element carries "type": "com.example.sitedraft.ui.canvas.DrawingElement.<Kind>"
 *  - Offset and Size are text, "x,y" and "w,h"
 *  - Color is Compose's packed 64-bit value as a JSON integer; many (black, the Blueprint blue)
 *    are beyond JavaScript's safe integers, so numbers are parsed losslessly (BigInt) and written
 *    back exactly as read
 *  - fields at their default value are left out (encodeDefaults = false)
 *
 * catalog.json (symbol names, footprints, property keys) is generated from the app's code by
 * ConnectorExportTest, so the names and sizes here are always the app's own.
 */
const crypto = require("crypto");
const catalog = require("./catalog.json");

/** A mistake in what was asked (shown to Claude as the tool's answer, not logged as a failure). */
class UserError extends Error {}

const TYPE_PREFIX = "com.example.sitedraft.ui.canvas.DrawingElement.";
/** Compose Color.White packed (the app's default ink). */
const WHITE = -4294967296;

// ---------------------------------------------------------------- lossless JSON

function parseProject(text) {
  return JSON.parse(text, (k, v, ctx) =>
    (typeof v === "number" && !Number.isSafeInteger(v) && ctx && /^-?\d+$/.test(ctx.source)) ? BigInt(ctx.source) : v);
}

/** Pretty-printed like the app (4 spaces), BigInts written back as plain integers. */
function stringifyProject(obj) {
  return JSON.stringify(obj, (k, v) => (typeof v === "bigint" ? JSON.rawJSON(v.toString()) : v), 4);
}

// ---------------------------------------------------------------- small values

const kind = (e) => String(e.type || "").startsWith(TYPE_PREFIX) ? e.type.slice(TYPE_PREFIX.length) : "";
const typeOf = (k) => TYPE_PREFIX + k;

function pt(text) {
  const [x, y] = String(text || "0,0").split(",").map(Number);
  return { x, y };
}
const ptText = (p) => `${num(p.x)},${num(p.y)}`;
const sizeText = (w, h) => `${num(w)},${num(h)}`;
/** A float as the app writes it (always with a decimal point; Kotlin reads either). */
function num(v) {
  const r = Math.round(v * 10000) / 10000;
  return Number.isInteger(r) ? r.toFixed(1) : String(r);
}

const newId = () => crypto.randomUUID();

// ---------------------------------------------------------------- units

/** World px per foot (imperial) or per metre (metric): the project's "1' = N px". */
function pxPerUnit(project) {
  return (project.scale && typeof project.scale.ratio === "number") ? project.scale.ratio : catalog.defaults.pxPerFoot;
}
function isMetric(project) { return project.unitSystem === "METRIC"; }
/** World px per real inch for this project. */
function pxPerInch(project) {
  return isMetric(project) ? pxPerUnit(project) * 0.0254 : pxPerUnit(project) / 12;
}

/**
 * A length typed the way people say it, to inches: 12'6", 12' 6 1/2", 12-6, 12.5' , 150",
 * 150 (inches), 3.2m, 450mm. Bare numbers are inches. Null when it is not a length.
 */
function parseLength(input) {
  if (typeof input === "number") return input;
  let s = String(input || "").trim().toLowerCase().replace(/[″”]/g, '"').replace(/[′’]/g, "'");
  if (!s) return null;
  let m;
  if ((m = s.match(/^(-?[\d.]+)\s*mm$/))) return Number(m[1]) / 25.4;
  if ((m = s.match(/^(-?[\d.]+)\s*cm$/))) return Number(m[1]) / 2.54;
  if ((m = s.match(/^(-?[\d.]+)\s*m$/))) return Number(m[1]) / 0.0254;
  const frac = (t) => {
    t = t.trim();
    if (!t) return 0;
    let mm;
    if ((mm = t.match(/^(\d+)\s+(\d+)\/(\d+)$/))) return Number(mm[1]) + Number(mm[2]) / Number(mm[3]);
    if ((mm = t.match(/^(\d+)-(\d+)\/(\d+)$/))) return Number(mm[1]) + Number(mm[2]) / Number(mm[3]);
    if ((mm = t.match(/^(\d+)\/(\d+)$/))) return Number(mm[1]) / Number(mm[2]);
    if (/^\d*\.?\d+$/.test(t)) return Number(t);
    return NaN;
  };
  // Feet and inches: 12'6", 12' 6 1/2", 12'-6", 12ft 6in, 12-6 (feet-inches)
  if ((m = s.match(/^(-?\d*\.?\d+)\s*(?:'|ft|feet|foot)\s*-?\s*(.*?)\s*(?:"|in|inch|inches)?$/))) {
    const inches = frac(m[2]);
    if (isNaN(inches)) return null;
    const ft = Number(m[1]);
    return ft * 12 + (ft < 0 ? -inches : inches);
  }
  if ((m = s.match(/^(\d+)-(\d+(?:\s+\d+\/\d+)?)$/))) return Number(m[1]) * 12 + frac(m[2]);
  if ((m = s.match(/^(.*?)\s*(?:"|in|inch|inches)?$/))) {
    const v = frac(m[1]);
    return isNaN(v) ? null : v;
  }
  return null;
}

/** Inches as a builder writes them: 12'-6 1/2" (to the nearest 1/16"), or metres in a metric project. */
function formatLength(inches, metric = false) {
  if (metric) return `${(inches * 0.0254).toFixed(3)} m`;
  const neg = inches < 0;
  let sixteenths = Math.round(Math.abs(inches) * 16);
  const ft = Math.floor(sixteenths / (12 * 16));
  sixteenths -= ft * 12 * 16;
  const wholeIn = Math.floor(sixteenths / 16);
  let rest = sixteenths - wholeIn * 16;
  let fracText = "";
  if (rest) {
    let den = 16;
    while (rest % 2 === 0) { rest /= 2; den /= 2; }
    fracText = ` ${rest}/${den}`;
  }
  return `${neg ? "-" : ""}${ft}'-${wholeIn}${fracText}"`;
}

// ---------------------------------------------------------------- catalog

const categories = catalog.categories;
const footprints = catalog.footprints;
const props = catalog.props;

function categoryOf(name) {
  for (const [cat, names] of Object.entries(categories)) if (names.includes(name)) return cat;
  return null;
}

/** A plan symbol the way the app's addSymbol makes one (widthInches/depthInches from the footprint). */
function makeSymbol({ name, category, center, widthPx, depthPx, rotation = 0, properties = {}, color = WHITE }) {
  const cat = category || categoryOf(name);
  if (!cat) throw new Error(`Unknown symbol "${name}"`);
  const fp = footprints[name] || [24, 24];
  const e = {
    type: typeOf("Symbol"),
    id: newId(),
    symbolName: name,
    category: cat,
    center: ptText(center),
    size: sizeText(widthPx, depthPx),
    color,
  };
  if (rotation) e.rotation = Number(num(((rotation % 360) + 360) % 360));
  e.properties = { widthInches: fp[0], depthInches: fp[1], ...properties };
  return e;
}

function makeText({ text, topLeft, fontSize = 25, color = WHITE }) {
  return { type: typeOf("Text"), id: newId(), text, topLeft: ptText(topLeft), fontSize, color };
}

module.exports = {
  UserError, TYPE_PREFIX, WHITE, catalog, categories, footprints, props,
  parseProject, stringifyProject, kind, typeOf, pt, ptText, sizeText, num, newId,
  pxPerUnit, pxPerInch, isMetric, parseLength, formatLength, categoryOf, makeSymbol, makeText,
};

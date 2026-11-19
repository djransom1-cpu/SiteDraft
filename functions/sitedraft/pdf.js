/**
 * PDF plans, rendered the way the desktop app imports them (FileImporter.desktop.kt): every page
 * at 72 DPI, one pixel per PDF point, PNG, stored base64 (MIME line breaks, as the app writes) in
 * one DrawingElement.Pdf. Rendered with pdf.js on @napi-rs/canvas (no system libraries needed).
 */
const M = require("./model");

const MAX_PAGES = 20;
const MAX_PIXELS = 40e6; // one page; a 36x48" sheet at 72 DPI is 9 Mpx

let pdfjs;
async function lib() {
  if (!pdfjs) pdfjs = await import("pdfjs-dist/legacy/build/pdf.mjs");
  return pdfjs;
}

/** MIME base64: 76-character lines joined by CRLF (java/kotlin Base64.Mime). */
function mimeBase64(buf) {
  const s = Buffer.from(buf).toString("base64");
  return s.match(/.{1,76}/g).join("\r\n");
}

/** PDF bytes -> [{ png: Buffer, width, height }] for the chosen pages (1-based). */
async function renderPages(bytes, pages) {
  const { getDocument } = await lib();
  const { createCanvas } = require("@napi-rs/canvas");
  const task = getDocument({ data: new Uint8Array(bytes), isEvalSupported: false, disableFontFace: true, useSystemFonts: false, verbosity: 0 });
  const doc = await task.promise;
  try {
    const want = pages && pages.length ? pages : Array.from({ length: doc.numPages }, (_, i) => i + 1);
    if (want.length > MAX_PAGES) throw new M.UserError(`That PDF has ${want.length} pages; import up to ${MAX_PAGES} at a time (pages: [1, 2, ...]).`);
    const out = [];
    for (const n of want) {
      if (n < 1 || n > doc.numPages) throw new M.UserError(`Page ${n} is not in the PDF (it has ${doc.numPages}).`);
      const page = await doc.getPage(n);
      const vp = page.getViewport({ scale: 1 });
      const w = Math.max(1, Math.round(vp.width)), h = Math.max(1, Math.round(vp.height));
      if (w * h > MAX_PIXELS) throw new M.UserError(`Page ${n} is too large to import (${w} x ${h} px).`);
      const canvas = createCanvas(w, h);
      const ctx = canvas.getContext("2d");
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, w, h);
      await page.render({ canvasContext: ctx, canvas, viewport: vp }).promise;
      out.push({ png: canvas.toBuffer("image/png"), width: w, height: h });
      page.cleanup();
    }
    return { pages: out, pageCount: doc.numPages };
  } finally {
    await task.destroy();
  }
}

/**
 * Adds the rendered pages as a plan under everything, centred on [center] (px) or at the
 * origin. [scaleFeetPerInch]: a plan drawn to a known scale ("1/4\" = 1'" -> 48) is sized so
 * its feet match the project's; otherwise it comes in at 1 px per point, as the app does.
 */
function addPlan(state, rendered, { center, planScale, opacity = 1, locked = false } = {}) {
  const first = rendered.pages[0];
  let k = 1;
  if (planScale) {
    // planScale = real inches per paper inch (1/4" = 1' -> 48). One paper inch = 72 px rendered.
    const realInchesPerPx = planScale / 72;
    k = realInchesPerPx * M.pxPerInch(state);
  }
  const w = first.width * k, h = first.height * k;
  const c = center || { x: 0, y: 0 };
  const el = {
    type: M.typeOf("Pdf"),
    id: M.newId(),
    pages: rendered.pages.map((p) => mimeBase64(p.png)),
    topLeft: M.ptText({ x: c.x - w / 2, y: c.y - h / 2 }),
    size: M.sizeText(w, h),
  };
  if (locked) el.locked = true;
  if (opacity !== 1) el.opacity = Math.max(0.1, Math.min(1, opacity));
  if (planScale) el.calibration = `Imported at a scale of 1:${Math.round(planScale)}`;
  return { state: { ...state, elements: [el, ...(state.elements || [])] }, element: el, sizePx: { w, h } };
}

/** "1/4\" = 1'", "1/8in=1ft", "1:50", "3/16" -> real inches per paper inch. */
function parseScale(text) {
  if (text === undefined || text === null || text === "") return null;
  if (typeof text === "number") return text;
  const s = String(text).trim().toLowerCase().replace(/\s+/g, "");
  let m;
  if ((m = s.match(/^1:(\d+(?:\.\d+)?)$/))) return Number(m[1]);
  if ((m = s.match(/^(.+?)(?:"|in)?=(.+)$/))) {
    const paper = M.parseLength(m[1].replace(/(?:"|in)$/, "") + '"');
    const real = M.parseLength(m[2].replace(/ft$/, "'"));
    if (paper && real) return real / paper;
  }
  if ((m = s.match(/^(\d+)\/(\d+)(?:"|in)?$/))) return 12 / (Number(m[1]) / Number(m[2])); // "1/4" alone = 1/4" per foot
  throw new M.UserError(`plan_scale "${text}": write it like 1/4" = 1', 1/8" = 1' or 1:50.`);
}

module.exports = { renderPages, addPlan, parseScale, mimeBase64, MAX_PAGES };

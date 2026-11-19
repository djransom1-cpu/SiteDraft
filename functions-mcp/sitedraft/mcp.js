/**
 * Site Draft MCP connector: a Model Context Protocol server, deployed as the `sitedraftMcp`
 * Cloud Function, that lets Claude read and draw in Site Draft projects. Built the same way as
 * Crewsync's crewsyncMcp: OAuth (oauth.js, an access key typed once on a consent page), then a
 * stateless Streamable HTTP MCP server per request, running with the Admin SDK as the owner.
 *
 * The drawing work is in tools.js (pure functions, tested without Firebase), the Firestore side
 * in store.js (the app's own chunked project format, a backup before every change, and a stamp
 * the open app watches so it reloads by itself), PDF plans in pdf.js.
 */
const crypto = require("crypto");
const express = require("express");
const { z } = require("zod");
const { McpServer } = require("@modelcontextprotocol/sdk/server/mcp.js");
const { StreamableHTTPServerTransport } = require("@modelcontextprotocol/sdk/server/streamableHttp.js");
const { mcpAuthRouter } = require("@modelcontextprotocol/sdk/server/auth/router.js");
const { requireBearerAuth } = require("@modelcontextprotocol/sdk/server/auth/middleware/bearerAuth.js");
const M = require("./model");
const T = require("./tools");
const S = require("./store");
const P = require("./pdf");
const { buildProvider, OWNER_EMAIL } = require("./oauth");

const UPLOAD_TTL_MS = 60 * 60 * 1000;
const MAX_INLINE_PDF_BYTES = 3 * 1024 * 1024;

const text = (s) => ({ content: [{ type: "text", text: s }] });
const json = (o) => ({ content: [{ type: "text", text: JSON.stringify(o, null, 2) }] });

const Len = z.union([z.string(), z.number()]).describe("A length: 12'6\", 12' 6 1/2\", 150\" or 150 (inches), 3.2m, 450mm");
const Project = z.string().describe("Project name exactly as list_projects shows it");
const Compass = z.enum(["north", "south", "east", "west"]);

const COORDS = "Plan coordinates are inches from the drawing origin: x to the right (east), y DOWN (south), as on screen; north is up.";

function buildServer(ctx) {
  const server = new McpServer({ name: "site-draft", version: "1.0.0" }, {
    instructions:
      "Site Draft is Dan Ransom's floor-plan and kitchen-layout app. Workflow: Dan walks a house and reads off wall lengths; " +
      "you draw them with draw_walls (ask which face he measured - inside finished face, framing, or outside - if he hasn't said), " +
      "then place doors and windows with place_opening and cabinets with place_cabinets. Always read_drawing first to get wall ids. " +
      "Every change is saved to the cloud with a backup, and Site Draft reloads the open project by itself within a few seconds; " +
      "undo_last_change puts back the drawing as it was before your last change. " + COORDS,
  });

  const reg = (name, description, schema, handler) => {
    server.registerTool(name, { description, inputSchema: schema }, async (args) => {
      try {
        return await handler(args || {});
      } catch (e) {
        if (e instanceof M.UserError) return { isError: true, content: [{ type: "text", text: e.message }] };
        console.error(`[sitedraftMcp] ${name} failed`, e);
        return { isError: true, content: [{ type: "text", text: `${name} failed: ${e.message}` }] };
      }
    });
  };

  /** Runs a pure tool against a saved project and stores the result. */
  const change = (tool, fn) => async (a) => {
    const out = await S.changeProject(ctx.db, ctx.uid, a.project, tool, (state) => fn(state, a));
    return json({ project: a.project, ...out.result, saved: new Date(out.updatedAt).toISOString(), note: "Site Draft reloads the open project within a few seconds." });
  };

  // ------------------------------------------------------------------ reading

  reg("whoami", "Which Site Draft account this connector works in, and how coordinates and lengths are written. Call first.", {}, async () =>
    json({ email: ctx.email, uid: ctx.uid, coordinates: COORDS, lengths: "12'6\", 12' 6 1/2\", 150\" (bare numbers are inches), 3.2m",
      wallsDefault: "2x4 Wall (4 1/2\" with drywall both sides)" }));

  reg("list_projects", "Site Draft projects in the cloud, newest first.", {}, async () => {
    const list = await S.listProjects(ctx.db, ctx.uid);
    return json(list.map((p) => ({ name: p.name, updated: p.updatedAt ? new Date(p.updatedAt).toISOString() : null, lastSavedBy: p.device })));
  });

  reg("read_drawing",
    "The walls (with ids, length, ends, which side the room is on), doors and windows (wall, position, hinge, swing), cabinets and appliances " +
    "(shop code like B24/SB36/W3030, wall, distance from the corner), other symbols and room labels of a project. " + COORDS,
    { project: Project, raw: z.boolean().optional().describe("Also return the raw element list (large)") },
    async (a) => {
      const p = await S.readProject(ctx.db, ctx.uid, a.project);
      if (!p) throw new M.UserError(`No project named "${a.project}".`);
      const out = T.readDrawing(p.state);
      out.updated = new Date(p.updatedAt).toISOString();
      out.lastSavedBy = p.device;
      if (a.raw) out.elements = JSON.parse(M.stringifyProject((p.state.elements || []).map((e) => (M.kind(e) === "Pdf" ? { ...e, pages: `[${e.pages.length} page image(s)]` } : e))));
      return json(out);
    });

  reg("list_catalog", "Symbol names in Site Draft's library by category, with footprints (width x depth, inches). Names are exact.",
    { category: z.string().optional() },
    async (a) => {
      const cats = a.category ? { [a.category]: M.categories[a.category] } : M.categories;
      if (a.category && !M.categories[a.category]) throw new M.UserError(`Categories: ${Object.keys(M.categories).join(", ")}`);
      const out = {};
      for (const [c, names] of Object.entries(cats)) out[c] = names.map((n) => (M.footprints[n] ? `${n}  (${M.footprints[n][0]} x ${M.footprints[n][1]})` : n));
      return json({ categories: out, cabinetCodes: "B (base), BD (drawer base), SB (sink base, adds a sink), LS (lazy susan corner 36), W{w}{h} (wall cabinet, e.g. W3030, W3018), WDC (diagonal corner), P (pantry), V / VD (vanity / vanity drawers), LT (linen tower), BF / WF (fillers), DW / DW18 (dishwasher), GAP 30\" (empty space)" });
    });

  // ------------------------------------------------------------------ projects

  reg("create_project", "Creates an empty Site Draft project in the cloud (it appears in the app's project list on the next sync).",
    { name: z.string(), metric: z.boolean().optional() },
    async (a) => {
      const out = await S.changeProject(ctx.db, ctx.uid, a.name, "create_project", (s) => ({ state: s, result: { created: a.name } }),
        { create: T.newProject(a.name, { metric: !!a.metric }), mustBeNew: true });
      return json({ ...out.result, note: "Open it in Site Draft from the project list (refresh the list if it isn't there yet)." });
    });

  // ------------------------------------------------------------------ walls, openings, cabinets

  reg("draw_walls",
    "Draws walls walking around a room: the first length runs start_direction (default east) from start (default: right of what's drawn), " +
    "each next length turns left or right (or by degrees, + = right) from the last. With close: true the last wall returns to the start " +
    "and a closed square room gets a label if room_name is given. measured says which face Dan read the lengths on; ASK him if he hasn't said. " +
    "Walls already drawn where a new one would go (a shared wall) are not doubled. Square (90 degree) corners are fully supported; other angles draw turned walls.",
    {
      project: Project,
      measured: z.enum(["inside", "framing", "outside", "center"]).describe("inside = finished room face (drywall), framing = stud face, outside = outer finished face, center = wall centre line"),
      segments: z.array(z.object({ length: Len, turn: z.union([z.enum(["left", "right"]), z.number()]).optional(), direction: Compass.optional() })).min(1),
      start_direction: Compass.optional(),
      start: z.object({ x: Len, y: Len }).optional().describe("Where the first measured corner is, inches. " + COORDS),
      close: z.boolean().optional(),
      room_name: z.string().optional(),
      room_side: z.enum(["left", "right"]).optional().describe("For an open run of walls: which side of the walking direction the room is on"),
      wall_type: z.string().optional().describe("2x4 Wall (default), 2x6 Wall, 2x4 Framing, 2x6 Framing, 8\" Block Wall, 8\" Concrete Wall"),
    },
    change("draw_walls", T.drawWalls));

  reg("place_opening",
    "Puts a door, window, sliding door or cased opening in a wall, positioned like the app's Position on the wall: measured from the " +
    "left/right end (top/bottom on a north-south wall), to the near edge or the centre, from the room's inside corner or the building's outside corner. " +
    "Doors need swing_into (the side the door swings to) and hinge (left/right); a pocket door needs pocket (the side it slides into).",
    {
      project: Project, wall_id: z.string(),
      kind: z.enum(["door", "window", "opening", "sliding door", "pocket door"]).optional(), type: z.string().optional().describe("Exact catalog name instead of kind, e.g. Door 2'-8\""),
      width: Len.optional(), from: z.enum(["left", "right", "top", "bottom"]).optional(), to: z.enum(["edge", "center"]).optional(),
      corner: z.enum(["inside", "outside"]).optional(), distance: Len.optional(),
      hinge: z.enum(["left", "right"]).optional(), swing_into: Compass.optional(),
      pocket: z.enum(["left", "right", "top", "bottom"]).optional().describe("Pocket door: the side it slides into (top/bottom on a north-south wall)"),
      height: Len.optional().describe("Door or opening height (door default 6'-8\"), or window height (default 4')"), sill: Len.optional().describe("Window sill height from the floor (default 36\")"),
    },
    change("place_opening", T.placeOpening));

  reg("place_cabinets",
    "Places a run of cabinets and appliances with their backs against one face of a wall, in order from the chosen end (inside corner by default) plus an offset. " +
    "Items: shop codes (B15, BD18, SB36 adds a sink, LS36, W3030, W1530, WDC2430, P2484, V30, VD18, BF3, WF3, DW), exact catalog names " +
    "(\"Range 30\\\"\", \"Refrigerator 36\\\" French Door\"), \"GAP 30\" for empty space, or {code, width, front}. Base and wall cabinets go in separate calls.",
    {
      project: Project, wall_id: z.string(),
      items: z.array(z.union([z.string(), z.object({ code: z.string().optional(), name: z.string().optional(), width: Len.optional(), depth: Len.optional(), front: z.union([z.string(), z.number()]).optional(), sink: z.boolean().optional() })])).min(1),
      face: Compass.optional().describe("Which face of the wall the cabinets stand against (default: the room side)"),
      from: z.enum(["left", "right", "top", "bottom"]).optional(), corner: z.enum(["inside", "outside"]).optional(), offset: Len.optional(),
      style: z.union([z.string(), z.number()]).optional().describe("slab, shaker, raised panel, glass"),
      hardware: z.union([z.string(), z.number()]).optional().describe("knobs, bar pulls, knobs + drawer pulls"),
      front: z.union([z.string(), z.number()]).optional().describe("Base fronts: doors, drawer + doors, 3 drawers, 4 drawers"),
    },
    change("place_cabinets", T.placeCabinets));

  reg("place_symbol", "Places any library symbol (island, appliance, fixture, furniture...) by its exact catalog name, centred at x, y (inches). " + COORDS,
    { project: Project, name: z.string(), x: Len, y: Len, rotation: z.number().optional().describe("Degrees clockwise"), width: Len.optional(), depth: Len.optional() },
    change("place_symbol", T.placeSymbol));

  reg("add_label", "Adds text at x, y (top-left, inches).", { project: Project, text: z.string(), x: Len, y: Len, size: Len.optional() },
    change("add_label", T.addLabel));

  reg("edit_element", "Moves, turns, resizes or re-labels one element by id, or merges symbol properties (e.g. {\"hingeRight\": 1}).",
    { project: Project, id: z.string(), move_by: z.object({ x: Len.optional(), y: Len.optional() }).optional(), rotation: z.number().optional(),
      width: Len.optional(), depth: Len.optional(), text: z.string().optional(), properties: z.record(z.string(), z.number()).optional() },
    change("edit_element", T.editElement));

  reg("delete_elements", "Deletes elements by id.", { project: Project, ids: z.array(z.string()).min(1) }, change("delete_elements", T.deleteElements));

  reg("undo_last_change", "Puts the project back the way it was before the connector's last change (repeat to go further back).", { project: Project }, async (a) => {
    const out = await S.undoLast(ctx.db, ctx.uid, a.project);
    return json({ project: a.project, ...out.result, saved: new Date(out.updatedAt).toISOString() });
  });

  // ------------------------------------------------------------------ PDF plans

  const pdfArgs = {
    pages: z.array(z.number().int()).optional().describe("Page numbers (1-based); default all, up to 20"),
    plan_scale: z.union([z.string(), z.number()]).optional().describe("The plan's printed scale, e.g. 1/4\" = 1' - sizes the plan to the drawing. Without it, it comes in at 1 px per point as the app does; calibrate in the app."),
    opacity: z.number().optional(), locked: z.boolean().optional(),
    create_if_missing: z.boolean().optional(),
  };

  reg("import_pdf",
    "Imports a PDF plan into a project, under everything, as the app's Import PDF does. Give pdf_base64 for a small PDF (up to 3 MB). " +
    "For a larger one, or one Dan has on his phone or computer, use create_upload_link instead.",
    { project: Project, pdf_base64: z.string(), ...pdfArgs },
    async (a) => {
      const bytes = Buffer.from(a.pdf_base64.replace(/^data:[^,]*,/, ""), "base64");
      if (bytes.length > MAX_INLINE_PDF_BYTES) throw new M.UserError("That PDF is over 3 MB; use create_upload_link and let Dan upload it.");
      if (bytes.subarray(0, 5).toString() !== "%PDF-") throw new M.UserError("That isn't a PDF (it should start with %PDF-).");
      const res = await importPdfBytes(ctx.db, ctx.uid, a.project, bytes, a);
      return json(res);
    });

  reg("create_upload_link",
    "Makes a one-time web page (good for an hour) where Dan picks a PDF from his phone or computer; it is imported into the project as soon as he uploads it. " +
    "Give him the link; then call upload_status (or read_drawing) to see it arrived.",
    { project: Project, ...pdfArgs },
    async (a) => {
      const token = crypto.randomBytes(24).toString("base64url");
      await ctx.db.collection("mcp_uploads").doc(token).set({
        uid: ctx.uid, project: a.project, pages: a.pages || null, plan_scale: a.plan_scale ?? null, opacity: a.opacity ?? null,
        locked: a.locked ?? null, create_if_missing: !!a.create_if_missing, createdAt: Date.now(), expiresAt: Date.now() + UPLOAD_TTL_MS, status: "waiting",
      });
      return json({ link: `${ctx.base}/upload/${token}`, uploadId: token, expires: new Date(Date.now() + UPLOAD_TTL_MS).toISOString() });
    });

  reg("upload_status", "Whether the PDF for an upload link has arrived and been imported.", { upload_id: z.string() }, async (a) => {
    const d = await ctx.db.collection("mcp_uploads").doc(a.upload_id).get();
    if (!d.exists || d.data().uid !== ctx.uid) throw new M.UserError("No such upload link.");
    const u = d.data();
    return json({ project: u.project, status: u.status, result: u.result || undefined, error: u.error || undefined });
  });

  return server;
}

async function importPdfBytes(db, uid, project, bytes, a) {
  const rendered = await P.renderPages(bytes, a.pages || null);
  const planScale = P.parseScale(a.plan_scale);
  const exists = !!(await S.readProject(db, uid, project));
  if (!exists && !a.create_if_missing) throw new M.UserError(`No project named "${project}" (set create_if_missing to make it).`);
  const out = await S.changeProject(db, uid, project, "import_pdf", (state) => {
    // Centred on the walls if there are any, else on the origin.
    const ws = require("./geometry").walls(state).map((w) => require("./geometry").bounds(w));
    const center = ws.length ? { x: (Math.min(...ws.map((r) => r.left)) + Math.max(...ws.map((r) => r.right))) / 2, y: (Math.min(...ws.map((r) => r.top)) + Math.max(...ws.map((r) => r.bottom))) / 2 } : { x: 0, y: 0 };
    const r = P.addPlan(state, rendered, { center, planScale, opacity: a.opacity ?? 1, locked: !!a.locked });
    return { state: r.state, result: { imported: r.element.id, pages: rendered.pages.length, ofPages: rendered.pageCount,
      sizeOnDrawing: `${M.formatLength(r.sizePx.w / M.pxPerInch(state))} x ${M.formatLength(r.sizePx.h / M.pxPerInch(state))}`,
      scaled: planScale ? `1:${planScale}` : "1 px per point (calibrate it in the app: Import > Scale plan)" } };
  }, { create: exists ? null : T.newProject(project) });
  return { project, ...out.result, saved: new Date(out.updatedAt).toISOString() };
}

// ------------------------------------------------------------------------------- identity

async function resolveContext(db, authInfo, { auth, base }) {
  const email = String(authInfo?.extra?.email || "").toLowerCase();
  if (!email) return { error: "This sign-in has no account. Disconnect and reconnect the Site Draft connector." };
  let uid = "";
  try { uid = (await auth.getUserByEmail(email)).uid; } catch (_) { /* fall back to the users collection */ }
  if (!uid) {
    const snap = await db.collection("users").where("email", "==", email).limit(1).get();
    if (!snap.empty) uid = snap.docs[0].id;
  }
  if (!uid) return { error: `No Site Draft account for ${email}. Sign in to Site Draft once with that email.` };
  return { db, email, uid, base };
}

// ------------------------------------------------------------------------------- upload page

const uploadPage = (token, project) => `<!doctype html>
<html><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Upload a plan</title></head>
<body style="font-family:sans-serif;max-width:460px;margin:60px auto;padding:0 16px;">
<h2>Upload a PDF plan to Site Draft</h2>
<p>Project: <b>${String(project).replace(/[<>&"]/g, "")}</b></p>
<input id="f" type="file" accept="application/pdf,.pdf" style="font-size:16px;" />
<button id="b" style="margin-top:12px;padding:8px 16px;font-size:16px;display:block;">Upload</button>
<p id="s" style="margin-top:16px;"></p>
<script>
document.getElementById("b").onclick = async () => {
  const f = document.getElementById("f").files[0], s = document.getElementById("s");
  if (!f) { s.textContent = "Pick a PDF first."; return; }
  s.textContent = "Uploading and importing...";
  try {
    const r = await fetch(location.pathname, { method: "POST", headers: { "Content-Type": "application/pdf" }, body: f });
    const j = await r.json();
    s.textContent = r.ok ? "Done: " + j.pages + " page(s) imported. You can close this page; Site Draft reloads the project by itself." : ("Not imported: " + j.error);
  } catch (e) { s.textContent = "Upload failed: " + e.message; }
};
</script></body></html>`;

// ------------------------------------------------------------------------------- HTTP app

function buildApp(db, { legacySecret, base, auth }) {
  const app = express();
  const { provider, confirmAuthorization } = buildProvider({ legacySecret, db });
  app.set("trust proxy", true);

  app.use(mcpAuthRouter({
    provider, issuerUrl: new URL(base), resourceServerUrl: new URL(base), resourceName: "Site Draft",
    authorizationOptions: { rateLimit: false }, tokenOptions: { rateLimit: false }, clientRegistrationOptions: { rateLimit: false },
  }));

  app.get("/authorize/confirm", async (req, res) => {
    try {
      res.redirect(302, await confirmAuthorization({ authId: req.query.authId, keyAttempt: req.query.secret }));
    } catch (error) {
      res.status(400).send("Authorization failed: " + String(error.message).replace(/[<>&]/g, ""));
    }
  });

  app.get("/upload/:token", async (req, res) => {
    const d = await db.collection("mcp_uploads").doc(req.params.token).get();
    if (!d.exists || d.data().expiresAt < Date.now() || d.data().status !== "waiting") { res.status(410).send("This upload link has expired or was already used. Ask Claude for a new one."); return; }
    res.set("Content-Type", "text/html").send(uploadPage(req.params.token, d.data().project));
  });

  app.post("/upload/:token", express.raw({ type: () => true, limit: "30mb" }), async (req, res) => {
    const ref = db.collection("mcp_uploads").doc(req.params.token);
    // Claim the link first so a double tap can't import twice.
    let u;
    try {
      u = await db.runTransaction(async (tx) => {
        const d = await tx.get(ref);
        if (!d.exists || d.data().expiresAt < Date.now() || d.data().status !== "waiting") return null;
        tx.set(ref, { status: "importing" }, { merge: true });
        return d.data();
      });
    } catch (e) { u = null; }
    if (!u) { res.status(410).json({ error: "This upload link has expired or was already used." }); return; }
    try {
      const bytes = Buffer.isBuffer(req.rawBody) && req.rawBody.length ? req.rawBody : req.body;
      if (!Buffer.isBuffer(bytes) || bytes.subarray(0, 5).toString() !== "%PDF-") throw new M.UserError("That file isn't a PDF.");
      const result = await importPdfBytes(db, u.uid, u.project, bytes, {
        pages: u.pages, plan_scale: u.plan_scale ?? undefined, opacity: u.opacity ?? undefined, locked: u.locked ?? undefined, create_if_missing: u.create_if_missing,
      });
      await ref.set({ status: "imported", result, usedAt: Date.now() }, { merge: true });
      res.json(result);
    } catch (e) {
      const msg = e instanceof M.UserError ? e.message : `Import failed: ${e.message}`;
      if (!(e instanceof M.UserError)) console.error("[sitedraftMcp] upload failed", e);
      // A bad file leaves the link usable for another try.
      await ref.set({ status: "waiting", error: msg }, { merge: true });
      res.status(400).json({ error: msg });
    }
  });

  app.get("/", (_req, res) => res.status(200).send("Site Draft MCP server is up. POST JSON-RPC here with a bearer token."));

  app.post("/", express.json({ limit: "8mb" }), requireBearerAuth({ verifier: provider }), async (req, res) => {
    try {
      const ctx = await resolveContext(db, req.auth, { auth, base });
      if (ctx.error) { res.status(403).json({ error: ctx.error }); return; }
      const server = buildServer(ctx);
      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
      res.on("close", () => { transport.close(); server.close(); });
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch (error) {
      console.error("[sitedraftMcp] request failed", error);
      if (!res.headersSent) res.status(500).json({ error: "Internal server error" });
    }
  });

  return app;
}

/** The function's own address, from the request (stable per deployment: the Cloud Run URL). */
function baseUrlOf(req) {
  const host = req.get("x-forwarded-host") || req.get("host");
  return `https://${host}`;
}

module.exports = { buildServer, buildApp, resolveContext, importPdfBytes, baseUrlOf, OWNER_EMAIL };

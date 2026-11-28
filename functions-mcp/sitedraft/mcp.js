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
const L = require("./layers");
const X = require("./extras");
const B = require("./bridge");
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
      "undo_last_change puts back the drawing as it was before your last change. " +
      "The building has levels (footing to roof, elevations from its inputs; first floor top of subfloor 100'-0\") and layers by trade; whatever you draw goes on the active layer (set_active_layer), e.g. the foundation walls on Foundation. " +
      "Floors: hatch_area (tile, planks, hardwood...). Elevations: add_elevation for a wall's cabinets, set_exterior_elevations for the four outside ones. Roof: set_roof. " +
      "Beam and posts: set_structure. Siding, stone or brick wainscot: set_exterior_finish. Porches, garages and wings: place_porch, place_garage, place_wing (a part of the house that sticks out, its own roof). Sheets: build_sheets, list_sheets. " +
      "Stairs: place_stairs (sized to the floor heights, up or down), edit_stairs. " +
      "Remodels: walls measured in a house as it stands are draw_walls status existing; mark walls coming out with edit_element status demo or demo_wall_part for part of one; new work is status new. set_remodel_view shows the existing, demo or proposed plan. " +
      "Every symbol's back is the side against the wall; read_drawing and the place/edit results say which way its front faces, so check that instead of asking Dan. " +
      "Full access while the app is open: run_actions sends the app's own actions (anything a tap or panel button can do; list_actions lists them, from the app's code) and the open copy of Site Draft runs them with its own code, then answers with what changed; app_status says which copies are open. Use it for anything the tools above can't do. " + COORDS,
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
  // Anything a tool draws goes on the active layer (cabinets and furniture on the floor's own), as in the app.
  const change = (tool, fn) => async (a) => {
    const out = await S.changeProject(ctx.db, ctx.uid, a.project, tool, async (state) => {
      const before = new Set((state.elements || []).map((e) => e.id));
      const res = await fn(state, a);
      if (res && res.state) L.tagNew(before, res.state);
      return res;
    });
    return json({ project: a.project, ...out.result, saved: new Date(out.updatedAt).toISOString(), note: "Site Draft reloads the open project within a few seconds." });
  };

  // ------------------------------------------------------------------ reading

  reg("whoami", "Which Site Draft account this connector works in, and how coordinates and lengths are written. Call first.", {}, async () =>
    json({ email: ctx.email, uid: ctx.uid, coordinates: COORDS, lengths: "12'6\", 12' 6 1/2\", 150\" (bare numbers are inches), 3.2m",
      wallsDefault: "2x4 Wall (4 1/2\" with drywall both sides)" }));

  reg("list_projects", "Site Draft projects in the cloud, newest first. Deleted ones are listed apart, under recentlyDeleted.", {}, async () => {
    const list = await S.listProjects(ctx.db, ctx.uid);
    const now = Date.now();
    const live = list.filter((p) => !(p.deletedAt > 0));
    const gone = list.filter((p) => p.deletedAt > 0 && now - p.deletedAt < S.KEEP_DELETED_MS);
    const rows = live.map((p) => ({ name: p.name, updated: p.updatedAt ? new Date(p.updatedAt).toISOString() : null, lastSavedBy: p.device }));
    if (!gone.length) return json(rows);
    return json({ projects: rows, recentlyDeleted: gone.map((p) => ({ name: p.name, deleted: new Date(p.deletedAt).toISOString(),
      daysLeft: Math.max(0, Math.ceil((p.deletedAt + S.KEEP_DELETED_MS - now) / 86400000)) })) });
  });

  reg("delete_project",
    "Deletes a whole project (job), as the app's Delete does: it leaves the project list on every device and waits 30 days in Recently deleted, " +
    "where restore_project (or Restore in the app) brings it back; after that it's gone. Only when Dan has asked to delete that job: " +
    "say which project and that it will be in Recently deleted for 30 days, get his yes, then call with confirm: true. " +
    "Refused while a copy of the app has the project open.",
    { project: Project, confirm: z.boolean().describe("true only after Dan said yes to deleting this project") },
    async (a) => {
      if (a.confirm !== true) throw new M.UserError("Not deleted: ask Dan first, then call again with confirm: true.");
      const open = (await B.copies(ctx.db, ctx.uid)).filter((c) => c.open && c.project === a.project);
      if (open.length) throw new M.UserError(`"${a.project}" is open on ${open.map((c) => c.device).join(", ")}. Open another job there first.`);
      const r = await S.setDeleted(ctx.db, ctx.uid, a.project, Date.now());
      if (!r) throw new M.UserError(`No project named "${a.project}". Use list_projects to see the names.`);
      if (r.wasDeletedAt > 0) { await S.setDeleted(ctx.db, ctx.uid, a.project, r.wasDeletedAt); throw new M.UserError(`"${a.project}" is already deleted.`); }
      return json({ deleted: r.name, inRecentlyDeletedUntil: new Date(Date.now() + S.KEEP_DELETED_MS).toDateString(), restoreWith: "restore_project" });
    });

  reg("restore_project", "Brings a deleted project back from Recently deleted (within 30 days of deleting it), on every device.",
    { project: Project },
    async (a) => {
      const r = await S.setDeleted(ctx.db, ctx.uid, a.project, 0);
      if (!r) throw new M.UserError(`No project named "${a.project}" in the cloud; it may be past the 30 days.`);
      if (!(r.wasDeletedAt > 0)) throw new M.UserError(`"${a.project}" isn't deleted.`);
      return json({ restored: r.name });
    });

  reg("read_drawing",
    "The walls (with ids, length, ends, which side the room is on), doors and windows (wall, position, hinge, swing), cabinets and appliances " +
    "(shop code like B24/SB36/W3030, wall, distance from the corner, which way the front faces, drawer/door front), other symbols, room labels, " +
    "floor hatches, elevations, the exterior elevations and the roof of a project. " + COORDS,
    { project: Project, raw: z.boolean().optional().describe("Also return the raw element list (large)") },
    async (a) => {
      const p = await S.readProject(ctx.db, ctx.uid, a.project);
      if (!p) throw new M.UserError(`No project named "${a.project}".`);
      const out = T.readDrawing(p.state);
      out.updated = new Date(p.updatedAt).toISOString();
      out.lastSavedBy = p.device;
      out.activeLayer = L.activeLayer(p.state).id; // new drawing lands here (list_levels_and_layers for all)
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

  // ------------------------------------------------------------------ the open app runs Claude's actions (bridge.js)

  reg("app_status",
    "Which copies of Site Draft are open right now (desktop, tablet, web), what project each has open and when each was last used. run_actions needs one open.",
    {}, async () => json(await B.status(ctx.db, ctx.uid)));

  reg("list_actions",
    "Every action the Site Draft app itself runs (the same ones a tap or a panel button sends), generated from the app's code: name, arguments, what it does. " +
    "Give action for one in full (argument types, enum values, the fields of settings objects), or search to filter.",
    { action: z.string().optional(), search: z.string().optional() },
    async (a) => json(B.listActions(a)));

  reg("run_actions",
    "Has the open Site Draft app run a list of its own actions, in order, with its own code (so everything the app can do, Claude can do), save, and answer with " +
    "what ran, anything the app said (a refusal or hint), and what was added, removed or changed. One wrong action name or argument and nothing runs. " +
    "Points are \"x,y\" in world pixels (x east, y south); the answer's pxPerFoot converts (pixels per inch = pxPerFoot / 12). " +
    "Typical: SelectSymbol then PlaceAt; StartPlacement then PlaceAt; SelectElements then UpdateElementProperty / DeleteSelectedElement; SetBuilding; DimensionPlan; Undo.",
    {
      project: Project,
      actions: z.array(z.object({ action: z.string(), args: z.record(z.any()).optional() })).min(1),
      wait_seconds: z.number().optional().describe("How long to wait for the app's answer (default 25)"),
    },
    async (a) => json(await B.runActions(ctx.db, ctx.uid, { project: a.project, actions: a.actions, waitMs: (a.wait_seconds || 25) * 1000 })));

  reg("command_status", "The answer to a run_actions command that was still waiting or running.",
    { command: z.string() }, async (a) => json(await B.commandStatus(ctx.db, ctx.uid, a.command)));

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
      status: z.enum(["existing", "demo", "new"]).optional().describe("Remodel: existing for walls measured in a house as it stands (outline), demo for walls coming out (dashed), new (default) for new work"),
      thickness: Len.optional().describe("The wall's finished thickness, face to face, when it isn't the wall type's (old houses: e.g. 6\" or 5 5/8\")"),
    },
    change("draw_walls", T.drawWalls));

  reg("place_opening",
    "Puts a door, window, sliding door or cased opening in a wall: measured from the left/right end (top/bottom on a north-south wall) " +
    "of the whole wall, from that end's inside corner (or the building's outside corner), to the near edge or the centre, like a field string " +
    "along the wall, even where interior walls meet it partway. The result also gives the distance from the corner of the room it's in (the app's Position). " +
    "Doors need swing_into (the side the door swings to) and hinge (left/right); a pocket door needs pocket (the side it slides into). " +
    "Bifold, double and barn doors are doors too: swing_into is the side they open to (a barn door hangs on that face); hinge right = a single bifold folds to the right jamb, a barn door slides right.",
    {
      project: Project, wall_id: z.string(),
      kind: z.enum(["door", "window", "opening", "sliding door", "pocket door", "bifold door", "double door", "barn door", "bi-parting barn door"]).optional(),
      type: z.string().optional().describe("Exact catalog name instead of kind, e.g. Door 2'-8\", Door 3'-0\" Bifold, Door 6'-0\" Double, Door 3'-0\" Barn"),
      width: Len.optional(), from: z.enum(["left", "right", "top", "bottom"]).optional(), to: z.enum(["edge", "center"]).optional(),
      corner: z.enum(["inside", "outside"]).optional(), distance: Len.optional(),
      hinge: z.enum(["left", "right"]).optional(), swing_into: Compass.optional(),
      pocket: z.enum(["left", "right", "top", "bottom"]).optional().describe("Pocket door: the side it slides into (top/bottom on a north-south wall)"),
      height: Len.optional().describe("Door or opening height (door default 6'-8\"), or window height (default 4')"), sill: Len.optional().describe("Window sill height from the floor (default 36\")"),
    },
    change("place_opening", T.placeOpening));

  reg("place_cabinets",
    "Places a run of cabinets and appliances with their backs against one face of a wall, in order from the chosen end (inside corner by default) plus an offset. " +
    "Items: shop codes (B15, BD18, SB36 adds a sink, LS36, W3030, W1530, WDC2430, P2484, OV3384 tall oven cabinet with a double oven in it ({code: \"OV3384\", oven: \"single\"|\"none\"}), V30, VD18, BF3, WF3, DW), exact catalog names " +
    "(\"Range 30\\\"\", \"Refrigerator 36\\\" French Door\"), \"GAP 30\" for empty space, or {code, width, front} ({code: \"BD\", width: 29} for an odd size). Base and wall cabinets go in separate calls. " +
    "A wall that borders several rooms (an exterior wall the length of the house) has several sets of inside corners: give room (its label) or near (a point in the room) so the run starts at that room's corner.",
    {
      project: Project, wall_id: z.string(),
      items: z.array(z.union([z.string(), z.object({ code: z.string().optional(), name: z.string().optional(), width: Len.optional(), depth: Len.optional(), front: z.union([z.string(), z.number()]).optional(), sink: z.boolean().optional(), oven: z.string().optional().describe("OV only: double (default), single or none") })])).min(1),
      face: Compass.optional().describe("Which face of the wall the cabinets stand against (default: the room side)"),
      from: z.enum(["left", "right", "top", "bottom"]).optional(), corner: z.enum(["inside", "outside"]).optional(), offset: Len.optional(),
      style: z.union([z.string(), z.number()]).optional().describe("slab, shaker, raised panel, glass"),
      hardware: z.union([z.string(), z.number()]).optional().describe("knobs, bar pulls, knobs + drawer pulls"),
      front: z.union([z.string(), z.number()]).optional().describe("Base fronts: doors, drawer + doors, 3 drawers, 4 drawers"),
      room: z.string().optional().describe("The room the cabinets are in, by its label (e.g. Kitchen): its inside corners are the ones measured from"),
      near: z.object({ x: Len, y: Len }).optional().describe("Or a point inside that room, inches"),
    },
    change("place_cabinets", T.placeCabinets));

  const Fronts = {
    front: z.union([z.string(), z.number()]).optional().describe("Cabinets: doors, drawer + doors, 3 drawers, 4 drawers"),
    style: z.union([z.string(), z.number()]).optional().describe("Cabinets: slab, shaker, raised panel, glass"),
    hardware: z.union([z.string(), z.number()]).optional().describe("Cabinets: knobs, bar pulls, knobs + drawer pulls"),
  };
  reg("place_symbol", "Places any library symbol (island, appliance, fixture, furniture...) by its exact catalog name, centred at x, y (inches). " +
    "Symbols are drawn back at the top: rotation 0 faces south, 90 west, 180 north, 270 east; the result says which way it faces and which wall it's against. " + COORDS,
    { project: Project, name: z.string(), x: Len, y: Len, rotation: z.number().optional().describe("Degrees clockwise"), width: Len.optional(), depth: Len.optional(), ...Fronts },
    change("place_symbol", T.placeSymbol));

  reg("add_label", "Adds text at x, y (top-left, inches).", { project: Project, text: z.string(), x: Len, y: Len, size: Len.optional() },
    change("add_label", T.addLabel));

  reg("edit_element", "Moves, turns, resizes or re-labels one element by id, sets a cabinet's front, style or hardware, or merges symbol properties (e.g. {\"hingeRight\": 1}). " +
    "The result says which way a symbol now faces and which wall it's against.",
    { project: Project, id: z.string(), move_by: z.object({ x: Len.optional(), y: Len.optional() }).optional(), rotation: z.number().optional(),
      width: Len.optional(), depth: Len.optional(),
      length: Len.optional().describe("A wall's length along it, whichever way it runs (width does the same on a wall)"),
      thickness: Len.optional().describe("A wall's thickness (depth does the same on a wall)"),
      keep: z.enum(["north", "south", "east", "west"]).optional().describe("Changing a wall's length: the end that stays put (default: both ends move)"),
      text: z.string().optional(), properties: z.record(z.string(), z.number()).optional(), ...Fronts,
      status: z.enum(["existing", "demo", "new"]).optional().describe("A wall's remodel status: existing, demo (the whole wall comes out) or new") },
    change("edit_element", T.editElement));

  // ------------------------------------------------------------------ stairs (app: Stairs.kt)
  reg("place_stairs",
    "Places a stair sized to the building: goes up to the floor above or down to the floor below / the basement slab (auto: up when there is a floor above, " +
    "otherwise down), the rise from the floor heights unless rise is given, risers as close to 7 1/2\" as it allows (6 1/4\" to 8 1/4\"). " +
    "climbs = the way you walk going UP it. Say where with top (the middle of its top edge, where it meets the upper floor; straight and deck stairs) or center. " +
    "It goes on the active layer's floor. With Build from the floor plan on, Site Draft opens the floor framing over it (headroom 6'-8\") when it reloads. " +
    "The plan shows UP or DN with the riser count (e.g. DN 13R).",
    { project: Project, type: z.enum(["straight", "l", "u", "spiral", "deck"]).optional().describe("Default straight"),
      goes: z.enum(["auto", "up", "down"]).optional(), climbs: Compass.optional().describe("Default north (up the page)"),
      top: z.object({ x: Len, y: Len }).optional().describe("Middle of the top edge, inches. " + COORDS), center: z.object({ x: Len, y: Len }).optional(),
      width: Len.optional().describe("Default 36\""), tread: Len.optional().describe("Tread depth, default 10\""),
      rise: Len.optional().describe("Only to override the building (typed by hand)"), turn: z.enum(["left", "right"]).optional().describe("L stair: which way it turns"),
      show_risers: z.boolean().optional().describe("Riser count on the plan (default on)"),
      handrail: z.enum(["none", "left", "right", "both"]).optional().describe("Stairs come in bare (no rails) unless Dan asks for one; left/right as you walk up"),
      winders: z.number().int().optional().describe("L stair: 0 a flat landing (default), 2 or 3 winders in the corner"),
      left_side: z.enum(["none", "handrail", "guard"]).optional(), right_side: z.enum(["none", "handrail", "guard"]).optional() },
    change("place_stairs", T.placeStairs));
  reg("edit_stairs",
    "Changes a stair: goes (auto, up, down), rise (a length typed by hand, or \"building\" to follow the floor heights again), width, tread, show_risers, " +
    "rails (handrail none/left/right/both, or left_side/right_side none/handrail/guard; left/right as you walk up). The footprint follows.",
    { project: Project, id: z.string(), goes: z.enum(["auto", "up", "down"]).optional(), rise: z.union([Len, z.literal("building")]).optional(),
      width: Len.optional(), tread: Len.optional(), show_risers: z.boolean().optional(),
      handrail: z.enum(["none", "left", "right", "both"]).optional(), left_side: z.enum(["none", "handrail", "guard"]).optional(), right_side: z.enum(["none", "handrail", "guard"]).optional(),
      winders: z.number().int().optional().describe("L stair: 0 a flat landing, 2 or 3 winders"), flight1: z.number().int().optional().describe("L or U stair: risers before the corner") },
    change("edit_stairs", T.editStairs));

  // ------------------------------------------------------------------ remodel (app: Remodel.kt)
  reg("demo_wall_part",
    "Remodel: marks part of a wall to come out. The wall is split; the piece coming out is demo (dashed), the rest keeps its status (existing). " +
    "from = where the demo starts, measured from measured_from (left/right end of an east-west wall, top/bottom = north/south end of a north-south one); length = how much comes out. " +
    "For a whole wall use edit_element with status demo. read_drawing first for the wall id; the result gives the new pieces' ids.",
    { project: Project, wall_id: z.string(), from: Len.describe("Distance from the end to where the demo starts (0 = at the end)"), length: Len,
      measured_from: z.enum(["left", "right", "top", "bottom", "west", "east", "north", "south"]).optional().describe("Default left (east-west wall) or top (north-south wall)") },
    change("demo_wall_part", T.demoWallPart));
  reg("set_remodel_view",
    "Remodel: which plan Site Draft shows (all = every wall in its own style; existing = the house as it stands; demo = what comes out, dashed; proposed = existing plus new, demo gone) " +
    "and what walls drawn in the app are drawn as (existing, demo, new).",
    { project: Project, show: z.enum(["all", "existing", "demo", "proposed"]).optional(), draw_new_walls_as: z.enum(["existing", "demo", "new"]).optional() },
    change("set_remodel_view", T.setRemodel));

  reg("delete_elements", "Deletes elements by id (walls, symbols, labels, hatches, elevations...).", { project: Project, ids: z.array(z.string()).min(1) }, change("delete_elements", T.deleteElements));
  reg("hatch_area",
    "Floor finish for a room, as the app's Hatch an area: fills the room (between the nearest walls on each side) with a pattern at a tile or plank size. " +
    "pattern: tile, planks (plank tile, hardwood, LVP, laminate), herringbone, hex, brick/subway, concrete, carpet... size = the tile or plank width (up the page), " +
    "length = its length (across the page) for planks, brick and herringbone. ASK Dan the size if he hasn't said. A hatch already in that room is replaced; delete one with delete_elements.",
    { project: Project, room: z.string().optional().describe("The room's label, e.g. Master Bath"), near: z.object({ x: Len, y: Len }).optional().describe("Or a point inside the area, inches"),
      pattern: z.string(), size: Len, length: Len.optional() },
    change("hatch_area", T.hatchArea));
  reg("add_elevation",
    "A live elevation of one wall (or an island face): it shows the cabinets with their fronts, appliances, openings and closet parts against that face, and follows the plan. " +
    "looking = the way the viewer looks at the wall (default: from the room). Placed in a row under the plan; put it on a sheet in the app. " +
    "For a whole room (each of its walls cut to the room, titled by the room's name, walls standing in front such as a closet front shown too), " +
    "use run_actions with AddRoomElevations {point: \"x,y\" inside the room in world px, walls: [\"north\",\"east\",\"south\",\"west\"] or [] for all}; it needs the app open.",
    { project: Project, wall_id: z.string().describe("A wall id, or an island's id"), looking: Compass.optional(),
      island_face: z.enum(["front", "back", "left end", "right end"]).optional(),
      wall_height: Len.optional().describe("Default 8'"), crown: Len.optional().describe("Crown on the uppers, its height"), base_molding: Len.optional().describe("Baseboard height"),
      casing: z.boolean().optional().describe("Door and window casing"),
      backsplash: z.object({ from: Len.optional(), to: Len.optional(), pattern: z.enum(["grid", "subway", "herringbone", "hex"]).optional(), size: Len.optional(), length: Len.optional(),
        whole_wall: z.boolean().optional() }).optional().describe("Wall tile: from/to heights (default 36\" to 54\"), only over the counters unless whole_wall") },
    change("add_elevation", T.addElevation));

  // ------------------------------------------------------------------ levels and layers (app: Building.kt)

  reg("list_levels_and_layers",
    "The building's levels (footing, foundation, framing, floors, roof) with elevations worked out from its inputs (first floor top of subfloor = 100'-0\" by default), " +
    "and its layers: which is active (where new drawing goes), which are shown, how many elements each holds.",
    { project: Project },
    async (a) => {
      const p = await S.readProject(ctx.db, ctx.uid, a.project);
      if (!p) throw new M.UserError(`No project named "${a.project}".`);
      return json(L.describe(p.state));
    });

  reg("set_active_layer",
    "Makes a layer (by name or id) the one new drawing goes on, as tapping it in the app's Layers panel does. Everything the connector draws after this lands on it.",
    { project: Project, layer: z.string() }, change("set_active_layer", L.setActiveLayer));

  reg("show_hide_layers", "Shows or hides layers (by name or id). Hidden layers are not drawn, picked or printed in the app. The active layer can't be hidden.",
    { project: Project, layers: z.array(z.string()).min(1), visible: z.boolean() }, change("show_hide_layers", L.showHideLayers));

  reg("add_layer", "Adds a layer of Dan's own (e.g. Electrical), on a level or the whole building, and makes it active unless make_active is false.",
    { project: Project, name: z.string(), level: z.string().optional().describe("Level id or name; omit for the whole building"), make_active: z.boolean().optional() },
    change("add_layer", L.addLayer));

  reg("set_building_inputs",
    "Changes the inputs the levels are worked out from; only what is given changes. Dan's defaults: first floor top of subfloor 100'-0\", 3/4\" subfloor, " +
    "9 1/4\" joists (2x10), 1 1/2\" sill, basement 11 courses of 8\" block, crawl spaces 24\" clear under the joists, footing 8\" x 18\". " +
    "upper_floors replaces the floors above the first (each with its top of subfloor) and adds their layers. Ask Dan rather than guess any size.",
    {
      project: Project,
      foundation: z.enum(["basement", "crawl", "slab"]).optional(),
      first_floor_top_of_subfloor: Len.optional(), subfloor: Len.optional(), joist_depth: Len.optional(), sill: Len.optional(),
      block_courses: z.number().int().optional(), course_height: Len.optional(), crawl_clearance: Len.optional(),
      footing_thickness: Len.optional(), footing_width: Len.optional(),
      upper_floors: z.array(z.object({ name: z.string(), top_of_subfloor: Len })).optional(),
      show_existing_framing: z.boolean().optional().describe(
        "Remodels: when every exterior wall is existing, the existing house gets no floor framing unless this is true; then it shows the simple way " +
        "an existing-conditions plan does (joist direction and size, EXIST. STEEL BEAM, posts). Off by default."),
      build_from_floor_plan: z.boolean().optional().describe(
        "true: Site Draft builds the foundation (block walls and footings under the exterior 2x6 walls), the first-floor framing " +
        "(joists the short way at 16\" o.c., beams so no span is over 14', posts on pad footings) and a ladder under every wall " +
        "that runs with the joists, from the first-floor plan; it rebuilds when the plan changes, the app does the building when the project is next opened"),
    },
    change("set_building_inputs", L.setBuildingInputs));

  reg("set_roof",
    "Sets up the roof over the main house (the outside walls less any wings, bearing on the top floor's plate; the app's Roof structural layer and roof plan): trusses or rafters, pitch, overhangs, heel, spacing, " +
    "gable or hip ends, roofing. Only what is given changes; Dan's defaults are trusses, 6/12, 12\" eave and rake overhang, 4\" heel, 24\" o.c., gable both ends, architectural shingles. " +
    "Site Draft builds the roof plan and framing when it reloads the project; the exterior elevations show it. remove: true takes it away. Ask Dan rather than guess.",
    { project: Project, framing: z.enum(["trusses", "rafters"]).optional(), pitch: z.union([z.number(), z.string()]).optional().describe("Rise per 12, e.g. 6 or \"6/12\""),
      overhang: Len.optional().describe("Eave overhang, level, past the wall"), rake: Len.optional().describe("Overhang at a gable end"), heel: Len.optional(),
      spacing: Len.optional().describe("Truss or rafter spacing (switching framing sets 24\" trusses / 16\" rafters unless given)"), rafter_size: z.string().optional().describe("e.g. 2x8 (rafters)"),
      girder_height: Len.optional().describe("Truss hip ends (step-down hip): the hip girder goes where the truss is this tall, default 5' (Dan: 4'-6')"),
      addon_spacing: Len.optional().describe("Garage and wing truss spacing, default 24\""),
      ends: z.enum(["gable", "hip"]).optional().describe("Both ends"), end_a: z.enum(["gable", "hip"]).optional().describe("The west (or north) end"),
      end_b: z.enum(["gable", "hip"]).optional().describe("The east (or south) end"), turn_ridge: z.boolean().optional().describe("Run the ridge the short way"),
      flat_top: z.boolean().optional().describe("A flat on top: the slopes stop at a flat deck, by default at the main gable's ridge height (the highest full-height wing's), where its valleys end"),
      flat_top_height: Len.optional().describe("The flat deck's elevation, if not the main gable's ridge"),
      covering: z.string().optional().describe("shingles, standing seam, metal (exposed fastener), cedar shakes, spanish tile, slate"), remove: z.boolean().optional() },
    change("set_roof", L.setRoof));
  reg("set_roof_area",
    "A traced roof area, for a roof a rectangle can't describe (an old house, a cross gable, a flat where the rafters frame in): its corners in plan inches " +
    "(outside faces of the walls, in order around it) and what each side is: eave (the roof slopes down to it), gable (rake), or open (it runs into another roof). " +
    "Once there are areas Site Draft builds the roof from them: each eave a plane rising at the pitch from the top of the wall plate (plus the heel); in an area the " +
    "lowest plane wins (ridges, hips), a flat caps them (flat_height, or flat_at_ridge_of another area: Dan's flat-roof ridge); where areas overlap the highest roof " +
    "wins (valleys), and a lower roof that doesn't reach the higher one stops against its wall. Overlap areas where one roof runs into another. Same name replaces; " +
    "remove: true takes one away; clear_all: true takes them all away (back to the rectangle). Framing, spacing, heel and roofing come from set_roof. Ask Dan rather than guess.",
    { project: Project, name: z.string().optional().describe("e.g. Main, West gable, East wing"),
      outline: z.array(z.union([z.object({ x: z.union([z.number(), z.string()]), y: z.union([z.number(), z.string()]) }), z.array(z.number()).length(2)])).optional()
        .describe("Corners in plan inches, x east, y south (as read_drawing gives the walls), in order around the area"),
      edges: z.array(z.enum(["eave", "gable", "open"])).optional().describe("One per side: side 1 runs from corner 1 to corner 2, the last side back to corner 1"),
      pitch: z.union([z.number(), z.string()]).optional().describe("Rise per 12; default the roof's"),
      wall_height: Len.optional().describe("Top of its wall plate above the first floor's top of subfloor (a one-story wing's walls); default the house's top plate"),
      overhang: Len.optional(), rake: Len.optional(),
      flat_height: Len.optional().describe("A flat on top this high above where the roof meets the wall"),
      flat_at_ridge_of: z.string().optional().describe("A flat at the ridge height of this other area (by name)"),
      no_flat: z.boolean().optional(), remove: z.boolean().optional(), clear_all: z.boolean().optional() },
    change("set_roof_area", L.setRoofArea));
  reg("set_exterior_elevations",
    "Makes the four exterior elevations (south, north, east, west): live from the plan, the exterior doors and windows, the foundation and the roof, on the Exterior elevation layer. " +
    "grade_exposure = how much foundation shows above grade (per job: ASK Dan). Site Draft builds them when it reloads the project. remove: true takes them away.",
    { project: Project, grade_exposure: Len.optional(), remove: z.boolean().optional() },
    change("set_exterior_elevations", L.setExteriorElevations));
  reg("set_structure",
    "The basement beam and posts (the app's Structure inputs): a steel beam shape like W10x15 (\"10 by 15\"), or auto to let Site Draft size it; " +
    "the most post spacing allowed (posts are spaced evenly, e.g. 12' gives 11'-7 3/8\" on a 58' run); lally or HSS posts; loads. Turns on Build from the floor plan. " +
    "Site Draft rebuilds the beam, posts and pad footings when it reloads the project.",
    { project: Project, beam: z.string().optional().describe("Steel W shape, e.g. W10x15; 'auto' to let the app size it"), post_spacing: Len.optional().describe("Most allowed between posts, e.g. 12'"),
      post_type: z.enum(["lally", "hss"]).optional(), max_joist_span: Len.optional(), live_load_psf: z.number().optional(), dead_load_psf: z.number().optional(),
      soil_bearing_psf: z.number().optional(), roof_snow_psf: z.number().optional(), build_from_floor_plan: z.boolean().optional() },
    change("set_structure", X.setStructure));

  reg("set_exterior_finish",
    "The house's siding: the main finish, a wainscot of stone or brick from the top of the foundation up to a height (e.g. 4' of stone), a different finish in the gables, corner trim. " +
    "Finishes: lap siding, board and batten, shake, brick, stone. Only what is given changes. The exterior elevations show it.",
    { project: Project, main: z.string().optional(), wainscot: z.string().optional().describe("stone, brick... or none"), wainscot_height: Len.optional().describe("Above the top of the foundation"),
      gable: z.string().optional(), corner_trim: Len.optional() },
    change("set_exterior_finish", X.setExteriorFinish));

  const sideArgs = {
    side: Compass.describe("The side of the house it's on (the 2x6 exterior walls)"),
    centered_on: z.string().optional().describe("Centre it on this element (a door id from read_drawing)"),
    from_corner: Len.optional().describe("Or: from the house's left (west) / top (north) outside corner to its near edge"),
  };
  reg("place_porch",
    "A porch against the house, as the app's Porch symbol: width along the wall (default 8'), depth out (default 5'), centred on the wall unless centered_on (a door) or from_corner is given. " +
    "type: framed (skirt), masonry (cap, veneer) or wood deck on posts; roof: shed, gable (facing out; a return gable), hip or none; columns: wood, masonry or mix; pier: pier to grade or cookie. " +
    "Site Draft builds its footings or piers, posts and roof when it reloads the project.",
    { project: Project, ...sideArgs, width: Len.optional(), depth: Len.optional(), type: z.string().optional(), roof: z.string().optional(), columns: z.string().optional(),
      pier: z.string().optional(), pitch: z.union([z.number(), z.string()]).optional().describe("Porch roof rise per 12; default 4/12 shed, the house's pitch otherwise") },
    change("place_porch", X.placePorch));

  reg("place_garage",
    "A garage against the house (or detached_gap away from it), as the app's Garage symbol: default 24' x 24', doors on the side away from the house unless door_side, one 16' door or two 9' doors, " +
    "roof: continue (the house roof), gable (turned 90) or hip. Site Draft builds its walls, slab, footings and roof when it reloads the project.",
    { project: Project, ...sideArgs, width: Len.optional(), depth: Len.optional(), detached_gap: Len.optional(), door_side: Compass.optional(), doors: z.string().optional(), roof: z.string().optional() },
    change("place_garage", X.placeGarage));

  reg("place_wing",
    "A wing: a part of the house that sticks out from the main house rectangle, with its own roof (a gable or hip end) tied into the house roof, " +
    "or into the house wall when it's lower. For a house already drawn wall by wall (a house as it stands) give rooms: the room labels it covers - " +
    "the wing uses those walls. Or outline (plan inches, outside faces) for a part that isn't a labelled room (a bay). Or side/width/depth like a garage, " +
    "and it builds its own 2x6 walls. height: one story (wall_height, default the house's) or full height (the house's top plate: a two-story bump-out). " +
    "ASK Dan which parts are one story and their wall height, and which way each roof runs, rather than guess.",
    { project: Project, rooms: z.union([z.array(z.string()), z.string()]).optional(),
      outline: z.object({ left: Len, top: Len, right: Len, bottom: Len }).optional(),
      ...sideArgs, side: Compass.optional(), width: Len.optional(), depth: Len.optional(),
      walls: z.enum(["drawn", "build"]).optional().describe("drawn = the walls already on the plan (default with rooms or outline); build = its own 2x6 walls"),
      height: z.string().optional().describe("one story (default) or full height"), wall_height: Len.optional().describe("One story's wall height, top of subfloor to top of plate"),
      pitch: z.union([z.number(), z.string()]).optional(), end: z.string().optional().describe("The far end: gable, hip or match the house"),
      foundation: z.string().optional().describe("match the house, crawl space or slab") },
    change("place_wing", X.placeWing));

  reg("build_sheets",
    "Builds the sheet set in Dan's order, one sheet per subject: T-1 cover/legend, T-2 notes, C-1 site plan (if any), S-1 foundation plan, S framing plan per floor, S roof framing, " +
    "S wall section and structural calcs, S details, A floor plan per floor, A roof plan, A exterior elevations, A interior elevations, A door/window/finish schedules and A cabinet/appliance/fixture schedules (details in building order). " +
    "Views sit centred on each sheet (the app numbers the set). Sheets already there are kept unless replace: true.",
    { project: Project, paper: z.string().optional().describe("Arch D (24x36, default), Arch C, Arch E1, 11x17"), scale: z.string().optional().describe("Plan and elevation scale: 1/4 (default), 1/8, 3/8, 1/2"),
      replace: z.boolean().optional(),
      only: z.union([z.array(z.string()), z.string()]).optional().describe("Add only the sheets whose title has one of these words (e.g. [\"cover\", \"notes\"]); the rest of the set is left alone") },
    change("build_sheets", X.buildSheets));

  reg("list_sheets", "The project's sheets: number, title, paper and the views on each.", { project: Project }, async (a) => {
    const p = await S.readProject(ctx.db, ctx.uid, a.project);
    if (!p) throw new M.UserError(`No project named "${a.project}".`);
    return json(X.listSheets(p.state));
  });

  reg("move_to_layer", "Puts elements (by id, from read_drawing) on a layer. Nothing moves on the drawing.",
    { project: Project, ids: z.array(z.string()).min(1), layer: z.string() }, change("move_to_layer", L.moveToLayer));

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

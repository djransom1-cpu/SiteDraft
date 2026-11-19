# Site Draft MCP connector

Lets Claude work in Site Draft: list and read projects, create projects, import PDF plans,
draw walls from measured lengths, place doors, windows and cabinets, edit, delete and undo.
Deployed as the Cloud Function `sitedraftMcp` in the `sitedraft-djransom` Firebase project, in its own
codebase `mcp` (folder `functions-mcp`, so it never needs the Stripe functions' secrets);
built the same way as Crewsync's `crewsyncMcp`.

## Files

| File | What it does |
| --- | --- |
| `mcp.js` | The MCP server (tools), the OAuth routes, the PDF upload page |
| `tools.js` | The drawing tools as pure functions: state + arguments -> new state + result |
| `geometry.js` | The app's plan geometry ported from Kotlin (FloorPlan, Elevation, OpeningPosition, cabinet codes) |
| `model.js` | The project file format (kotlinx JSON, packed colours kept exactly), lengths, symbols |
| `store.js` | Firestore: the app's chunked project documents, backups, the reload stamp |
| `pdf.js` | PDF pages rendered at 72 DPI as the desktop app does (pdf.js + @napi-rs/canvas) |
| `oauth.js` | OAuth provider, adapted from Crewsync |
| `catalog.json`, `fixtures/sample-*`, `empty-project.json` | Generated from the app by `ConnectorExportTest` (do not edit) |
| `fixtures/connector-*` | Written by `test/sitedraft-mcp.test.js`, opened with the app's code by `ConnectorCheckTest` |

## Firestore

- `users/{uid}/projects/{name}` (+ `chunks/{i}`): the project, exactly as the app saves it. Saved with
  `device: "Claude"` and an `updatedAt` newer than the cloud copy's.
- `users/{uid}/projectStamps/{name}`: `{ updatedAt }` of Claude's last change. The open project polls it
  every 5 seconds (ProjectSync.startWatching) and reloads; edits made on the device in between are
  kept as "<name> (<device> edits)".
- `users/{uid}/mcpHistory/{id}`: the drawing before each change (newest 30 per project) for `undo_last_change`.
- `mcp_oauth_*`, `mcp_access_keys`, `mcp_uploads`: connector sign-in and upload links. No client access.

## Deploy

Double-click `deploy-connector.bat` in the SiteDraft folder. The first run sets the `SITEDRAFT_MCP_KEY`
secret from `AndroidStudioProjects\Firebase keys\sitedraft-mcp-key.txt` (the access key typed on the
consent page). Then in Claude: Settings > Connectors > Add custom connector, URL = the Cloud Run URL the
deploy prints (`https://sitedraftmcp-....run.app`), and type the key when asked.

## Test

`cd functions-mcp && npm test` (no network or Firebase needed), then the desktop tests
(`ConnectorCheckTest`, `ProjectSyncWatchTest`).

/**
 * Site Draft MCP connector (sitedraft/): lets Claude read and draw in the owner's Site Draft
 * projects. Its own Firebase codebase ("mcp"), so deploying it never needs the Stripe functions'
 * secrets. Connector URL = this function's Cloud Run URL; the access key typed on the consent page
 * is the SITEDRAFT_MCP_KEY secret. See sitedraft/README.md.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");

initializeApp();

const SITEDRAFT_MCP_KEY = defineSecret("SITEDRAFT_MCP_KEY");
const apps = new Map(); // built lazily per address: secrets are only readable inside a request

exports.sitedraftMcp = onRequest({ secrets: [SITEDRAFT_MCP_KEY], invoker: "public", timeoutSeconds: 300, memory: "1GiB" }, (req, res) => {
  const { buildApp, baseUrlOf } = require("./sitedraft/mcp");
  const base = baseUrlOf(req);
  if (!apps.has(base)) apps.set(base, buildApp(getFirestore(), { legacySecret: SITEDRAFT_MCP_KEY.value(), base, auth: getAuth() }));
  apps.get(base)(req, res);
});

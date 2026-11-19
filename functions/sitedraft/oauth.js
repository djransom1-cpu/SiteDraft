const crypto = require("crypto");
const { getFirestore } = require("firebase-admin/firestore");
const {
  InvalidTokenError,
  AccessDeniedError,
  ServerError,
} = require("@modelcontextprotocol/sdk/server/auth/errors.js");

/**
 * OAuth provider for the Site Draft MCP server (used by mcpAuthRouter in sitedraft/mcp.js).
 * Adapted from Crewsync's functions/oauth.js; same flow, Site Draft's own collections and key.
 *
 * Any MCP client may dynamically register (standard MCP behavior), but a human must type a
 * Site Draft *access key* once at the /authorize consent page before a code (and therefore a
 * bearer token) is issued. The token records which account the key belongs to, so every tool
 * call runs as that person.
 *
 * Keys are per person. `mcp_access_keys/{sha256(key)}` = { email, label, timezone, enabled, ... }
 * (none yet; room for per-person keys later). The Firebase secret (
 * SITEDRAFT_MCP_KEY) is the owner's key and maps to the owner account.
 *
 * All state lives in Firestore because Cloud Functions instances are stateless/ephemeral:
 *   mcp_oauth_clients, mcp_oauth_pending, mcp_oauth_codes, mcp_oauth_tokens
 */

const DEFAULT_TIMEZONE = "America/Detroit";
// The account the secret key belongs to.
const OWNER_EMAIL = "djransom1@gmail.com";

const PENDING_TTL_MS = 10 * 60 * 1000;
const CODE_TTL_MS = 5 * 60 * 1000;
const TOKEN_TTL_SECONDS = 400 * 24 * 60 * 60; // long-lived; no refresh flow implemented

function randomToken() {
  return crypto.randomBytes(32).toString("base64url");
}

function hashKey(key) {
  return crypto.createHash("sha256").update(String(key)).digest("hex");
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a || ""));
  const y = Buffer.from(String(b || ""));
  return x.length === y.length && x.length > 0 && crypto.timingSafeEqual(x, y);
}

// Returns { provider, confirmAuthorization }.
function buildProvider({ legacySecret, db: dbOverride, base = "" }) {
  const db = () => dbOverride || getFirestore();

  const clientsStore = {
    async getClient(clientId) {
      const doc = await db().collection("mcp_oauth_clients").doc(clientId).get();
      return doc.exists ? doc.data() : undefined;
    },
    async registerClient(client) {
      // Firestore rejects `undefined` field values (e.g. client_secret on a public client).
      const clean = JSON.parse(JSON.stringify(client));
      await db().collection("mcp_oauth_clients").doc(client.client_id).set(clean);
      return client;
    },
  };

  const provider = {
    clientsStore,

    // Renders a one-field consent form. It submits to /authorize/confirm (registered in sitedraft/mcp.js,
    // outside mcpAuthRouter) which issues the code and redirects back to the client.
    async authorize(client, params, res) {
      const authId = randomToken();
      await db().collection("mcp_oauth_pending").doc(authId).set({
        clientId: client.client_id,
        redirectUri: params.redirectUri,
        codeChallenge: params.codeChallenge,
        state: params.state || null,
        resource: params.resource ? params.resource.toString() : null,
        createdAt: Date.now(),
        expiresAt: Date.now() + PENDING_TTL_MS,
      });

      res.set("Content-Type", "text/html");
      res.send(`<!doctype html>
<html><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Authorize Site Draft</title></head>
<body style="font-family:sans-serif;max-width:420px;margin:80px auto;padding:0 16px;">
<h2>Authorize Site Draft access</h2>
<p>Enter your Site Draft access key to let Claude work in your Site Draft drawings.</p>
<form method="GET" action="${base}/authorize/confirm">
<input type="hidden" name="authId" value="${authId}" />
<input type="password" name="secret" placeholder="Access key" style="width:100%;padding:8px;font-size:16px;box-sizing:border-box;" autofocus />
<button type="submit" style="margin-top:12px;padding:8px 16px;font-size:16px;">Authorize</button>
</form>
<p style="color:#666;font-size:13px;margin-top:24px;">The key is the SITEDRAFT_MCP_KEY secret set when the connector was deployed.</p>
</body></html>`);
    },

    async challengeForAuthorizationCode(client, authorizationCode) {
      const doc = await db().collection("mcp_oauth_codes").doc(authorizationCode).get();
      if (!doc.exists) throw new InvalidTokenError("Unknown authorization code");
      return doc.data().codeChallenge;
    },

    async exchangeAuthorizationCode(client, authorizationCode) {
      const ref = db().collection("mcp_oauth_codes").doc(authorizationCode);
      const doc = await ref.get();
      if (!doc.exists) throw new InvalidTokenError("Unknown authorization code");
      const code = doc.data();
      await ref.delete();
      if (code.expiresAt < Date.now()) throw new InvalidTokenError("Authorization code expired");
      if (code.clientId !== client.client_id) throw new InvalidTokenError("Client mismatch");

      const accessToken = randomToken();
      await db().collection("mcp_oauth_tokens").doc(accessToken).set({
        clientId: client.client_id,
        email: code.email,
        timezone: code.timezone || DEFAULT_TIMEZONE,
        keyId: code.keyId || null,
        scopes: [],
        createdAt: Date.now(),
        expiresAt: Date.now() + TOKEN_TTL_SECONDS * 1000,
      });

      return { access_token: accessToken, token_type: "bearer", expires_in: TOKEN_TTL_SECONDS };
    },

    async exchangeRefreshToken() {
      throw new ServerError("Refresh tokens are not issued by this server");
    },

    async verifyAccessToken(token) {
      const doc = await db().collection("mcp_oauth_tokens").doc(token).get();
      if (!doc.exists) throw new InvalidTokenError("Invalid access token");
      const info = doc.data();
      if (info.expiresAt < Date.now()) throw new InvalidTokenError("Access token expired");
      // If this token came from a per-person key, honor a later revoke of that key.
      if (info.keyId) {
        const k = await db().collection("mcp_access_keys").doc(info.keyId).get();
        if (!k.exists || k.data().enabled === false) throw new InvalidTokenError("Access key revoked");
        db().collection("mcp_access_keys").doc(info.keyId).update({ lastUsedAt: Date.now() }).catch(() => {});
      }
      return {
        token,
        clientId: info.clientId,
        scopes: info.scopes || [],
        expiresAt: Math.floor(info.expiresAt / 1000),
        // Tokens issued before per-person keys existed have no email: they belong to the owner.
        extra: { email: (info.email || OWNER_EMAIL).toLowerCase(), timezone: info.timezone || DEFAULT_TIMEZONE },
      };
    },
  };

  /** Resolve a typed key to an identity: a per-person key doc, or the legacy shared secret → owner. */
  async function identityForKey(keyAttempt) {
    const attempt = String(keyAttempt || "").trim();
    if (!attempt) return null;
    const k = await db().collection("mcp_access_keys").doc(hashKey(attempt)).get();
    if (k.exists) {
      if (k.data().enabled === false) throw new AccessDeniedError("This access key has been revoked");
      return { email: String(k.data().email).toLowerCase(), timezone: k.data().timezone || DEFAULT_TIMEZONE, keyId: k.id };
    }
    if (legacySecret && safeEqual(attempt, legacySecret)) return { email: OWNER_EMAIL, timezone: DEFAULT_TIMEZONE, keyId: null };
    return null;
  }

  // Called by the /authorize/confirm route in sitedraft/mcp.js once the human submits the consent form.
  async function confirmAuthorization({ authId, keyAttempt }) {
    const ref = db().collection("mcp_oauth_pending").doc(String(authId || ""));
    const doc = await ref.get();
    if (!doc.exists) throw new AccessDeniedError("Authorization request expired or not found");
    const pending = doc.data();
    if (pending.expiresAt < Date.now()) {
      await ref.delete();
      throw new AccessDeniedError("Authorization request expired");
    }
    const who = await identityForKey(keyAttempt);
    if (!who) throw new AccessDeniedError("Incorrect access key");

    const code = randomToken();
    await db().collection("mcp_oauth_codes").doc(code).set({
      clientId: pending.clientId,
      codeChallenge: pending.codeChallenge,
      email: who.email,
      timezone: who.timezone,
      keyId: who.keyId,
      expiresAt: Date.now() + CODE_TTL_MS,
    });
    await ref.delete();

    const redirect = new URL(pending.redirectUri);
    redirect.searchParams.set("code", code);
    if (pending.state) redirect.searchParams.set("state", pending.state);
    return redirect.toString();
  }

  return { provider, confirmAuthorization };
}

module.exports = { buildProvider, hashKey, DEFAULT_TIMEZONE, OWNER_EMAIL };

// SiteDraft Cloud Functions: the only code allowed to write users/{uid}.subscription.
//
// Deploy:   firebase deploy --only functions           (from the repo root, Blaze plan needed)
// Secrets:  firebase functions:secrets:set STRIPE_SECRET_KEY
//           firebase functions:secrets:set STRIPE_WEBHOOK_SECRET
//           firebase functions:secrets:set STRIPE_PRICE_ID
// Param:    APP_URL (where Stripe sends the user back; defaults to the GitHub Pages web app).
//
// The app calls createCheckoutSession / createPortalSession with the callable protocol over
// HTTPS (shared/.../cloud/CloudFunctions.kt) and Stripe calls stripeWebhook.

const { onCall, onRequest, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret, defineString } = require("firebase-functions/params");
const { logger } = require("firebase-functions");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const Stripe = require("stripe");
const { subscriptionDocFrom, uidFrom, HANDLED_EVENTS } = require("./subscription");

initializeApp();

const STRIPE_SECRET_KEY = defineSecret("STRIPE_SECRET_KEY");
const STRIPE_WEBHOOK_SECRET = defineSecret("STRIPE_WEBHOOK_SECRET");
const STRIPE_PRICE_ID = defineSecret("STRIPE_PRICE_ID");
const APP_URL = defineString("APP_URL", { default: "https://djransom1-cpu.github.io/SiteDraft/" });

function stripeClient() {
  return new Stripe(STRIPE_SECRET_KEY.value(), { apiVersion: "2025-02-24.acacia" });
}

/** A safe return URL: only http(s), falling back to APP_URL. */
function returnUrlFrom(data) {
  const candidate = data && typeof data.returnUrl === "string" ? data.returnUrl : "";
  return /^https?:\/\//i.test(candidate) ? candidate : APP_URL.value();
}

/** The Stripe customer for this Firebase user, created on first use and remembered on users/{uid}. */
async function customerFor(stripe, uid, email) {
  const db = getFirestore();
  const userRef = db.collection("users").doc(uid);
  const snap = await userRef.get();
  const existing = snap.exists ? snap.get("stripeCustomerId") : null;
  if (existing) return existing;
  const customer = await stripe.customers.create({
    email: email || undefined,
    metadata: { firebaseUid: uid },
  });
  await userRef.set({ stripeCustomerId: customer.id, email: email || null }, { merge: true });
  return customer.id;
}

exports.createCheckoutSession = onCall({ secrets: [STRIPE_SECRET_KEY, STRIPE_PRICE_ID] }, async (request) => {
  const auth = request.auth;
  if (!auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const stripe = stripeClient();
  const customer = await customerFor(stripe, auth.uid, auth.token.email);
  const returnUrl = returnUrlFrom(request.data);
  const session = await stripe.checkout.sessions.create({
    mode: "subscription",
    customer,
    client_reference_id: auth.uid,
    line_items: [{ price: STRIPE_PRICE_ID.value(), quantity: 1 }],
    allow_promotion_codes: true,
    success_url: returnUrl,
    cancel_url: returnUrl,
    metadata: { firebaseUid: auth.uid },
    subscription_data: { metadata: { firebaseUid: auth.uid } },
  });
  return { url: session.url };
});

exports.createPortalSession = onCall({ secrets: [STRIPE_SECRET_KEY] }, async (request) => {
  const auth = request.auth;
  if (!auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const stripe = stripeClient();
  const customer = await customerFor(stripe, auth.uid, auth.token.email);
  const session = await stripe.billingPortal.sessions.create({
    customer,
    return_url: returnUrlFrom(request.data),
  });
  return { url: session.url };
});

/** Finds the uid for a Stripe customer id through users/{uid}.stripeCustomerId. */
async function uidForCustomer(customerId) {
  if (!customerId) return null;
  const db = getFirestore();
  const hits = await db.collection("users").where("stripeCustomerId", "==", customerId).limit(1).get();
  return hits.empty ? null : hits.docs[0].id;
}

async function writeSubscription(uid, subscription) {
  const db = getFirestore();
  await db.collection("users").doc(uid).set(
    {
      subscription: subscriptionDocFrom(subscription),
      stripeCustomerId: typeof subscription.customer === "string" ? subscription.customer : subscription.customer?.id || null,
      subscriptionUpdatedAt: FieldValue.serverTimestamp(),
    },
    { merge: true }
  );
}

exports.stripeWebhook = onRequest({ secrets: [STRIPE_SECRET_KEY, STRIPE_WEBHOOK_SECRET] }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("POST only");
    return;
  }
  const stripe = stripeClient();
  let event;
  try {
    event = stripe.webhooks.constructEvent(req.rawBody, req.headers["stripe-signature"], STRIPE_WEBHOOK_SECRET.value());
  } catch (error) {
    logger.warn("Webhook signature check failed", error.message);
    res.status(400).send("Bad signature");
    return;
  }

  if (!HANDLED_EVENTS.has(event.type)) {
    res.status(200).send("ignored");
    return;
  }

  try {
    const object = event.data.object;
    let subscription = object;
    if (event.type === "checkout.session.completed") {
      if (!object.subscription) {
        res.status(200).send("no subscription on session");
        return;
      }
      subscription = await stripe.subscriptions.retrieve(object.subscription);
    }
    const customerId = typeof subscription.customer === "string" ? subscription.customer : subscription.customer?.id;
    const uid = uidFrom(subscription) || uidFrom(object) || (await uidForCustomer(customerId));
    if (!uid) {
      logger.error("No Firebase user for Stripe customer", customerId, event.type);
      res.status(200).send("no user");
      return;
    }
    await writeSubscription(uid, subscription);
    logger.info("Subscription updated", { uid, status: subscription.status, event: event.type });
    res.status(200).send("ok");
  } catch (error) {
    logger.error("Webhook handling failed", error);
    res.status(500).send("error");
  }
});

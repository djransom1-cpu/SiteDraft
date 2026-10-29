// Pure helpers shared by index.js and the tests: no Firebase, no Stripe client.

/**
 * The `users/{uid}.subscription` document for a Stripe subscription object. The app's gate
 * (shared/.../cloud/AccessGate.kt) reads exactly these fields, so keep them in step.
 */
function subscriptionDocFrom(subscription, now = Date.now()) {
  const item = subscription.items && subscription.items.data && subscription.items.data[0];
  // Stripe API 2025-03+ moved current_period_end onto the subscription item; older payloads
  // still carry it on the subscription itself.
  const periodEnd = (item && item.current_period_end) || subscription.current_period_end || 0;
  return {
    status: subscription.status || "",
    currentPeriodEnd: periodEnd * 1000,
    priceId: (item && item.price && item.price.id) || null,
    stripeSubscriptionId: subscription.id || null,
    cancelAtPeriodEnd: Boolean(subscription.cancel_at_period_end),
    updatedAt: now,
  };
}

/**
 * Which Firebase user a Stripe object belongs to. Checkout sessions and subscriptions created
 * by createCheckoutSession carry the uid in metadata; the customer record carries it too, and
 * as a last resort the caller looks the customer id up in Firestore.
 */
function uidFrom(stripeObject) {
  const meta = (stripeObject && stripeObject.metadata) || {};
  if (meta.firebaseUid) return meta.firebaseUid;
  if (stripeObject && stripeObject.client_reference_id) return stripeObject.client_reference_id;
  return null;
}

/** Only these events change what the gate sees. */
const HANDLED_EVENTS = new Set([
  "checkout.session.completed",
  "customer.subscription.created",
  "customer.subscription.updated",
  "customer.subscription.deleted",
]);

module.exports = { subscriptionDocFrom, uidFrom, HANDLED_EVENTS };
